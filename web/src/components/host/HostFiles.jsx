import React from 'react'
import {
    Alert,
    Button,
    Empty,
    Modal,
    Popconfirm,
    Space,
    Tooltip,
    Tree,
    Typography,
    Upload,
    message
} from 'antd'
import {
    DeleteOutlined,
    DownloadOutlined,
    EditOutlined,
    EyeOutlined,
    FileOutlined,
    FolderOutlined,
    ReloadOutlined,
    UploadOutlined
} from '@ant-design/icons'
import {getToken, HttpClient, PermUtils} from '@jiangood/open-admin'
import CodeMirrorEditor from '../CodeMirrorEditor'
import {editorMode, isEditable} from '../fileUtils'
import {formatBytes} from '../container/utils'
import {parentRelative} from './utils'

const ROOT_KEY = '__root__'

const CLOSED_PREVIEW = {open: false, node: null, content: '', loading: false, editable: false}
const CLOSED_EDIT = {open: false, node: null, content: '', loading: false, saving: false}

/** 在树数据中替换某个节点的 children。 */
function updateTree(nodes, key, children) {
    return nodes.map(node => {
        if (node.key === key) {
            return {...node, children}
        }
        if (node.children) {
            return {...node, children: updateTree(node.children, key, children)}
        }
        return node
    })
}

/**
 * 右侧文件树：只提供查看、上传、编辑、删除。
 * <p>
 * 重命名、下载、新建目录等操作交给左侧命令行（agent 内置 docker / compose 和 BusyBox），
 * 文件树保持简单。目录按需加载；根目录是辅助容器当前挂载的宿主机目录，
 * 需要看别的目录时用右上角的「切换目录」（会重建容器，文件与终端一起切换）。
 */
export default class extends React.Component {

    state = {
        root: '',
        deployed: false,
        loaded: false,
        treeData: [],
        treeKey: 0,
        expandedKeys: [],
        selectedKeys: [],
        selected: null,
        uploading: false,
        preview: CLOSED_PREVIEW,
        edit: CLOSED_EDIT,
        deleting: false,
    }

    editContent = ''
    uploadingCount = 0

    componentDidMount() {
        this.applyStatus(this.props.status)
    }

    componentDidUpdate(prevProps) {
        if (prevProps.status !== this.props.status) {
            this.applyStatus(this.props.status)
        }
    }

    applyStatus = status => {
        const root = (status && (status.mountedRoot || status.desiredRoot)) || '/home'
        const deployed = !!(status && status.deployed)
        if (!deployed) {
            this.setState({
                root, deployed, loaded: false, treeData: [], selected: null,
                selectedKeys: [], preview: CLOSED_PREVIEW, edit: CLOSED_EDIT
            })
            return
        }
        // 目录变化或首次可用时重建树；单纯刷新 agent 状态不打断当前展开/选中
        if (this.state.root !== root || !this.state.loaded) {
            this.setState(state => ({
                root,
                deployed,
                loaded: true,
                selected: null,
                selectedKeys: [],
                expandedKeys: [ROOT_KEY],
                treeData: [this.rootNode(root)],
                treeKey: state.treeKey + 1,
                preview: CLOSED_PREVIEW,
                edit: CLOSED_EDIT,
            }))
        } else {
            this.setState({root, deployed})
        }
    }

    rootNode = root => ({
        key: ROOT_KEY,
        title: root,
        name: root,
        path: '',
        type: 'dir',
        isLeaf: false,
    })

    toNode = file => ({
        key: file.path,
        title: file.name,
        name: file.name,
        path: file.path,
        type: file.type,
        size: file.size,
        mode: file.mode,
        mtime: file.mtime,
        linkTarget: file.linkTarget,
        isLeaf: file.type !== 'dir',
    })

    fetchChildren = (key, path) => {
        const {hostId} = this.props
        return HttpClient.get('admin/host/file/list', {hostId, path: path || ''}, {toastError: false})
            .then(rs => (rs.data || []).map(this.toNode))
            .catch(e => {
                message.error(e.message || '读取目录失败')
                return []
            })
    }

    loadData = node => {
        if (node.type !== 'dir' || node.isLeaf) {
            return Promise.resolve()
        }
        return this.fetchChildren(node.key, node.path).then(children => {
            this.setState(state => ({treeData: updateTree(state.treeData, node.key, children)}))
        })
    }

    reloadNode = (key, path) => {
        this.fetchChildren(key, path).then(children => {
            this.setState(state => ({
                treeData: updateTree(state.treeData, key, children),
                expandedKeys: state.expandedKeys.includes(key)
                    ? state.expandedKeys
                    : [...state.expandedKeys, key],
            }))
        })
    }

    onSelect = (keys, info) => this.setState({selectedKeys: keys, selected: info.node})

    onExpand = keys => this.setState({expandedKeys: keys})

    refresh = () => this.setState(state => ({
        selected: null,
        selectedKeys: [],
        expandedKeys: [ROOT_KEY],
        treeData: [this.rootNode(state.root)],
        treeKey: state.treeKey + 1,
        preview: CLOSED_PREVIEW,
        edit: CLOSED_EDIT,
    }))

