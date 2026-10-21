import {Col, Form, Input, Row, Select, Spin} from "antd";
import React, {useEffect, useRef, useState} from "react";
import EditTable from "../../components/EditTable";
import {HttpClient} from "@jiangood/open-admin";

/**
 * 容器配置（复用组件）：端口 / 卷 / 环境变量 / 启动命令 / 网络 / extraHosts / 设备请求。
 *
 * 直接绑定父 Form 中以 namePrefix 为前缀的字段，放在父 Form 内使用：
 *   <ContainerConfigForm namePrefix={['config']} imageUrl={...} imageTag={...}/>
 *
 * 传 appId 时读取 admin/app/configMeta（详情页用）；
 * 否则按 imageUrl + imageTag 读取 admin/app/configMetaByImage（新建应用 / docker run 用）。
 * 两处返回的只是镜像声明的端口/卷，用于在配置里还没有对应字段时预填默认值，
 * 端口与文件映射始终可自由增删改。
 *
 * 传 twoColumn 时网络模式单独一行，启动命令 / extraHosts 排在最后（两列），表格仍整行；
 * 不传则维持单列纵向，详情页保持原样。
 */

const NETWORK_OPTIONS = [
    {label: '桥接模式', value: 'bridge'},
    {label: '主机模式', value: 'host'},
    {label: '无需网络', value: 'none'},
]

const portsColumns = [
    {title: '主机端口', dataIndex: 'publicPort', dataType: 'InputNumber', width: 160},
    {title: '容器端口', dataIndex: 'privatePort', dataType: 'InputNumber', width: 160},
    {title: '协议', dataIndex: 'protocol', dataType: 'Select', valueEnum: {TCP: 'TCP', UDP: 'UDP'}, width: 120},
]

const bindsColumns = [
    {title: '主机路径', dataIndex: 'publicVolume', dataType: 'Input'},
    {title: '容器路径', dataIndex: 'privateVolume', dataType: 'Input'},
]

const envColumns = [
    {title: '变量名', dataIndex: 'name', dataType: 'Input'},
    {title: '变量值', dataIndex: 'value', dataType: 'Input'},
]

const deviceColumns = [
    {
        title: 'driver', dataIndex: 'driver', dataType: 'Select',
        valueEnum: {nvidia: 'nvidia', amd: 'amd'},
    },
    {
        title: 'count', dataIndex: 'count', dataType: 'Select',
        options: [
            {label: '全部(all)', value: -1},
            {label: '1', value: 1},
            {label: '2', value: 2},
            {label: '3', value: 3},
            {label: '4', value: 4},
        ],
    },
    {
        title: 'capabilities', dataIndex: 'capabilities', dataType: 'Select', mode: 'multiple',
        valueEnum: {gpu: 'gpu', compute: 'compute', utility: 'utility', graphics: 'graphics', video: 'video'},
        format: v => Array.isArray(v) ? v.flat() : v,
        parse: v => (v && v.length) ? [v] : [],
    },
]

/**
 * 卷的主机路径默认根目录：最终路径为 DEFAULT_DATA_ROOT/应用名/容器路径。
 */
const DEFAULT_DATA_ROOT = '/docker-app-data'

/**
 * 卷的默认主机路径：/docker-app-data/<应用名>/<容器路径>。
 * 应用名为空时不生成默认值（返回空串）。
 */
function defaultHostPath(appName, privateVolume) {
    if (!appName) return ''
    const sub = String(privateVolume || '').replace(/^\/+/, '')
    return sub ? `${DEFAULT_DATA_ROOT}/${appName}/${sub}` : `${DEFAULT_DATA_ROOT}/${appName}`
}

/**
 * 归一化配置：
 * 网络模式 / 环境变量 / 设备请求补默认值；
 * 端口 / 卷以配置为准，镜像声明只在配置里还没有该字段时作为默认值预填
 * （新建应用选择镜像），之后完全由用户编辑：改哪行、删哪行、加几行都不会被声明覆盖。
 */
