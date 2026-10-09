import React from "react";
import {Alert, Breadcrumb, Button, Empty, message, Modal, Space, Spin, Table, Tag, Typography} from "antd";
import {DownloadOutlined, FileOutlined, FolderOutlined, ReloadOutlined} from "@ant-design/icons";
import {HttpClient, PermUtils} from "@jiangood/open-admin";
import {formatBytes} from "./utils";

/**
 * 容器文件浏览：目录导航、文本预览、文件/目录下载。
 * 需要 container:file 权限。
 */
export default class extends React.Component {

    state = {
        path: '/',
        files: [],
        loading: false,
        error: null,
        preview: {open: false, name: '', content: '', loading: false},
    }

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

    preview = record => {
        const {hostId, containerId} = this.props
        this.setState({preview: {open: true, name: record.name, content: '', loading: true}})
        HttpClient.get('admin/container/preview', {hostId, containerId, path: record.path}, {toastError: false})
            .then(rs => this.setState({preview: {open: true, name: record.name, content: rs.data || '', loading: false}}))
            .catch(e => this.setState({
                preview: {open: true, name: record.name, content: '读取失败：' + (e.message || ''), loading: false}
            }))
    }

    download = record => {
        const {hostId, containerId} = this.props
        const isDir = record.type === 'dir'
        const hide = message.loading('下载中...', 0)
        HttpClient.download({
            url: isDir ? 'admin/container/downloadDir' : 'admin/container/download',
            params: {hostId, containerId, path: record.path},
            fileName: isDir ? record.name + '.tar' : record.name,
        }).catch(() => {
        }).finally(() => hide())
    }

    breadcrumbItems = () => {
        const {path} = this.state
        const parts = (path || '/').split('/').filter(Boolean)
        const items = [{title: <a onClick={() => this.load('/')}>/</a>}]
        let acc = ''
        parts.forEach((p, i) => {
            acc += '/' + p
            const target = acc
            items.push({
                title: i === parts.length - 1
                    ? <span>{p}</span>
                    : <a onClick={() => this.load(target)}>{p}</a>
            })
        })
        return items
    }

    render() {
        const {files, loading, error, preview, path} = this.state

        if (!PermUtils.hasPermission('container:file')) {
            return <Alert type='warning' showIcon title='缺少 container:file 权限，无法浏览容器文件'/>
        }

        const columns = [
            {
                title: '名称', dataIndex: 'name',
                render: (name, record) => {
                    const icon = record.type === 'dir'
                        ? <FolderOutlined style={{color: '#faad14'}}/>
                        : record.type === 'link'
                            ? <FileOutlined style={{color: '#1677ff'}}/>
                            : <FileOutlined/>
                    return <a onClick={() => this.enter(record)} style={{marginLeft: 0}}>
                        <Space size={6}>{icon}{name}</Space>
                    </a>
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
                title: '操作', width: 150,
                render: (_, record) => <Space>
                    {record.type === 'file' &&
                        <Button size='small' type='link' onClick={() => this.preview(record)}>预览</Button>}
                    {record.type !== 'link' &&
                        <Button size='small' type='link' icon={<DownloadOutlined/>}
                                onClick={() => this.download(record)}>下载</Button>}
                </Space>
            },
        ]

        return <>
            <Space style={{marginBottom: 12}} wrap>
                <Breadcrumb items={this.breadcrumbItems()}/>
                <Button size='small' icon={<ReloadOutlined/>} onClick={this.refresh}>刷新</Button>
                <span style={{color: '#999'}}>路径：{path}</span>
            </Space>

            {error ? <Alert type='error' showIcon title={error}/> :
                <Spin spinning={loading}>
                    <Table size='small' rowKey='path' pagination={false} dataSource={files}
                           locale={{emptyText: <Empty description='目录为空'/>}}
                           columns={columns}/>
                </Spin>}

            <Modal open={preview.open} title={preview.name} width={800} footer={null}
                   onCancel={() => this.setState({preview: {open: false, name: '', content: '', loading: false}})}>
                <Spin spinning={preview.loading}>
                    <Typography>
                        <pre style={{
                            margin: 0, maxHeight: 520, overflow: 'auto', whiteSpace: 'pre-wrap',
                            wordBreak: 'break-all', background: '#f6f6f6', padding: 12, borderRadius: 4,
                        }}>{preview.content}</pre>
                    </Typography>
                </Spin>
            </Modal>
        </>
    }
}
