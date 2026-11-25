import {Alert, Button, Card, Checkbox, Drawer, Form, Input, Popconfirm, Select, Space, Typography} from 'antd'
import {DeleteOutlined, DownloadOutlined, FileTextOutlined} from '@ant-design/icons'
import React from 'react'
import {HttpClient, Page, Perm, UrlUtils} from '@jiangood/open-admin'
import LogView from '../../components/LogView'
import {formatBytes} from '../../components/container/utils'

/**
 * 镜像下载 tar：在指定主机上拉取镜像（支持公共镜像），再用 docker save 打成 tar 包。
 * <p>
 * 无论主机是本机、TCP 还是 SSH，tar 统一保存在平台服务器的 image-tar 目录，
 * 下载时从平台读盘；可选「下载完成后删除」以节省磁盘，超过保留天数的也会自动清理。
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
        deleteAfter: true,
        downloaded: false,
        removed: false,
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
        this.setState({submitting: true, finished: false, task: null, downloaded: false, removed: false})
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
        const suffix = this.state.deleteAfter ? '&deleteAfter=1' : ''
        return UrlUtils.contextPath('/admin/image-download/tar')
            + '?logId=' + encodeURIComponent(this.state.logId) + suffix
    }

    /** 勾了「下载完成后删除」就置为已完成态，避免再次点击落到已删除的文件上。 */
    onDownload = () => {
        if (this.state.deleteAfter) {
            this.setState({downloaded: true})
        }
    }

    deleteTar = () => {
        const {logId} = this.state
        HttpClient.postForm('admin/image-download/delete', {logId}).then(() => {
            this.setState({task: null, downloaded: true, removed: true})
        })
    }

    renderTask = () => {
        const {task, finished, deleteAfter, downloaded, removed} = this.state
        if (!this.state.logId) {
            return null
        }
        if (removed) {
            return <Alert style={{marginTop: 16}} type='success' showIcon
                          message='tar 已删除'
                          description='需要再次下载请重新提交任务。'/>
        }
        if (!task) {
            return <Alert style={{marginTop: 16}} type={finished ? 'warning' : 'info'} showIcon
                          message={finished ? '任务已结束，暂未看到可下载的 tar' : '正在拉取并打包'}
                          description={finished
                              ? '如果日志里提示失败，请按原因处理后重新提交；若只是收尾较慢，可稍后再打开日志确认。'
                              : `拉取与打包在后台执行（docker save 流由平台接收），完成后这里会出现「下载 tar」按钮${deleteAfter ? '；勾选状态下下载完成会自动删除 tar' : ''}。`}/>
        }
        return <Alert style={{marginTop: 16}} type='success' showIcon
                      message={downloaded
                          ? '下载已开始，完成后平台会删除 tar'
                          : `tar 已生成${task.size ? `（${formatBytes(task.size)}）` : ''}，可直接下载`}
                      description={<Space direction='vertical' size={6} style={{width: '100%'}}>
                          <Typography.Text>
                              保存位置：
                              <Typography.Text code copyable style={{wordBreak: 'break-all'}}>
                                  {task.path}
                              </Typography.Text>
                          </Typography.Text>
                          <Typography.Text type='secondary'>
                              tar 保存在平台服务器，下载时从平台读取；不会长期保留，超过系统参数里的保留天数后自动清理。
                          </Typography.Text>
                          <Checkbox checked={deleteAfter} disabled={downloaded}
                                    onChange={e => this.setState({deleteAfter: e.target.checked})}>
                              下载完成后删除 tar（不占平台磁盘）
                          </Checkbox>
                          <Space>
                              <Button type='primary' size='small' icon={<DownloadOutlined/>}
                                      disabled={downloaded}
                                      href={downloaded ? undefined : this.downloadUrl()}
                                      onClick={this.onDownload}>
                                  {downloaded ? '已开始下载' : '下载 tar'}
                              </Button>
                              {downloaded ? null
                                  : <Popconfirm title='删除这个 tar？' description='删除后需要重新下载镜像才能再次生成。'
                                                onConfirm={this.deleteTar}>
                                      <Button size='small' danger icon={<DeleteOutlined/>}>删除 tar</Button>
                                  </Popconfirm>}
                          </Space>
                          {downloaded ? <Typography.Text type='secondary'>
                              下载完成后平台会自动删除 tar；若取消了浏览器下载，文件会保留，超过保留天数后由平台清理。
                              需要再次下载请重新提交任务。
                          </Typography.Text> : null}
                      </Space>}/>
    }

    render() {
        const {hostOptions, logId, logVisible, submitting, task, downloaded} = this.state
        const extra = []
        if (logId) {
            extra.push(<Button key='log' type='link' size='small' icon={<FileTextOutlined/>}
                               onClick={() => this.setState({logVisible: true})}>
                查看日志
            </Button>)
        }
        if (task?.ready && !downloaded) {
            extra.push(<Button key='download' type='primary' size='small' icon={<DownloadOutlined/>}
                               href={this.downloadUrl()} onClick={this.onDownload}>
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
                               extra='拉取与打包在所选主机上执行，tar 统一保存在平台服务器，可在本页直接下载。'>
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
                    extra={task?.ready && !downloaded ? (
                        <Button type='primary' size='small' icon={<DownloadOutlined/>}
                                href={this.downloadUrl()} onClick={this.onDownload}>
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
