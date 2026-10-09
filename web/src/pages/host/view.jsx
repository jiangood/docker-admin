import {Button, Card, Descriptions, Empty, Modal, Result, Spin, Table, Tabs, Tag} from 'antd'
import React from 'react'
import {HttpClient, Page, PageUtils} from "@jiangood/open-admin"
import ContainerDetail from "../../components/container/ContainerDetail"
import {formatBytes, formatTime, stateColor, stateLabel} from "../../components/container/utils"

const CONNECTION_LABELS = {local: '本机', tcp: '远程 TCP', ssh: 'SSH', unix: '本机'}

function connectionLabel(type) {
    return CONNECTION_LABELS[type] || CONNECTION_LABELS[(type || '').toLowerCase()] || type || '-'
}

function endpointOf(host) {
    if (!host) {
        return '-'
    }
    const type = host.connectionType
    if (type === 'ssh') {
        return `ssh://${host.sshUser || 'root'}@${host.sshHost}:${host.sshPort || 22}`
    }
    if (type === 'local' || type === 'unix') {
        return host.dockerHost || '本机（默认）'
    }
    return host.dockerHost || '-'
}

/**
 * 主机详情：主机信息 + 垂直 Tab「容器 / 镜像」，点击容器弹窗展示通用容器组件。
 */
export default class extends React.Component {

    state = {
        id: null,
        host: null,
        hostLoading: true,

        info: null,
        infoError: null,

        containers: [],
        containersLoading: false,

        images: [],
        imagesLoading: false,

        detail: {open: false, containerId: null, name: ''},
        activeTab: 'containers',
    }

    componentDidMount() {
        const id = PageUtils.currentParams().id
        this.setState({id})
        if (!id) {
            this.setState({hostLoading: false, infoError: '缺少主机 id'})
            return
        }
        this.loadHost(id)
        this.loadInfo(id)
        this.loadContainers(id)
        this.loadImages(id)
    }

    loadHost = id => {
        HttpClient.get('admin/host/get', {id}, {toastError: false})
            .then(rs => this.setState({host: rs.data, hostLoading: false}))
            .catch(() => this.setState({hostLoading: false}))
    }

    loadInfo = id => {
        HttpClient.get('admin/host/info', {id}, {toastError: false})
            .then(rs => this.setState({info: rs.data, infoError: null}))
            .catch(e => this.setState({info: null, infoError: e.message || '无法连接主机'}))
    }

    loadContainers = id => {
        this.setState({containersLoading: true})
        HttpClient.get('admin/container/list', {hostId: id, all: true}, {toastError: false})
            .then(rs => this.setState({containers: rs.data || []}))
            .catch(() => this.setState({containers: []}))
            .finally(() => this.setState({containersLoading: false}))
    }

    loadImages = id => {
        this.setState({imagesLoading: true})
        HttpClient.get('admin/host/images', {id}, {toastError: false})
            .then(rs => this.setState({images: rs.data || []}))
            .catch(() => this.setState({images: []}))
            .finally(() => this.setState({imagesLoading: false}))
    }

    openContainer = record => {
        this.setState({detail: {open: true, containerId: record.id, name: record.name}})
    }

    closeContainer = () => {
        this.setState({detail: {open: false, containerId: null, name: ''}})
        if (this.state.id) {
            this.loadContainers(this.state.id)
        }
    }

