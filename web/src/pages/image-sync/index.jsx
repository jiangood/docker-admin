import {Alert, AutoComplete, Button, Card, Drawer, Form, Segmented, Select, Tag, Tooltip, Typography} from 'antd'
import React from 'react'
import {FileTextOutlined} from '@ant-design/icons'
import {DateUtils, HttpClient, Page, Perm, ProTable} from '@jiangood/open-admin'
import LogView from '../../components/LogView'

/**
 * 最近同步过的镜像记录在浏览器本地，仅用于输入框的自动补全，不落库。
 */
const RECENT_KEY = 'docker-admin:image-sync-history'

/**
 * 最近使用最多保留的条数。
 */
const RECENT_LIMIT = 20

/**
 * 同步类型：
 * <ul>
 *   <li>registry：执行主机拉取后推送到注册中心；</li>
 *   <li>host：各目标主机直接拉取；</li>
 *   <li>relay：执行主机拉取后中转（save/load）给各目标主机。</li>
 * </ul>
 */
const MODE_REGISTRY = 'registry'
const MODE_HOST = 'host'
const MODE_RELAY = 'relay'

/** 同步类型选项：注册中心 / 直连主机 / 经执行主机中转。 */
const MODE_OPTIONS = [
    {label: '同步到注册中心', value: MODE_REGISTRY},
    {label: '同步到主机', value: MODE_HOST},
    {label: '经执行主机中转', value: MODE_RELAY},
]

/**
 * 常用镜像，按语言/用途分组，作为自动补全的候选。
 */
const PRESET_IMAGES = [
    {
        group: 'Java',
        images: [
            'eclipse-temurin:21-jre', 'eclipse-temurin:17-jre', 'eclipse-temurin:21-jdk', 'eclipse-temurin:17-jdk',
            'amazoncorretto:21', 'amazoncorretto:17',
            'maven:3.9-eclipse-temurin-21', 'gradle:8-jdk21',
        ],
    },
    {
        group: 'Node',
        images: ['node:22-slim', 'node:20-slim', 'node:18-slim', 'node:lts-slim', 'node:22'],
    },
    {
        group: 'Python',
        images: ['python:3.13-slim', 'python:3.12-slim', 'python:3.11-slim', 'python:3.10-slim'],
    },
    {
        group: '常用',
        images: ['nginx:1.27-alpine', 'redis:7-alpine', 'mysql:8.4', 'alpine:3.20'],
    },
]

/**
 * 读取最近使用的镜像，解析失败时按空处理。
 */
const loadRecent = () => {
    try {
        const list = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]')
        return Array.isArray(list) ? list : []
    } catch (e) {
        return []
    }
}

/**
 * 记录一次成功同步的镜像：去重后放到最前，超出上限丢弃最旧的。
 */
const pushRecent = image => {
    const list = [image, ...loadRecent().filter(v => v !== image)].slice(0, RECENT_LIMIT)
    try {
        localStorage.setItem(RECENT_KEY, JSON.stringify(list))
    } catch (e) {
        // 本地存储不可用（如隐私模式）时静默忽略，不影响同步本身
    }
    return list
}

/**
 * 同步记录的状态：success 为空表示仍在进行中。
 */
const statusTagOf = row => {
    if (row.success === true) {
        return <Tag color='success'>成功</Tag>
    }
    if (row.success === false) {
        return <Tag color='error'>失败</Tag>
    }
    return <Tag color='processing'>同步中</Tag>
}

/**
 * 同步类型标签：注册中心 / 主机（直连）/ 主机（中转）。
 */
const typeTagOf = syncType => {
    if (syncType === MODE_HOST) {
        return <Tag color='blue'>主机</Tag>
    }
    if (syncType === MODE_RELAY) {
        return <Tag color='cyan'>主机中转</Tag>
    }
    return <Tag>注册中心</Tag>
}

