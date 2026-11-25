import {Alert, Button, Card, Drawer, Form, Input, Popconfirm, Select, Space, Typography} from 'antd'
import {DeleteOutlined, DownloadOutlined, FileTextOutlined} from '@ant-design/icons'
import React from 'react'
import {HttpClient, Page, Perm, UrlUtils} from '@jiangood/open-admin'
import LogView from '../../components/LogView'
import {formatBytes} from '../../components/container/utils'

/**
 * 镜像下载 tar：在指定主机上拉取镜像（支持公共镜像），再用 docker save 打成 tar 包。
 * <p>
 * 优先由主机辅助容器打包（tar 落在挂载目录下，网页可直接下载）；
 * 未部署辅助容器时回落到 SSH 远端 / 平台服务器保存，同样支持网页下载。
 */
const POLL_INTERVAL = 2000
/** 日志结束后最多再确认几次状态（任务收尾 / WS 提前断开时兜底）。 */
const MAX_POLL_COUNT = 20

export default class extends React.Component {

    state = {
        hostOptions: [],
        logId: null,
        logVisible: false,
        submitting: false,
        finished: false,
        task: null,
    }

    pollTimer = null
    pollCount = 0

    componentDidMount() {
        HttpClient.get('admin/host/options')
            .then(rs => this.setState({hostOptions: rs.data || []}))
            .catch(() => {
            })
    }

    componentWillUnmount() {
        this.stopPoll()
    }

    submit = values => {
        this.stopPoll()
        this.setState({submitting: true, finished: false, task: null})
        HttpClient.post('admin/image-download/download', values).then(rs => {
            this.setState({logId: rs.data, logVisible: true})
        }).catch(() => {
            // 参数校验、无权限等请求未成功，不会产生日志，直接恢复按钮
            this.setState({submitting: false})
        })
    }

    /**
     * 日志流关闭表示后台任务结束：确认一次结果，让「下载 tar」立刻出现；
     * 还没登记上就继续轮询一小段时间兜底。
     */
    onLogClose = () => {
        this.setState({submitting: false, finished: true})
        this.pollCount = 0
        this.loadTask()
    }

    loadTask = () => {
        const {logId} = this.state
        if (!logId) {
            return
        }
        HttpClient.get('admin/image-download/task', {logId}, {toastError: false}).then(rs => {
            const task = rs.data || null
            this.setState({task})
            if (task?.ready) {
                this.stopPoll()
            } else {
                this.startPoll()
            }
        }).catch(() => this.startPoll())
    }

    startPoll = () => {
        if (this.pollTimer) {
            return
        }
        this.pollTimer = setInterval(() => {
            this.pollCount += 1
            if (this.pollCount > MAX_POLL_COUNT) {
                this.stopPoll()
                return
            }
            this.loadTask()
        }, POLL_INTERVAL)
    }

    stopPoll = () => {
        if (this.pollTimer) {
            clearInterval(this.pollTimer)
            this.pollTimer = null
        }
    }

    /** 同源普通链接，浏览器直接流式写盘（不走 axios blob，避免大镜像撑爆内存）。 */
    downloadUrl = () => {
        return UrlUtils.contextPath('/admin/image-download/tar')
            + '?logId=' + encodeURIComponent(this.state.logId)
    }

    deleteTar = () => {
        const {logId} = this.state
        HttpClient.postForm('admin/image-download/delete', {logId}).then(() => {
            this.setState({task: null})
        })
    }

    modeHint = mode => {
        if (mode === 'AGENT') {
            return 'tar 由主机辅助容器打包，保存在其挂载目录下；下载直接经 Docker API 读取，不会自动删除，可在终端里访问上面的路径或删除。'
        }
        if (mode === 'SSH') {
            return 'tar 保存在执行主机上，下载时经 SFTP 读取；不会自动删除，请按需清理。'
        }
        return '该主机为 TCP 连接，docker API 无法写远端文件，tar 保存在平台服务器；不会自动删除，请按需清理。'
    }

