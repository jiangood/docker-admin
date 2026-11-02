import React from 'react'
import {Button, Upload, message} from 'antd'
import {DownloadOutlined, UploadOutlined} from '@ant-design/icons'
import {HttpClient} from '@jiangood/open-admin'

/**
 * 文件上传 / 下载按钮，放在 Agent 状态条右上角。
 * <p>
 * 页面不再展示文件树：接口里的 path 相对于辅助容器当前挂载的目录，
 * 空字符串表示挂载目录本身，所以上传写入挂载目录、下载把整个挂载目录打成 tar；
 * 查看、编辑、删除、重命名、新建目录等操作统一走终端。
 */
export default class extends React.Component {

    state = {
        uploading: false,
    }

    uploadingCount = 0

    /** 当前挂载目录的宿主机绝对路径，只用于展示与下载文件名。 */
    root = () => {
        const {status} = this.props
        const root = (status && (status.mountedRoot || status.desiredRoot)) || '/home'
        const trimmed = root.replace(/\/+$/, '')
        return trimmed || '/'
    }

    deployed = () => !!(this.props.status && this.props.status.deployed)

    uploadFiles = files => {
        const {hostId} = this.props
        const path = this.root()
        files.forEach(file => {
            const form = new FormData()
            form.append('file', file)
            this.uploadingCount += 1
            this.setState({uploading: true})
            // path 为空表示挂载目录本身
            HttpClient.post('admin/host/file/upload', form, {hostId, path: ''}, {toastError: false})
                .then(() => message.success(`${file.name} 已上传到 ${path}`))
                .catch(e => message.error(e.message || `${file.name} 上传失败`))
                .finally(() => {
                    this.uploadingCount -= 1
                    this.setState({uploading: this.uploadingCount > 0})
                })
        })
    }

    download = () => {
        const {hostId} = this.props
        const path = this.root()
        const name = path.split('/').filter(Boolean).pop() || 'root'
        const hide = message.loading(`正在打包 ${path}…`, 0)
        HttpClient.download({
            url: 'admin/host/file/downloadDir',
            params: {hostId, path: ''},
            fileName: `${name}.tar`,
        }).catch(e => message.error(e.message || '下载失败'))
            .finally(() => hide())
    }

    render() {
        const {uploading} = this.state
        const disabled = !this.deployed()
        const root = this.root()

        return <>
            <Upload showUploadList={false} multiple
                    beforeUpload={file => {
                        this.uploadFiles([file])
                        return false
                    }}>
                <Button size='small' icon={<UploadOutlined/>} loading={uploading}
                        disabled={disabled} title={`上传到 ${root}`}>上传</Button>
            </Upload>
            <Button size='small' icon={<DownloadOutlined/>} disabled={disabled}
                    title={`下载 ${root}（tar）`} onClick={this.download}>下载</Button>
        </>
    }
}