/**
 * 镜像同步，三种类型：
 * <ul>
 *   <li>同步到注册中心：执行主机拉取后 push 到平台注册中心，并登记镜像；</li>
 *   <li>同步到主机：目标主机直接 pull 源镜像，不经过注册中心；</li>
 *   <li>经执行主机中转：执行主机 pull 后 save/load 到目标主机，不经过注册中心。</li>
 * </ul>
 * 一个表单，用「同步类型」切换字段。
 */
export default class extends React.Component {

    syncTable = React.createRef()

    state = {
        mode: MODE_REGISTRY,
        registry: null,
        registryOptions: [],
        hostOptions: [],
        registryPreview: '',
        recentImages: [],
        // 当前日志面板展示的日志
        logId: null,
        // 本次正在运行的同步日志：只有它关闭才代表同步结束
        runningLogId: null,
        logVisible: false,
        syncing: false,
    }

    syncColumns = [
        {title: '开始时间', dataIndex: 'createTime', width: 170},
        {title: '类型', dataIndex: 'syncType', width: 90, render: typeTagOf},
        {title: '源镜像', dataIndex: 'sourceImage', ellipsis: true},
        {title: '目标镜像', dataIndex: 'targetImage', ellipsis: true, render: v => v || '-'},
        {title: '执行主机', dataIndex: 'hostName', width: 140, render: v => v || '-'},
        {
            title: '目标主机', dataIndex: 'targetHostName', ellipsis: true,
            render: v => v || '-',
        },
        {
            title: '状态', dataIndex: 'success', width: 100,
            render: (_, row) => row.errorMessage
                ? <Tooltip title={row.errorMessage}>{statusTagOf(row)}</Tooltip>
                : statusTagOf(row),
        },
        {
            title: '耗时', dataIndex: 'timeSpend', width: 100,
            render: v => v != null ? DateUtils.friendlyTotalTime(v) : '-',
        },
        {
            title: '操作', key: 'option', width: 100,
            render: (_, row) => row.logId
                ? <Button type='link' size='small' onClick={() => this.openLog(row.logId)}>查看日志</Button>
                : '-',
        },
    ]

    componentDidMount() {
        this.loadRegistry()
        this.loadHosts()
        this.setState({recentImages: loadRecent()})
    }

    loadRegistry = () => {
        Promise.all([HttpClient.get('admin/registry/info'), HttpClient.get('admin/registry/list')])
            .then(([current, list]) => this.setState({
                registry: current.data || null,
                registryOptions: list.data || [],
            }))
            .catch(() => {})
    }

    loadHosts = () => {
        HttpClient.get('admin/host/options')
            .then(rs => this.setState({hostOptions: rs.data || []}))
            .catch(() => {
            })
    }

    /**
     * 自动补全候选项：最近使用 + 常用镜像，附上分组作为提示。
     */
    imageOptions = () => {
        const label = (value, group) => (
            <span className='gap-4' style={{display: 'flex', justifyContent: 'space-between'}}>
                <span>{value}</span>
                <Typography.Text type='secondary'>{group}</Typography.Text>
            </span>
        )
        const recent = this.state.recentImages
        return [
            ...recent.map(v => ({value: v, label: label(v, '最近使用')})),
            ...PRESET_IMAGES.flatMap(({group, images}) => images
                .filter(v => !recent.includes(v))
                .map(v => ({value: v, label: label(v, group)}))),
        ]
    }

    /**
     * 目标镜像名：取源镜像去掉 tag 后的最后一段。
     */
    targetNameOf = sourceImage => {
        const src = (sourceImage || '').trim()
        if (!src) {
            return ''
        }
        let repo = src
        const lastSlash = src.lastIndexOf('/')
        const lastColon = src.lastIndexOf(':')
        if (lastColon > lastSlash) {
            repo = src.slice(0, lastColon)
        }
        return repo.split('/').pop()
    }

    /**
     * 源镜像的 tag，未写时默认 latest。
     */
    tagOf = sourceImage => {
        const src = (sourceImage || '').trim()
        const lastSlash = src.lastIndexOf('/')
        const lastColon = src.lastIndexOf(':')
        return lastColon > lastSlash ? src.slice(lastColon + 1) : 'latest'
    }

