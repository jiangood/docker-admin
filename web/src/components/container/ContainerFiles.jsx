import React from "react";
import {Alert, Breadcrumb, Button, Divider, Empty, Input, message, Modal, Space, Spin, Table, Tooltip, Typography, Upload} from "antd";
import {
    ArrowUpOutlined,
    DownloadOutlined,
    EditOutlined,
    FileAddOutlined,
    FileOutlined,
    FolderOutlined,
    ReloadOutlined,
    UploadOutlined,
} from "@ant-design/icons";
import {getToken, HttpClient, PermUtils} from "@jiangood/open-admin";
import LinkButton from "../LinkButton";
import CodeMirrorEditor from "../CodeMirrorEditor";
import {formatBytes} from "./utils";

/** 与后端 ContainerService.PREVIEW_MAX_BYTES 一致：超过该体积不提供在线编辑，避免截断后保存丢数据。 */
const EDIT_MAX_BYTES = 262144;

/** 常见二进制后缀，命中则不提供在线编辑。 */
const BINARY_EXTENSIONS = new Set([
    'zip', 'tar', 'gz', 'tgz', 'bz2', 'xz', '7z', 'rar', 'jar', 'war', 'ear',
    'class', 'exe', 'dll', 'so', 'dylib', 'bin', 'img', 'iso', 'o', 'a', 'lib', 'pyc', 'wasm',
    'png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp', 'ico', 'tif', 'tiff',
    'mp3', 'mp4', 'avi', 'mov', 'mkv', 'wav', 'flac', 'ogg', 'webm',
    'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx',
    'sqlite', 'db', 'mdb', 'woff', 'woff2', 'ttf', 'otf', 'eot',
]);

/** 取小写后缀，无后缀返回空串。 */
function extensionOf(name) {
    const base = (name || '').toLowerCase();
    const dot = base.lastIndexOf('.');
    return dot > 0 ? base.slice(dot + 1) : '';
}

/** 是否可作为文本在线编辑：非二进制后缀且体积在限制内。 */
function isEditable(record) {
    if (record.type !== 'file') {
        return false;
    }
    if (record.size != null && record.size > EDIT_MAX_BYTES) {
        return false;
    }
    const ext = extensionOf(record.name);
    return !(ext && BINARY_EXTENSIONS.has(ext));
}

/** 按文件名推断 CodeMirror 语法模式；无匹配时用纯文本。 */
function editorMode(name) {
    const base = (name || '').toLowerCase();
    const ext = extensionOf(base);
    if (base === 'dockerfile' || ext === 'dockerfile' || /^dockerfile\./.test(base)) {
        return 'dockerfile';
    }
    const map = {
        yml: 'yaml', yaml: 'yaml',
        toml: 'toml',
        properties: 'properties', ini: 'properties', conf: 'properties', cfg: 'properties',
        env: 'properties', service: 'properties', unit: 'properties', editorconfig: 'properties',
        js: 'javascript', mjs: 'javascript', cjs: 'javascript', jsx: 'javascript',
        json: {name: 'javascript', json: true},
        ts: 'javascript', tsx: 'javascript',
        xml: 'xml', html: 'xml', htm: 'xml', svg: 'xml', plist: 'xml',
        sh: 'shell', bash: 'shell', zsh: 'shell',
        py: 'python',
        sql: 'sql',
        md: 'markdown', markdown: 'markdown',
        nginx: 'nginx',
    };
    return map[ext] || 'text/plain';
}

/** 拼接容器内路径，避免出现重复斜杠。 */
function joinPath(dir, name) {
    const d = dir || '/';
    const n = (name || '').trim();
    return (d.endsWith('/') ? d : d + '/') + n;
}

/**
 * 容器文件浏览：目录导航、文本预览、文件/目录下载、上传文件、在线编辑文本文件（yml / toml 等）。
 * 需要 container:file 权限。
 */
export default class extends React.Component {

    state = {
        path: '/',
        files: [],
        loading: false,
        error: null,
        uploading: false,
        preview: {open: false, name: '', path: '', content: '', loading: false, editable: false},
        edit: {open: false, isNew: false, name: '', path: '', content: '', loading: false, saving: false},
    }

    /** 编辑器当前内容：用普通字段保存，避免每次按键触发整表重渲染。 */
    editContent = ''

    /** 并发上传计数，全部结束才恢复按钮 loading 状态。 */
    uploadingCount = 0

    componentDidMount() {
        if (PermUtils.hasPermission('container:file')) {
            this.load('/')
        }
    }

    load = path => {
        const {hostId, containerId} = this.props
        this.setState({loading: true, error: null, path})
        HttpClient.get('admin/container/files', {hostId, containerId, path}, {toastError: false})
            .then(rs => {
                this.setState({files: rs.data || [], loading: false})
            })
            .catch(e => {
                this.setState({files: [], loading: false, error: e.message || '读取目录失败'})
            })
    }

    enter = record => {
        if (record.type === 'dir') {
            this.load(record.path)
        } else if (record.type === 'file') {
            this.preview(record)
        }
    }