    fullPath = path => {
        const root = (this.state.root || '/home').replace(/\/+$/, '')
        return path ? `${root}/${path}` : root
    }

    /** 上传目标：选中的目录；选中文件时用其父目录；未选中时用根目录。 */
    uploadTarget = () => {
        const {selected} = this.state
        if (!selected) {
            return ''
        }
        return selected.type === 'dir' ? selected.path : parentRelative(selected.path)
    }

    uploadFiles = files => {
        const {hostId} = this.props
        const target = this.uploadTarget()
        files.forEach(file => {
            const form = new FormData()
            form.append('file', file)
            this.uploadingCount += 1
            this.setState({uploading: true})
            HttpClient.post('admin/host/file/upload', form, {hostId, path: target})
                .then(() => this.reloadNode(target || ROOT_KEY, target))
                .catch(() => {
                })
                .finally(() => {
                    this.uploadingCount -= 1
                    this.setState({uploading: this.uploadingCount > 0})
                })
        })
    }

    openPreview = node => {
        if (!node || node.type !== 'file') {
            return
        }
        const {hostId} = this.props
        this.setState({
            preview: {open: true, node, content: '', loading: true, editable: isEditable(node)}
        })
        HttpClient.get('admin/host/file/preview', {hostId, path: node.path}, {toastError: false})
            .then(rs => this.setState(state => ({
                preview: {...state.preview, content: rs.data || '', loading: false}
            })))
            .catch(e => this.setState(state => ({
                preview: {...state.preview, content: '读取失败：' + (e.message || ''), loading: false}
            })))
    }

    closePreview = () => this.setState({preview: CLOSED_PREVIEW})

    editFromPreview = () => {
        const node = this.state.preview.node
        this.closePreview()
        this.openEditor(node)
    }

    openEditor = node => {
        if (!node || node.type !== 'file' || !isEditable(node)) {
            return
        }
        const {hostId} = this.props
        this.editContent = ''
        this.setState({
            edit: {open: true, node, content: '', loading: true, saving: false}
        })
        HttpClient.get('admin/host/file/preview', {hostId, path: node.path}, {toastError: false})
            .then(rs => {
                this.editContent = rs.data || ''
                this.setState(state => ({
                    edit: {...state.edit, content: rs.data || '', loading: false}
                }))
            })
            .catch(e => {
                message.error(e.message || '读取文件失败')
                this.closeEditor()
            })
    }

    closeEditor = () => this.setState({edit: CLOSED_EDIT})

    save = () => {
        const {hostId} = this.props
        const {edit} = this.state
        if (!edit.node) {
            return
        }
        const content = this.editContent == null ? '' : this.editContent
        this.setState(state => ({edit: {...state.edit, saving: true}}))
        HttpClient.post('admin/host/file/write', {
            hostId,
            path: edit.node.path,
            content
        }, null, {toastError: false})
            .then(rs => {
                message.success((rs && rs.message) || '保存成功')
                const parent = parentRelative(edit.node.path)
                this.closeEditor()
                this.reloadNode(parent || ROOT_KEY, parent)
            })
            .catch(e => message.error(e.message || '保存失败'))
            .finally(() => this.setState(state => ({edit: {...state.edit, saving: false}})))
    }

    download = node => {
        if (!node || node.key === ROOT_KEY || node.type === 'link') {
            return
        }
        const {hostId} = this.props
        const isDir = node.type === 'dir'
        const hide = message.loading('下载中…', 0)
        HttpClient.download({
            url: isDir ? 'admin/host/file/downloadDir' : 'admin/host/file/download',
            params: {hostId, path: node.path},
            fileName: isDir ? node.name + '.tar' : node.name,
        }).catch(() => {
        }).finally(() => hide())
    }

    remove = node => {
        if (!node || node.key === ROOT_KEY) {
            return
        }
        const {hostId} = this.props
        this.setState({deleting: true})
        return HttpClient.post('admin/host/file/delete', null, {hostId, path: node.path}, {toastError: false})
            .then(rs => {
                message.success((rs && rs.message) || '删除成功')
                const parent = parentRelative(node.path)
                this.setState({selected: null, selectedKeys: [], preview: CLOSED_PREVIEW, edit: CLOSED_EDIT})
                this.reloadNode(parent || ROOT_KEY, parent)
            })
            .catch(e => message.error(e.message || '删除失败'))
            .finally(() => this.setState({deleting: false}))
    }

    titleRender = node => {
        const token = getToken()
        const isRoot = node.key === ROOT_KEY
        const icon = node.type === 'dir'
            ? <FolderOutlined style={{color: token.colorWarning}}/>
            : <FileOutlined/>
        const size = !isRoot && node.type !== 'dir' && node.size != null
            ? <Typography.Text type='secondary' style={{fontSize: 11}}>{formatBytes(node.size)}</Typography.Text>
            : null
        const link = node.type === 'link' && node.linkTarget
            ? <Typography.Text type='secondary' style={{fontSize: 11}}>→ {node.linkTarget}</Typography.Text>
            : null
        return <Space size={6}>
            {icon}
            <span>{isRoot ? node.title : node.name}</span>
            {size}
            {link}
        </Space>
    }