    /**
     * 目标镜像预览：镜像在平台注册中心上的完整地址（与后端默认命名规则保持一致）。
     * 只有「同步到注册中心」需要。
     */
    calcRegistryPreview = values => {
        if (this.state.mode !== MODE_REGISTRY) {
            this.setState({registryPreview: ''})
            return
        }
        const {registryOptions} = this.state
        const registry = registryOptions.find(r => r.id === values.targetRegistryId) || this.state.registry
        this.setState({registry})
        const name = this.targetNameOf(values.sourceImage)
        if (!name || !registry || !registry.url) {
            this.setState({registryPreview: ''})
            return
        }
        const base = registry.url + (registry.namespace ? '/' + registry.namespace : '')
        this.setState({registryPreview: base + '/' + name + ':' + this.tagOf(values.sourceImage)})
    }

    /**
     * 切换同步类型：字段随类型增删，目标镜像预览也跟着重算。
     */
    changeMode = mode => {
        this.setState({mode}, () => this.calcRegistryPreview(this.lastValues || {}))
    }

    submit = values => {
        const {mode} = this.state
        const payload = {
            syncType: mode,
            sourceImage: values.sourceImage,
            // 直连主机不需要执行主机；其余两种都要
            hostId: mode === MODE_HOST ? null : values.hostId,
            targetRegistryId: mode === MODE_REGISTRY ? values.targetRegistryId : null,
            targetHostId: mode === MODE_REGISTRY ? null : values.targetHostId,
        }
        this.setState({syncing: true})
        HttpClient.post('admin/image-sync/sync', payload).then(rs => {
            this.setState({
                recentImages: pushRecent(values.sourceImage.trim()),
                logId: rs.data,
                runningLogId: rs.data,
                logVisible: true,
            })
            // 同步开始时后端已落库一条「同步中」记录，立刻刷新列表
            this.reloadRecords()
        }).catch(() => {
            // 请求未成功（参数校验、无权限等）不会产生日志，直接恢复按钮
            this.setState({syncing: false})
        })
    }

    reloadRecords = () => {
        this.syncTable.current?.reload()
    }

    /**
     * 日志流关闭即表示后台同步已结束，恢复「开始同步」按钮并刷新记录。
     * 只有正在运行的这次同步关闭才恢复按钮——回看历史记录时关闭不影响进行中的同步。
     */
    onSyncFinished = logId => {
        if (logId === this.state.runningLogId) {
            this.setState({syncing: false, runningLogId: null})
        }
        this.reloadRecords()
    }

    /**
     * 打开某条同步记录的日志（历史记录或正在运行的同步）。
     */
    openLog = logId => {
        if (logId) {
            this.setState({logId, logVisible: true})
        }
    }