    refresh = () => this.load(this.state.path)

    /** 当前路径的上一级；已在根目录时返回 null。 */
    parentPath = () => {
        const p = (this.state.path || '/').replace(/\/+$/, '')
        if (!p || p === '/') {
            return null
        }
        const idx = p.lastIndexOf('/')
        return idx <= 0 ? '/' : p.substring(0, idx)
    }

    goUp = () => {
        const parent = this.parentPath()
        if (parent) {
            this.load(parent)
        }
    }

    preview = record => {
        const {hostId, containerId} = this.props
        const editable = isEditable(record)
        this.setState({preview: {open: true, name: record.name, path: record.path, content: '', loading: true, editable}})
        HttpClient.get('admin/container/preview', {hostId, containerId, path: record.path}, {toastError: false})
            .then(rs => this.setState(state => ({
                preview: {...state.preview, content: rs.data || '', loading: false}
            })))
            .catch(e => this.setState(state => ({
                preview: {...state.preview, content: '读取失败：' + (e.message || ''), loading: false}
            })))
    }

    closePreview = () => this.setState({preview: {open: false, name: '', path: '', content: '', loading: false, editable: false}})

    /** 从预览弹窗进入编辑。 */
    editFromPreview = () => {
        const {name, path} = this.state.preview
        this.closePreview()
        this.openEditor({name, path})
    }

    download = record => {
        const {hostId, containerId} = this.props
        const isDir = record.type === 'dir'
        const hide = message.loading('下载中…', 0)
        HttpClient.download({
            url: isDir ? 'admin/container/downloadDir' : 'admin/container/download',
            params: {hostId, containerId, path: record.path},
            fileName: isDir ? record.name + '.tar' : record.name,
        }).catch(() => {
        }).finally(() => hide())
    }

    uploadFiles = files => {
        const {hostId, containerId} = this.props
        const {path} = this.state
        files.forEach(file => {
            const form = new FormData()
            form.append('file', file)
            this.uploadingCount += 1
            this.setState({uploading: true})
            HttpClient.post('admin/container/upload', form, {hostId, containerId, path})
                .then(() => this.refresh())
                .catch(() => {})
                .finally(() => {
                    this.uploadingCount -= 1
                    this.setState({uploading: this.uploadingCount > 0})
                })
        })
    }

    /** 打开编辑器：record 为空表示在当前目录新建文件。 */
    openEditor = record => {
        const {hostId, containerId} = this.props
        if (!record) {
            this.editContent = ''
            this.setState({
                edit: {open: true, isNew: true, name: '', path: '', content: '', loading: false, saving: false}
            })
            return
        }
        this.editContent = ''
        this.setState({
            edit: {open: true, isNew: false, name: record.name, path: record.path, content: '', loading: true, saving: false}
        })
        HttpClient.get('admin/container/preview', {hostId, containerId, path: record.path}, {toastError: false})
            .then(rs => {
                this.editContent = rs.data || ''
                this.setState(state => ({edit: {...state.edit, content: rs.data || '', loading: false}}))
            })
            .catch(e => {
                message.error(e.message || '读取文件失败')
                this.closeEditor()
            })
    }

    closeEditor = () => this.setState(state => ({
        edit: {...state.edit, open: false, isNew: false, name: '', path: '', content: '', loading: false, saving: false}
    }))

    save = () => {
        const {hostId, containerId} = this.props
        const {edit, path} = this.state
        const name = (edit.name || '').trim()
        if (!name) {
            message.warning('请输入文件名')
            return
        }
        const target = edit.isNew ? joinPath(path, name) : edit.path
        const content = this.editContent == null ? '' : this.editContent
        this.setState(state => ({edit: {...state.edit, saving: true}}))
        HttpClient.post('admin/container/write', {hostId, containerId, path: target, content}, null, {toastError: false})
            .then(rs => {
                message.success(rs.message || '保存成功')
                this.closeEditor()
                this.refresh()
            })
            .catch(e => message.error(e.message || '保存失败'))
            .finally(() => this.setState(state => ({edit: {...state.edit, saving: false}})))
    }

    breadcrumbItems = () => {
        const {path} = this.state
        const parts = (path || '/').split('/').filter(Boolean)
        const items = [{title: <a style={{cursor: 'pointer'}} onClick={() => this.load('/')}>/</a>}]
        let acc = ''
        parts.forEach((p, i) => {
            acc += '/' + p
            const target = acc
            items.push({
                title: i === parts.length - 1
                    ? <span>{p}</span>
                    : <a style={{cursor: 'pointer'}} onClick={() => this.load(target)}>{p}</a>
            })
        })
        return items
    }