    render() {
        const {
            root, deployed, treeData, treeKey, expandedKeys, selectedKeys,
            selected, uploading, preview, edit, deleting
        } = this.state
        const token = getToken()
        const height = this.props.height || 640

        if (!PermUtils.hasPermission('host:file')) {
            return <Alert type='warning' showIcon title='缺少 host:file 权限，无法管理主机文件'/>
        }

        if (!deployed) {
            return <div style={{height, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
                <Empty description='辅助容器未部署，请先在右上角部署'/>
            </div>
        }

        const canView = !!selected && selected.type === 'file'
        const canDownload = !!selected && selected.key !== ROOT_KEY && selected.type !== 'link'
        const canEdit = !!selected && selected.type === 'file' && isEditable(selected)
        const canDelete = !!selected && selected.key !== ROOT_KEY
        const uploadTarget = this.uploadTarget()

        return <div style={{height, display: 'flex', flexDirection: 'column'}}>
            <div className='mb-2'
                 style={{display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap'}}>
                <Typography.Text type='secondary' ellipsis style={{flex: 1, minWidth: 120}}>
                    {selected ? `已选：${this.fullPath(selected.path)}` : `根目录：${root}`}
                </Typography.Text>
                <Space size={4} wrap>
                    <Button size='small' icon={<EyeOutlined/>} disabled={!canView}
                            onClick={() => this.openPreview(selected)}>查看</Button>
                    <Button size='small' icon={<DownloadOutlined/>} disabled={!canDownload}
                            onClick={() => this.download(selected)}>下载</Button>
                    <Upload showUploadList={false} multiple beforeUpload={file => {
                        this.uploadFiles([file])
                        return false
                    }}>
                        <Button size='small' icon={<UploadOutlined/>} loading={uploading}
                                title={`上传到 ${this.fullPath(uploadTarget)}`}>上传</Button>
                    </Upload>
                    {canEdit
                        ? <Button size='small' icon={<EditOutlined/>}
                                  onClick={() => this.openEditor(selected)}>编辑</Button>
                        : <Tooltip title='二进制或超过 256KB 的文件不支持在线编辑，请用左侧命令行处理'>
                            <span><Button size='small' icon={<EditOutlined/>} disabled>编辑</Button></span>
                        </Tooltip>}
                    <Popconfirm title={`确定删除 ${selected ? this.fullPath(selected.path) : ''}？删除后不可恢复`}
                                disabled={!canDelete}
                                okText='删除' cancelText='取消'
                                okButtonProps={{danger: true, loading: deleting}}
                                onConfirm={() => this.remove(selected)}>
                        <Button size='small' danger icon={<DeleteOutlined/>} disabled={!canDelete}
                                loading={deleting}>删除</Button>
                    </Popconfirm>
                    <Button size='small' icon={<ReloadOutlined/>} onClick={this.refresh}>刷新</Button>
                </Space>
            </div>

            <div style={{
                flex: 1,
                minHeight: 0,
                overflow: 'auto',
                border: `1px solid ${token.colorBorderSecondary}`,
                borderRadius: token.borderRadius,
                padding: 8,
            }}>
                <Tree key={treeKey}
                      blockNode
                      showLine
                      treeData={treeData}
                      loadData={this.loadData}
                      expandedKeys={expandedKeys}
                      onExpand={this.onExpand}
                      selectedKeys={selectedKeys}
                      onSelect={this.onSelect}
                      titleRender={this.titleRender}/>
            </div>

            <Modal open={preview.open} title={preview.node ? preview.node.name : '预览'} width={800}
                   footer={preview.editable ? [
                       <Button key='close' onClick={this.closePreview}>关闭</Button>,
                       <Button key='edit' type='primary' icon={<EditOutlined/>}
                               onClick={this.editFromPreview}>编辑</Button>,
                   ] : null}
                   onCancel={this.closePreview}>
                <Typography>
                    <pre className='code-block'
                         style={{
                             maxHeight: 520, background: token.colorFillTertiary,
                             padding: 'var(--space-3)', borderRadius: 'var(--radius-sm)',
                         }}>{preview.content}</pre>
                </Typography>
            </Modal>

            <Modal open={edit.open} width={900} destroyOnHidden
                   title='编辑文件'
                   confirmLoading={edit.saving}
                   okText='保存'
                   onOk={this.save}
                   onCancel={this.closeEditor}>
                {edit.node && <Typography.Text type='secondary' className='mb-2'
                                               style={{display: 'block'}}>{this.fullPath(edit.node.path)}</Typography.Text>}
                <Alert type='warning' showIcon className='mb-3'
                       title='保存会直接覆盖宿主机文件，部分服务需重启后才会生效'/>
                <CodeMirrorEditor key={edit.node ? edit.node.path : 'empty'}
                                  mode={editorMode(edit.node ? edit.node.name : '')}
                                  value={edit.content} height={480}
                                  onChange={v => {
                                      this.editContent = v
                                  }}
                                  placeholder={'# 在此编辑文件内容'}/>
            </Modal>
        </div>
    }
}