    render() {
        const {mode, registry, registryOptions, hostOptions, registryPreview, logId, logVisible, syncing} = this.state
        const registryConfigured = registryOptions.length > 0
        const isRegistry = mode === MODE_REGISTRY
        const isRelay = mode === MODE_RELAY
        const needExecHost = mode !== MODE_HOST
        const needTargets = mode !== MODE_REGISTRY
        const formProps = {labelCol: {flex: '100px'}, preserve: false}

        return <Page padding>
            <Card title='镜像同步' className='page-card'
                  extra={logId ? (
                      <Button type='link' size='small' icon={<FileTextOutlined/>}
                              onClick={() => this.openLog(logId)}>
                          查看同步日志
                      </Button>
                  ) : null}>
                {isRegistry && !registryConfigured && (
                    <Alert
                        type='warning'
                        showIcon
                        className='mb-4'
                        title='尚未配置镜像注册中心，请先前往【设置 - 镜像注册中心】完成配置'
                    />
                )}

                <Form {...formProps}
                      disabled={logVisible}
                      onValuesChange={(_, values) => {
                          this.lastValues = values
                          this.calcRegistryPreview(values)
                      }}
                      onFinish={this.submit}>

                    <Form.Item label='同步类型'
                               tooltip='同步到注册中心：推送到平台注册中心；同步到主机：目标主机直接拉取；经执行主机中转：执行主机拉取后导入到目标主机（目标主机无需访问镜像仓库）'>
                        <Segmented value={mode} options={MODE_OPTIONS}
                                   onChange={this.changeMode}/>
                    </Form.Item>

                    <Form.Item label='源镜像' name='sourceImage' rules={[{required: true, message: '请输入源镜像'}]}
                               tooltip='支持完整地址，如 nginx:1.25-alpine、docker.io/library/redis:7；私有镜像会按地址自动匹配已配置的注册中心凭据，无需另行选择。可从下拉列表选择常用镜像或最近同步过的镜像'>
                        <AutoComplete options={this.imageOptions()} placeholder='nginx:1.25-alpine'
                                      filterOption={(input, option) => option.value.toLowerCase().includes(input.toLowerCase())}/>
                    </Form.Item>

                    {isRegistry && (
                        <Form.Item label='目标注册中心' name='targetRegistryId'
                                   tooltip='默认使用全局设置中的注册中心；可在此为本次同步单独指定目标'>
                            <Select allowClear showSearch optionFilterProp='label'
                                    options={registryOptions.map(r => ({value: r.id, label: r.url + '/' + r.namespace}))}
                                    placeholder='使用系统默认注册中心'/>
                        </Form.Item>
                    )}

                    {needExecHost && (
                        <Form.Item label={isRelay ? '中转主机' : '执行主机'} name='hostId'
                                   rules={[{required: true, message: isRelay ? '请选择中转主机' : '请选择执行主机'}]}
                                   tooltip={isRelay
                                       ? '网络通畅的主机：负责拉取源镜像并打包，再导入到目标主机'
                                       : '网络通畅、能访问公共镜像仓库的主机，负责拉取源镜像并推送到注册中心'}>
                            <Select options={hostOptions} showSearch optionFilterProp='label'
                                    placeholder={isRelay ? '请选择中转主机' : '请选择执行主机'}/>
                        </Form.Item>
                    )}

                    {isRegistry && registryPreview &&
                        <Form.Item label='目标镜像' tooltip='镜像在平台注册中心上的完整地址，由源镜像自动生成'>
                            <Typography.Text copyable code>{registryPreview}</Typography.Text>
                        </Form.Item>
                    }

                    {needTargets && (
                        <Form.Item label='目标主机' name='targetHostId'
                                   dependencies={['hostId']}
                                   rules={[
                                       {required: true, message: '请选择目标主机'},
                                       ({getFieldValue}) => ({
                                           validator: (_, value) => {
                                               const hostId = getFieldValue('hostId')
                                               if (isRelay && hostId && value === hostId) {
                                                   return Promise.reject(new Error('目标主机不能与中转主机相同'))
                                               }
                                               return Promise.resolve()
                                           },
                                       }),
                                   ]}
                                   tooltip={isRelay
                                       ? '镜像由中转主机导入（save/load），该主机无需能访问镜像仓库'
                                       : '该主机会直接拉取源镜像'}>
                            <Select options={hostOptions} showSearch optionFilterProp='label'
                                    allowClear placeholder='请选择目标主机'/>
                        </Form.Item>
                    )}

                    <Form.Item>
                        <Perm code='image-sync:sync'>
                            <Button type='primary' htmlType='submit'
                                    loading={syncing} disabled={isRegistry && !registryConfigured}>
                                开始同步
                            </Button>
                        </Perm>
                    </Form.Item>
                </Form>
            </Card>

            <Card title='同步记录' className='page-card mt-4'>
                <ProTable actionRef={this.syncTable}
                          defaultPageSize={10}
                          request={params => HttpClient.get('admin/image-sync/page', params)}
                          columns={this.syncColumns}/>
            </Card>

            <Drawer title='同步日志' size={860} open={logVisible}
                    mask={{closable: false}}
                    onClose={() => this.setState({logVisible: false})}>
                {logId
                    ? <LogView key={logId} url={'/admin/ws/sync-log/' + logId} websocket={true}
                               onClose={() => this.onSyncFinished(logId)}/>
                    : null}
            </Drawer>
        </Page>
    }
}