    render() {
        const {files, loading, error, preview, edit, uploading} = this.state
        const token = getToken()

        if (!PermUtils.hasPermission('container:file')) {
            return <Alert type='warning' showIcon title='缺少 container:file 权限，无法浏览容器文件'/>
        }

        const columns = [
            {
                title: '名称', dataIndex: 'name',
                render: (name, record) => {
                    const icon = record.type === 'dir'
                        ? <FolderOutlined style={{color: token.colorWarning}}/>
                        : record.type === 'link'
                            ? <FileOutlined style={{color: token.colorPrimary}}/>
                            : <FileOutlined/>
                    return <LinkButton onClick={() => this.enter(record)}>
                        <Space size={6}>{icon}{name}</Space>
                    </LinkButton>
                }
            },
            {
                title: '类型', dataIndex: 'type', width: 90,
                render: t => {
                    const map = {dir: '目录', file: '文件', link: '链接', other: '其他'}
                    return map[t] || t
                }
            },
            {title: '大小', dataIndex: 'size', width: 110, render: v => formatBytes(v)},
            {title: '权限', dataIndex: 'mode', width: 130},
            {title: '修改时间', dataIndex: 'mtime', width: 170},
            {
                title: '操作', width: 190,
                render: (_, record) => <Space>
                    {record.type === 'file' &&
                        <Button size='small' type='link' onClick={() => this.preview(record)}>预览</Button>}
                    {record.type === 'file' && (
                        isEditable(record)
                            ? <Button size='small' type='link' icon={<EditOutlined/>}
                                      onClick={() => this.openEditor(record)}>编辑</Button>
                            : <Tooltip title='二进制或超过 256KB 的文件不支持在线编辑，请下载后修改'>
                                <Button size='small' type='link' icon={<EditOutlined/>} disabled>编辑</Button>
                            </Tooltip>
                    )}
                    {record.type !== 'link' &&
                        <Button size='small' type='link' icon={<DownloadOutlined/>}
                                onClick={() => this.download(record)}>下载</Button>}
                </Space>
            },
        ]

        return <>
            <div className='mb-3'
                 style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                         gap: 'var(--space-4)', flexWrap: 'wrap'}}>
                <Breadcrumb items={this.breadcrumbItems()}/>
                <Space size={12} split={<Divider type='vertical' style={{margin: 0}}/>}>
                    <Space size={8}>
                        <Tooltip title='返回上一级'>
                            <Button size='small' icon={<ArrowUpOutlined/>} disabled={!this.parentPath()}
                                    onClick={this.goUp}>上一级</Button>
                        </Tooltip>
                        <Button size='small' icon={<ReloadOutlined/>} onClick={this.refresh}>刷新</Button>
                    </Space>
                    <Space size={8}>
                        <Upload showUploadList={false} multiple beforeUpload={file => {
                            this.uploadFiles([file])
                            return false
                        }}>
                            <Button size='small' icon={<UploadOutlined/>} loading={uploading}>上传</Button>
                        </Upload>
                        <Button size='small' icon={<FileAddOutlined/>} onClick={() => this.openEditor(null)}>新建文件</Button>
                    </Space>
                </Space>
            </div>

            {error ? <Alert type='error' showIcon title={error}/> :
                <Spin spinning={loading}>
                    <Table size='small' rowKey='path' pagination={false} dataSource={files}
                           locale={{emptyText: <Empty description='目录为空'/>}}
                           columns={columns}/>
                </Spin>}

            <Modal open={preview.open} title={preview.name} width={800}
                   footer={preview.editable ? [
                       <Button key='close' onClick={this.closePreview}>关闭</Button>,
                       <Button key='edit' type='primary' icon={<EditOutlined/>}
                               onClick={this.editFromPreview}>编辑</Button>,
                   ] : null}
                   onCancel={this.closePreview}>
                <Spin spinning={preview.loading}>
                    <Typography>
                        <pre className='code-block'
                             style={{
                                 maxHeight: 520, background: token.colorFillTertiary,
                                 padding: 'var(--space-3)', borderRadius: 'var(--radius-sm)',
                             }}>{preview.content}</pre>
                    </Typography>
                </Spin>
            </Modal>

            <Modal open={edit.open} width={900} destroyOnHidden
                   title={edit.isNew ? '新建文件' : '编辑文件'}
                   confirmLoading={edit.saving}
                   okText='保存'
                   onOk={this.save}
                   onCancel={this.closeEditor}>
                {edit.isNew && <Input className='mb-3' placeholder='文件名，如 app.yml（可带相对子目录 conf/app.yml）'
                                      value={edit.name}
                                      onChange={e => this.setState(state => ({edit: {...state.edit, name: e.target.value}}))}/>}
                {!edit.isNew && <Typography.Text type='secondary' className='mb-2'
                                                 style={{display: 'block'}}>{edit.path}</Typography.Text>}
                <Alert type='warning' showIcon className='mb-3'
                       title='保存会直接覆盖容器内文件，部分服务需重启后才会生效'/>
                <Spin spinning={edit.loading}>
                    {edit.loading
                        ? <div style={{height: 480}}/>
                        : <CodeMirrorEditor key={edit.path || 'new'} mode={editorMode(edit.name)}
                                            value={edit.content} height={480}
                                            onChange={v => {
                                                this.editContent = v
                                            }}
                                            placeholder={'# 在此编辑文件内容'}/>}
                </Spin>
            </Modal>
        </>
    }
}