    render() {
        const {host, hostLoading, info, infoError, containers, containersLoading,
            images, imagesLoading, detail, activeTab} = this.state

        if (hostLoading) {
            return <Page padding><Spin/></Page>
        }
        if (!this.state.id) {
            return <Page padding><Result status='warning' title='缺少主机 id'/></Page>
        }

        const containerColumns = [
            {
                title: '名称', dataIndex: 'name',
                render: (name, record) => <a onClick={() => this.openContainer(record)}>{name}</a>
            },
            {title: '镜像', dataIndex: 'image', ellipsis: true},
            {
                title: '状态', dataIndex: 'state', width: 220,
                render: (state, record) => <>
                    <Tag color={stateColor(state)}>{stateLabel(state)}</Tag>
                    <span style={{color: '#999'}}>{record.status}</span>
                </>
            },
            {
                title: '端口', width: 220,
                render: (_, record) => (record.ports || [])
                    .filter(p => p.publicPort)
                    .map(p => `${p.publicPort}→${p.privatePort}/${p.protocol}`)
                    .join(', ') || '-'
            },
            {title: '创建时间', dataIndex: 'created', width: 170, render: v => formatTime(v)},
            {
                title: '操作', width: 90,
                render: (_, record) => <Button size='small' type='link' onClick={() => this.openContainer(record)}>查看</Button>
            },
        ]

        const imageColumns = [
            {
                title: '仓库 / 标签', dataIndex: 'repoTags',
                render: (tags, record) => (tags && tags.length)
                    ? tags.map(t => <div key={t}>{t}</div>)
                    : <Tag>无标签</Tag>
            },
            {title: '镜像ID', dataIndex: 'idShort', width: 160},
            {title: '大小', dataIndex: 'size', width: 120, render: v => formatBytes(v)},
            {title: '创建时间', dataIndex: 'created', width: 170, render: v => formatTime(v)},
            {title: '容器数', dataIndex: 'containers', width: 90, render: v => v ?? '-'},
        ]

        const items = [
            {
                key: 'containers',
                label: `容器 (${containers.length})`,
                children: <Table rowKey='id' size='small' loading={containersLoading}
                                 dataSource={containers} columns={containerColumns}
                                 pagination={{pageSize: 20, showSizeChanger: true}}
                                 locale={{emptyText: <Empty description='暂无容器'/>}}
                                 onRow={record => ({onDoubleClick: () => this.openContainer(record)})}/>
            },
            {
                key: 'images',
                label: `镜像 (${images.length})`,
                children: <Table rowKey='id' size='small' loading={imagesLoading}
                                 dataSource={images} columns={imageColumns}
                                 pagination={{pageSize: 20, showSizeChanger: true}}
                                 locale={{emptyText: <Empty description='暂无镜像'/>}}/>
            },
        ]

        return <Page padding>
            <Card className='mb-2' title={host ? host.name : '主机'}>
                {infoError && <Result status='warning' title='无法连接主机' subTitle={infoError}
                                     style={{padding: 0, marginBottom: 12}}/>}
                <Descriptions size='small' column={2}
                              items={[
                                  {key: 'name', label: '名称', children: host?.name || '-'},
                                  {key: 'conn', label: '连接方式', children: connectionLabel(host?.connectionType)},
                                  {key: 'endpoint', label: '连接地址', children: endpointOf(host), span: 2},
                                  {key: 'version', label: 'Docker 版本', children: info?.dockerVersion || '-'},
                                  {key: 'api', label: 'API 版本', children: info?.apiVersion || '-'},
                                  {key: 'os', label: '操作系统', children: info?.os || '-'},
                                  {key: 'arch', label: '架构', children: info?.arch || '-'},
                                  {
                                      key: 'containers', label: '容器数',
                                      children: info ? `${info.containersRunning || 0} 运行 / ${info.containersTotal || 0} 总计` : '-'
                                  },
                                  {key: 'images', label: '镜像数', children: info ? info.images : '-'},
                                  {key: 'mem', label: '内存', children: info?.memory ? formatBytes(info.memory) : '-'},
                                  {key: 'cpu', label: 'CPU', children: info?.cpus || '-'},
                              ]}/>
            </Card>

            <Card>
                <Tabs activeKey={activeTab} items={items}
                      onChange={key => this.setState({activeTab: key})}/>
            </Card>

            <Modal title={`容器 - ${detail.name}`}
                   open={detail.open}
                   onCancel={this.closeContainer}
                   footer={null}
                   width={1100}
                   destroyOnHidden
                   styles={{body: {minHeight: 520}}}>
                {detail.open && detail.containerId &&
                    <ContainerDetail hostId={this.state.id} containerId={detail.containerId}
                                     onClose={this.closeContainer}/>}
            </Modal>
        </Page>
    }
}