    renderTask = () => {
        const {logId, task, finished} = this.state
        if (!logId) {
            return null
        }
        if (!task) {
            return <Alert style={{marginTop: 16}} type={finished ? 'warning' : 'info'} showIcon
                          message={finished ? '任务已结束，暂未看到可下载的 tar' : '正在拉取并打包'}
                          description={finished
                              ? '如果日志里提示失败，请按原因处理后重新提交；若只是收尾较慢，可稍后再打开日志确认。'
                              : '拉取与打包在后台执行，完成后这里会出现「下载 tar」按钮，过程中可打开日志查看进度。'}/>
        }
        return <Alert style={{marginTop: 16}} type='success' showIcon
                      message={`tar 已生成${task.size ? `（${formatBytes(task.size)}）` : ''}，可直接下载`}
                      description={<Space direction='vertical' size={6} style={{width: '100%'}}>
                          <Typography.Text>
                              保存位置：
                              <Typography.Text code copyable style={{wordBreak: 'break-all'}}>
                                  {task.path}
                              </Typography.Text>
                          </Typography.Text>
                          <Typography.Text type='secondary'>{this.modeHint(task.mode)}</Typography.Text>
                          <Space>
                              <Button type='primary' size='small' icon={<DownloadOutlined/>}
                                      href={this.downloadUrl()}>
                                  下载 tar
                              </Button>
                              <Popconfirm title='删除这个 tar？' description='删除后需要重新下载镜像才能再次生成。'
                                          onConfirm={this.deleteTar}>
                                  <Button size='small' danger icon={<DeleteOutlined/>}>删除 tar</Button>
                              </Popconfirm>
                          </Space>
                      </Space>}/>
    }

    render() {
        const {hostOptions, logId, logVisible, submitting, task} = this.state
        const extra = []
        if (logId) {
            extra.push(<Button key='log' type='link' size='small' icon={<FileTextOutlined/>}
                               onClick={() => this.setState({logVisible: true})}>
                查看日志
            </Button>)
        }
        if (task?.ready) {
            extra.push(<Button key='download' type='primary' size='small' icon={<DownloadOutlined/>}
                               href={this.downloadUrl()}>
                下载 tar{task.size ? `（${formatBytes(task.size)}）` : ''}
            </Button>)
        }

        return <Page padding>
            <Card title='镜像下载' className='page-card' extra={extra.length ? extra : null}>
                <Form style={{maxWidth: 520}} labelCol={{flex: '80px'}}
                      preserve={false} onFinish={this.submit}>
                    <Form.Item label='镜像地址' name='image'
                               rules={[{required: true, message: '请输入镜像地址'}]}
                               extra='支持公共镜像与已配置凭据的私有仓库，例如 mysql:8、nginx:1.27-alpine'>
                        <Input placeholder='mysql:8'/>
                    </Form.Item>

                    <Form.Item label='执行主机' name='hostId'
                               rules={[{required: true, message: '请选择执行主机'}]}
                               extra='优先由该主机的辅助容器打包，tar 可直接在网页下载；未部署辅助容器时按 SSH 远端 / 平台服务器保存。'>
                        <Select options={hostOptions} showSearch optionFilterProp='label'
                                placeholder='请选择执行主机'/>
                    </Form.Item>

                    <Form.Item>
                        <Perm code='image-download:download'>
                            <Button type='primary' htmlType='submit' loading={submitting}>
                                开始下载
                            </Button>
                        </Perm>
                    </Form.Item>
                </Form>

                {this.renderTask()}
            </Card>

            <Drawer title='下载日志' size={860} open={logVisible}
                    mask={{closable: false}}
                    extra={task?.ready ? (
                        <Button type='primary' size='small' icon={<DownloadOutlined/>}
                                href={this.downloadUrl()}>
                            下载 tar
                        </Button>
                    ) : null}
                    onClose={() => this.setState({logVisible: false})}>
                {/* 抽屉关闭也保持日志连接，任务结束时才能刷新出下载按钮 */}
                {logId
                    ? <LogView key={logId} url={'/admin/ws/task-log/' + logId}
                               websocket={true} onClose={this.onLogClose}/>
                    : null}
            </Drawer>
        </Page>
    }

}
