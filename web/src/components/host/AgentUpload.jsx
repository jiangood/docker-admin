import React from 'react'
import {Alert, Breadcrumb, Button, Empty, message, Modal, Space, Spin, Table, Tooltip, Typography, Upload} from 'antd'
import {
    ArrowUpOutlined,
    FileOutlined,
    FolderOutlined,
    ReloadOutlined,
    UploadOutlined,
} from '@ant-design/icons'
import {getToken, HttpClient} from '@jiangood/open-admin'

/**
 * Agent 面板「上传文件」弹窗：在挂载目录的相对路径视图里逐级点进目标目录后上传。
 * <p>
 * 同名文件 / 目录不允许覆盖：当前目录列表里已有同名条目时前端直接拦截，
 * 后端上传前还会再做一次存在性检查兜底。通过 ref 暴露 {@code open()} 供外层按钮调用。
 */
export default class extends React.Component {

    state = {
        open: false,
        path: '',
        entries: [],
        loading: false,
        error: null,
        uploading: false,
    }

    /** 并发上传计数，全部结束才恢复按钮 loading 状态。 */
    uploadingCount = 0

    open = () => {
        this.setState({open: true, path: '', entries: [], loading: true, error: null})
        this.load('')
    }

    close = () => this.setState({open: false})

    /** path 相对挂载目录，空串表示挂载目录本身。 */
    load = path => {
        const {hostId} = this.props
        this.setState({loading: true, error: null, path})
        HttpClient.get('admin/host/file/list', {hostId, path}, {toastError: false})
            .then(rs => this.setState({entries: rs.data || [], loading: false}))
            .catch(e => this.setState({entries: [], loading: false, error: e.message || '读取目录失败'}))
    }

    refresh = () => this.load(this.state.path)

    /** 当前路径的上一级；已在挂载目录根时返回 null。 */
    parentPath = () => {
        const p = (this.state.path || '').replace(/\/+$/, '')
        if (!p) {
            return null
        }
        const idx = p.lastIndexOf('/')
        return idx <= 0 ? '' : p.substring(0, idx)
    }

    goUp = () => {
        const parent = this.parentPath()
        if (parent !== null) {
            this.load(parent)
        }
    }

    uploadFiles = files => {
        const {hostId} = this.props
        const {path, entries} = this.state
        files.forEach(file => {
            // 预检：当前目录里已有同名条目就不发请求，后端上传前还会再做一次存在性检查兜底
            if (entries.some(e => e.name === file.name)) {
                message.warning(`已存在 ${file.name}，不允许覆盖，请先删除或改名后再上传`)
                return
            }
            const form = new FormData()
            form.append('file', file)
            this.uploadingCount += 1
            this.setState({uploading: true})
            HttpClient.post('admin/host/file/upload', form, {hostId, path})
                .then(() => message.success(`已上传 ${file.name}`))
                .catch(() => {
                })
                .finally(() => {
                    this.uploadingCount -= 1
                    this.setState({uploading: this.uploadingCount > 0})
                    this.refresh()
                })
        })
    }

    breadcrumbItems = () => {
        const {path} = this.state
        const parts = (path || '').split('/').filter(Boolean)
        const items = [{title: <a style={{cursor: 'pointer'}} onClick={() => this.load('')}>挂载目录</a>}]
        let acc = ''
        parts.forEach((p, i) => {
            acc += (acc ? '/' : '') + p
            const target = acc
            items.push({
                title: i === parts.length - 1
                    ? <span>{p}</span>
                    : <a style={{cursor: 'pointer'}} onClick={() => this.load(target)}>{p}</a>,
            })
        })
        return items
    }

    render() {
        const {open, path, entries, loading, error, uploading} = this.state
        const {mountedRoot} = this.props
        const token = getToken()

        const columns = [
            {
                title: '名称', dataIndex: 'name',
                render: (name, record) => {
                    if (record.type !== 'dir') {
                        return <Space size={6}><FileOutlined/>{name}</Space>
                    }
                    const target = path ? path + '/' + record.name : record.name
                    return <a onClick={() => this.load(target)}>
                        <Space size={6}><FolderOutlined style={{color: token.colorWarning}}/>{name}</Space>
                    </a>
                }
            },
            {title: '类型', dataIndex: 'type', width: 90, render: t => t === 'dir' ? '目录' : '文件'},
        ]

        return <Modal title='上传文件到挂载目录' open={open} width={680} footer={null}
                      onCancel={this.close} destroyOnHidden>
            <Typography.Text type='secondary' style={{display: 'block', marginBottom: 8}}>
                宿主机目录 <code>{mountedRoot || '-'}</code>。在这里逐级点进目标目录，再点「上传」；
                目标目录里已有同名文件 / 目录时不允许覆盖。
            </Typography.Text>
            <div className='mb-2'
                 style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                         gap: 'var(--space-4)', flexWrap: 'wrap'}}>
                <Breadcrumb items={this.breadcrumbItems()}/>
                <Space size={8}>
                    <Tooltip title='返回上一级'>
                        <Button size='small' icon={<ArrowUpOutlined/>} disabled={this.parentPath() === null}
                                onClick={this.goUp}>上一级</Button>
                    </Tooltip>
                    <Button size='small' icon={<ReloadOutlined/>} onClick={this.refresh}>刷新</Button>
                    <Upload showUploadList={false} multiple disabled={uploading} beforeUpload={file => {
                        this.uploadFiles([file])
                        return false
                    }}>
                        <Button size='small' type='primary' icon={<UploadOutlined/>}
                                loading={uploading}>上传</Button>
                    </Upload>
                </Space>
            </div>
            {error
                ? <Alert type='error' showIcon title={error}/>
                : <Spin spinning={loading}>
                    <Table size='small' rowKey='name' pagination={false} dataSource={entries}
                           columns={columns}
                           locale={{emptyText: <Empty description='目录为空'/>}}/>
                </Spin>}
        </Modal>
    }
}
