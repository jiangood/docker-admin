import {Alert, Button, Modal, Space, Typography, Upload, message} from 'antd'
import {InboxOutlined} from '@ant-design/icons'
import React from 'react'
import {HttpClient} from '@jiangood/open-admin'
import {formatBytes} from './container/utils'

/**
 * 导入 tar 镜像到主机（主机详情页）。
 * <p>
 * 与镜像列表页的「导入 tar 到注册中心」不同：归档直接载入该主机的 docker，不 push、也不登记平台镜像。
 * 归档常见几 GB，上传与载入都在同一个请求里完成（远程主机经 SSH 传输），
 * 因此提交期间禁止关闭弹窗，并提示用户不要刷新页面。
 */
export default class HostImageImportModal extends React.Component {

    state = {
        open: false,
        file: null,
        loading: false,
        error: null,
        // 载入成功后 docker 输出的镜像引用；null 表示还没开始或仍在进行
        imported: null,
    }

    show = () => {
        this.setState({open: true, file: null, loading: false, error: null, imported: null})
    }

    close = () => {
        this.setState({open: false})
    }

    submit = () => {
        const {file} = this.state
        if (!file) {
            message.error('请选择要导入的 tar 包')
            return
        }
        const form = new FormData()
        form.append('file', file)
        this.setState({loading: true, error: null, imported: null})
        HttpClient.post('admin/host/loadImage', form, {id: this.props.hostId}, {
            toastSuccess: false,
            toastError: false,
        }).then(rs => {
            this.setState({loading: false, imported: rs.data || []})
            // 载入的镜像会立刻出现在主机镜像列表里，刷新让用户看到结果
            this.props.onSuccess?.()
        }).catch(e => {
            this.setState({loading: false, error: e.message || '导入失败'})
        })
    }

    footer = () => {
        const {loading, error, imported} = this.state
        if (imported !== null) {
            return [<Button key='done' type='primary' onClick={this.close}>完成</Button>]
        }
        if (error) {
            return [
                <Button key='close' onClick={this.close}>关闭</Button>,
                <Button key='retry' type='primary' onClick={() => this.setState({error: null})}>重新导入</Button>,
            ]
        }
        return [
            <Button key='cancel' disabled={loading} onClick={this.close}>取消</Button>,
            <Button key='submit' type='primary' loading={loading} onClick={this.submit}>开始导入</Button>,
        ]
    }

    renderForm = () => {
        const {file, loading, error} = this.state
        return <Space direction='vertical' size='middle' style={{width: '100%'}}>
            <Alert type='info' showIcon
                   message='归档会直接载入该主机的 Docker，不会推送到注册中心'
                   description='仅支持 docker save 生成的 .tar 归档；大文件上传与载入可能需要几分钟。'/>
            {error ? <Alert type='error' showIcon message='导入失败' description={error}/> : null}
            <Upload.Dragger accept='.tar' maxCount={1} showUploadList={false} disabled={loading}
                            beforeUpload={f => {
                                this.setState({file: f})
                                return false
                            }}>
                <p className='ant-upload-drag-icon'><InboxOutlined/></p>
                <p className='ant-upload-text'>点击或拖拽 tar 到此处</p>
                <p className='ant-upload-hint'>仅支持 docker save 生成的 .tar 归档</p>
            </Upload.Dragger>
            {file
                ? <Typography.Text type='secondary'>
                    已选择：{file.name}（{formatBytes(file.size)}）
                </Typography.Text>
                : null}
        </Space>
    }

    renderResult = () => {
        const {imported} = this.state
        return <Space direction='vertical' size='middle' style={{width: '100%'}}>
            <Alert type='success' showIcon
                   message={imported.length ? `已载入 ${imported.length} 个镜像` : '已载入镜像归档'}
                   description={imported.length
                       ? <div style={{wordBreak: 'break-all'}}>{imported.join('、')}</div>
                       : '归档内没有带标签的镜像，可在镜像列表中按 ID 查看。'}/>
        </Space>
    }

    render() {
        const {open, loading, imported} = this.state
        return <Modal open={open}
                      width={640}
                      title='导入 tar 镜像到主机'
                      onCancel={this.close}
                      mask={{closable: false}}
                      closable={!loading}
                      keyboard={!loading}
                      destroyOnHidden
                      footer={this.footer()}>
            {imported !== null ? this.renderResult() : this.renderForm()}
            {loading
                ? <Alert className='mt-3' type='warning' showIcon
                         message='正在上传并载入，请勿关闭页面或刷新浏览器'/>
                : null}
        </Modal>
    }

}