function normalize(meta, cfg, appName) {
    const next = {...(cfg || {})}
    next.networkMode = next.networkMode || 'bridge'
    next.envs = next.envs || []
    next.deviceRequests = next.deviceRequests || []

    // 字段已存在（含清空后的空数组、docker run 解析结果）就不再覆盖：
    // 删掉的端口/卷不会被镜像声明重新加回来
    if (next.ports == null && meta?.ports?.length) {
        next.ports = meta.ports.map(p => ({
            publicPort: p.privatePort,
            privatePort: p.privatePort,
            protocol: p.protocol,
        }))
    }

    if (next.binds == null && meta?.volumes?.length) {
        next.binds = meta.volumes.map(v => ({
            publicVolume: defaultHostPath(appName, v.privateVolume),
            privateVolume: v.privateVolume,
        }))
    }

    return next
}

export default function ContainerConfigForm({namePrefix, imageUrl, imageTag, appId, appName, twoColumn}) {
    const form = Form.useFormInstance()
    const [meta, setMeta] = useState(null)
    const [loading, setLoading] = useState(true)
    const appliedKey = useRef(null)
    // 上次归一化时使用的应用名，用于应用名后填/变更时更新默认卷主机路径
    const lastAppNameRef = useRef(null)

    // 应用名：详情页由 prop 传入；新增应用弹窗读取同表单的根字段 name
    const watchedName = Form.useWatch('name', form)
    const effectiveAppName = appName ?? watchedName

    const cfg = Form.useWatch(namePrefix, form) || {}

    useEffect(() => {
        if (appId) {
            setLoading(true)
            HttpClient.get('admin/app/configMeta', {id: appId})
                .then(rs => setMeta(rs.data))
                .catch(() => setMeta(null))
                .finally(() => setLoading(false))
            return
        }
        if (!imageUrl) {
            setMeta(null)
            setLoading(false)
            return
        }
        setLoading(true)
        HttpClient.get('admin/app/configMetaByImage', {imageUrl, imageTag})
            .then(rs => setMeta(rs.data))
            .catch(() => setMeta(null))
            .finally(() => setLoading(false))
    }, [appId, imageUrl, imageTag])

    // meta 变化后按声明预填默认值（同一镜像只处理一次，避免覆盖用户后续编辑）
    useEffect(() => {
        if (loading) return
        const key = appId || `${imageUrl || ''}:${imageTag || ''}`
        if (appliedKey.current === key) return
        appliedKey.current = key
        lastAppNameRef.current = effectiveAppName || null
        const current = form.getFieldValue(namePrefix)
        const next = normalize(meta, current, effectiveAppName)
        if (JSON.stringify(next) !== JSON.stringify(current || {})) {
            form.setFieldValue(namePrefix, next)
        }
    }, [loading, meta])

    // 应用名后填/变更：把「空白」或「仍是上次自动默认」的卷主机路径更新为新默认，
    // 用户手改过的值不动。
    useEffect(() => {
        if (loading || !effectiveAppName) return
        const prev = lastAppNameRef.current
        if (prev === effectiveAppName) return
        lastAppNameRef.current = effectiveAppName
        const binds = (form.getFieldValue(namePrefix) || {}).binds
        if (!binds || !binds.length) return
        let changed = false
        const nextBinds = binds.map(b => {
            if (!b || !b.privateVolume) return b
            const oldDefault = prev ? defaultHostPath(prev, b.privateVolume) : ''
            if (!b.publicVolume || (oldDefault && b.publicVolume === oldDefault)) {
                changed = true
                return {...b, publicVolume: defaultHostPath(effectiveAppName, b.privateVolume)}
            }
            return b
        })
        if (changed) {
            form.setFieldValue([...namePrefix, 'binds'], nextBinds)
        }
    }, [effectiveAppName, loading])

    if (loading) {
        return <div className='center-box'><Spin/></div>
    }

    const name = f => [...namePrefix, f]
    // 镜像声明只用于预填默认值，表格始终可增删改
    const hasDeclaredPorts = !!(meta && meta.ports && meta.ports.length)
    const hasDeclaredVolumes = !!(meta && meta.volumes && meta.volumes.length)
    // 弹窗（twoColumn）里收紧行距，减少多个配置块堆叠的纵向占用；详情页保持默认
    const itemStyle = twoColumn ? {marginBottom: 8} : undefined

    const networkOptions = (!cfg.networkMode || NETWORK_OPTIONS.some(o => o.value === cfg.networkMode))
        ? NETWORK_OPTIONS
        : [...NETWORK_OPTIONS, {label: cfg.networkMode, value: cfg.networkMode}]

    const networkItem = (
        <Form.Item label='网络模式' name={name('networkMode')} style={itemStyle}>
            <Select style={{width: 200}} options={networkOptions}/>
        </Form.Item>
    )

    const cmdItem = (
        <Form.Item label='启动命令' name={name('cmd')} style={itemStyle}>
            <Input/>
        </Form.Item>
    )

    const extraHostsItem = (
        <Form.Item label='extraHosts' name={name('extraHosts')} style={itemStyle}
                   tooltip='域名IP映射,类似dns,hosts文件'>
            <Input placeholder='域名:IP 域名2:IP2'/>
        </Form.Item>
    )

    const portsItem = (!cfg.networkMode || cfg.networkMode === 'bridge') && (
        <Form.Item label='端口映射' name={name('ports')} style={itemStyle}
                   tooltip={hasDeclaredPorts
                       ? '已按镜像声明的端口预填，可自由增删改；主机端口留空表示不映射'
                       : '按需添加「主机端口 → 容器端口」映射，主机端口留空表示不映射'}>
            <EditTable columns={portsColumns}
                       defaultRow={{protocol: 'TCP'}} extra='暂无端口'/>
        </Form.Item>
    )

    const bindsItem = (
        <Form.Item label='文件映射' name={name('binds')} style={itemStyle}
                   tooltip={hasDeclaredVolumes
                       ? `已按镜像声明的卷预填主机路径（默认 ${DEFAULT_DATA_ROOT}/应用名/...），可自由增删改`
                       : '按需添加「主机路径 → 容器路径」映射'}>
            <EditTable columns={bindsColumns} extra='暂无卷'/>
        </Form.Item>
    )

    const envsItem = (
        <Form.Item label='环境变量' tooltip='每行一个环境变量' name={name('envs')} style={itemStyle}>
            <EditTable columns={envColumns}
                       defaultRow={{name: '', value: ''}}
                       extra='暂无环境变量'/>
        </Form.Item>
    )

    const deviceItem = (
        <Form.Item label='设备请求' name={name('deviceRequests')} style={itemStyle}
                   tooltip='GPU 等设备透传，对应 docker run --gpus；需目标主机已安装 nvidia-container-toolkit'>
            <EditTable columns={deviceColumns}
                       defaultRow={{driver: 'nvidia', count: -1, capabilities: [['gpu']]}}
                       extra='未配置设备请求'/>
        </Form.Item>
    )

    if (twoColumn) {
        return <>
            <Row gutter={16}>
                <Col span={12}>{networkItem}</Col>
            </Row>
            {portsItem}
            {bindsItem}
            {envsItem}
            {deviceItem}
            <Row gutter={16}>
                <Col span={12}>{cmdItem}</Col>
                <Col span={12}>{extraHostsItem}</Col>
            </Row>
        </>
    }

    return <>
        {networkItem}
        {portsItem}
        {bindsItem}
        {envsItem}
        {cmdItem}
        {extraHostsItem}
        {deviceItem}
    </>
}
