import {Alert, Button, Form, Input, message, Modal, Select, Space, Typography, Upload} from 'antd'
import {InboxOutlined} from '@ant-design/icons'
import React from 'react'
import {HttpClient} from '@jiangood/open-admin'
import LogView from './LogView'
import {formatBytes} from './container/utils'

/**
 * 导入状态轮询间隔（毫秒）：只在导入任务进行中时轮询。
 */
const POLL_INTERVAL = 2000

/**
 * 导入 tar 到注册中心（镜像列表页）。
 * <p>
 * 流程：上传 tar → 平台所在主机 docker load → 打上注册中心前缀并 push → 登记镜像与版本。
 * 上传完成后后台任务才真正开始，因此这里先展示实时日志，再轮询最终结果。
 * 镜像名与版本可留空，默认沿用 tar 内的 RepoTags。
 */
export default class ImageImportModal extends React.Component {

    formRef = React.createRef()
    pollTimer = null
    notified = false

    state = {
        open: false,
        // 上传中：禁止关闭，避免用户以为可以中断
        starting: false,
        file: null,
        logId: null,
        // 后端返回的 {status, message, images}
        task: null,
        registryOptions: [],
    }

    show = () => {
        this.stopPoll()
        this.notified = false
        this.setState({open: true, starting: false, file: null, logId: null, task: null})
        HttpClient.get('admin/registry/options').then(rs => {
            this.setState({registryOptions: rs.data || []})
        }).catch(() => {
        })
    }

    componentWillUnmount() {
        this.stopPoll()
    }

    cancel = () => {
        this.stopPoll()
        this.setState({open: false})
    }

    submit = () => {
        const {file} = this.state
        if (!file) {
            message.error('请选择要导入的 tar 包')
            return
        }
        this.formRef.current.validateFields().then(values => {
            const form = new FormData()
            form.append('file', file)
            this.setState({starting: true})
            HttpClient.post('admin/image/import/start', form, {
                registryId: values.registryId,
                imageName: values.imageName,
                tag: values.tag,
            }).then(rs => {
                this.setState({starting: false, logId: rs.data, task: null})
                this.poll(rs.data)
            }).catch(() => this.setState({starting: false}))
        }).catch(() => {
            // 表单校验失败时保持弹窗，不开始上传
        })
    }

    // ------------------------------------------------------------------ 状态

    poll = logId => {
        this.stopPoll()
        HttpClient.get('admin/image/import/status', {logId}, {toastError: false}).then(rs => {
            const task = rs.data
            if (!task) {
                this.startPoll(logId)
                return
            }
            this.setState({task})
            if (task.status === 'RUNNING') {
                this.startPoll(logId)
            } else if (task.status === 'SUCCESS' && !this.notified) {
                // 成功后刷新镜像列表；只通知一次，避免轮询重复刷新
                this.notified = true
                this.props.onSuccess?.()
            }
        }).catch(() => this.startPoll(logId))
    }

    startPoll = logId => {
        if (this.pollTimer) {
            return
        }
        this.pollTimer = setInterval(() => this.poll(logId), POLL_INTERVAL)
    }

    stopPoll = () => {
        if (this.pollTimer) {
            clearInterval(this.pollTimer)
            this.pollTimer = null
        }
    }

    /** 日志流关闭：任务已结束（或连接断开），补一次状态查询把结果落到界面。 */
    onLogClose = () => {
        if (this.state.logId) {
            this.poll(this.state.logId)
        }
    }

    /** 失败后重试：保留已选文件，重新上传并派发任务。 */
    retry = () => {
        this.stopPoll()
        this.notified = false
        this.setState({logId: null, task: null, starting: false})
    }

    // ------------------------------------------------------------------ 渲染

    footer = () => {
        const {starting, logId, task} = this.state
        if (!logId) {
            return [
                <Button key='cancel' disabled={starting} onClick={this.cancel}>取消</Button>,
                <Button key='submit' type='primary' loading={starting} onClick={this.submit}>开始导入</Button>,
            ]
        }
        if (task?.status === 'SUCCESS') {
            return [<Button key='done' type='primary' onClick={this.cancel}>完成</Button>]
        }
        if (task?.status === 'FAILED') {
            return [
                <Button key='close' onClick={this.cancel}>关闭</Button>,
                <Button key='retry' type='primary' onClick={this.retry}>重新导入</Button>,
            ]
        }
        return [<Button key='close' onClick={this.cancel}>关闭</Button>]
    }

    renderForm = () => {
        const {file, registryOptions} = this.state
        return <Form ref={this.formRef} layout='vertical' preserve={false}>
            <Form.Item label='镜像归档' required>
                <Upload.Dragger accept='.tar' maxCount={1} showUploadList={false}
                                beforeUpload={f => {
                                    this.setState({file: f})
                                    return false
                                }}>
                    <p className='ant-upload-drag-icon'><InboxOutlined/></p>
                    <p className='ant-upload-text'>点击或拖拽 tar 到此处</p>
                    <p className='ant-upload-hint'>仅支持 docker save 生成的 .tar 归档</p>
                </Upload.Dragger>
                {file
                    ? <Typography.Text type='secondary' style={{display: 'block', marginTop: 8}}>
                        已选择：{file.name}（{formatBytes(file.size)}）
                    </Typography.Text>
                    : null}
            </Form.Item>

            <Form.Item label='目标注册中心' name='registryId' tooltip='留空使用系统默认注册中心'>
                <Select allowClear showSearch optionFilterProp='label' options={registryOptions}
                        placeholder='使用系统默认注册中心'/>
            </Form.Item>

            <Form.Item label='镜像名' name='imageName'
                       tooltip='留空沿用 tar 内的镜像名；指定时归档里只能有一个镜像'>
                <Input allowClear placeholder='留空则用 tar 内的镜像名，如 myapp'/>
            </Form.Item>

            <Form.Item label='版本' name='tag'
                       tooltip='留空沿用 tar 内的 tag；归档中有多个镜像时不能统一指定'>
                <Input allowClear placeholder='留空则用 tar 内的版本，如 1.0.0'/>
            </Form.Item>

            <Typography.Text type='secondary' style={{fontSize: 12}}>
                不填镜像名与版本时，tar 内每个带标签的镜像都会按「注册中心/命名空间/镜像名:版本」导入。
            </Typography.Text>
        </Form>
    }

    renderLog = () => {
        const {logId, task} = this.state
        return <Space direction='vertical' size='middle' style={{width: '100%'}}>
            {task?.status === 'SUCCESS'
                ? <Alert type='success' showIcon message={task.message}
                         description={task.images?.length ? task.images.join('、') : null}/>
                : task?.status === 'FAILED'
                    ? <Alert type='error' showIcon message='导入失败' description={task.message}/>
                    : <Alert type='info' showIcon message='正在载入归档并推送到注册中心，进度见下方日志'/>}
            <LogView key={logId}
                     url={'/admin/ws/image-import-log/' + logId}
                     websocket={true}
                     height={420}
                     onClose={this.onLogClose}/>
        </Space>
    }

    render() {
        const {open, starting, logId} = this.state
        return <Modal open={open}
                      width={720}
                      title='导入 tar 到注册中心'
                      onCancel={this.cancel}
                      mask={{closable: false}}
                      closable={!starting}
                      keyboard={!starting}
                      destroyOnHidden
                      footer={this.footer()}>
            {logId ? this.renderLog() : this.renderForm()}
        </Modal>
    }

}
