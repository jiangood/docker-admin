import {Form, Input, Select, Spin} from "antd";
import React, {useEffect, useRef, useState} from "react";
import EditTable from "../../components/EditTable";
import {HttpClient} from "@jiangood/open-admin";

/**
 * 容器配置（复用组件）：端口 / 卷 / 环境变量 / 启动命令 / 网络 / extraHosts / 设备请求。
 *
 * 直接绑定父 Form 中以 namePrefix 为前缀的字段，放在父 Form 内使用：
 *   <ContainerConfigForm namePrefix={['config']} imageUrl={...} imageTag={...}/>
 *
 * 传 appId 时读取 admin/app/configMeta（合并已保存映射，详情页用）；
 * 否则按 imageUrl + imageTag 读取 admin/app/configMetaByImage（新建应用 / docker run 用）。
 */

const NETWORK_OPTIONS = [
    {label: '桥接模式', value: 'bridge'},
    {label: '主机模式', value: 'host'},
    {label: '无需网络', value: 'none'},
]

const portsColumns = (strict) => strict ? [
    {title: '主机端口', dataIndex: 'publicPort', dataType: 'InputNumber'},
    {title: '容器端口', dataIndex: 'privatePort', readonly: true},
    {title: '协议', dataIndex: 'protocol', readonly: true},
] : [
    {title: '主机端口', dataIndex: 'publicPort', dataType: 'InputNumber'},
    {title: '容器端口', dataIndex: 'privatePort', dataType: 'InputNumber'},
    {title: '协议', dataIndex: 'protocol', dataType: 'Select', valueEnum: {TCP: 'TCP', UDP: 'UDP'}},
]

const bindsColumns = (strict) => strict ? [
    {title: '主机路径', dataIndex: 'publicVolume', dataType: 'Input'},
    {title: '容器路径', dataIndex: 'privateVolume', readonly: true},
] : [
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
 * 按镜像声明（meta）归一化配置：
 * 严格维度（镜像声明了端口/卷）按声明重建容器侧，仅保留已填的主机侧映射；
 * 非严格维度保留用户填写/解析得到的值。
 */
function normalize(meta, cfg) {
    const next = {...(cfg || {})}
    next.networkMode = next.networkMode || 'bridge'
    next.envs = next.envs || []
    next.deviceRequests = next.deviceRequests || []

    if (meta && meta.strictPorts) {
        const saved = cfg?.ports || []
        next.ports = (meta.ports || []).map(p => {
            const hit = saved.find(x => x.privatePort === p.privatePort
                && (x.protocol || 'TCP').toUpperCase() === p.protocol)
            return {
                publicPort: hit?.publicPort ?? p.publicPort ?? null,
                privatePort: p.privatePort,
                protocol: p.protocol,
            }
        })
    } else {
        next.ports = cfg?.ports || []
    }

    if (meta && meta.strictVolumes) {
        const saved = cfg?.binds || []
        next.binds = (meta.volumes || []).map(v => {
            const hit = saved.find(x => x.privateVolume === v.privateVolume)
            return {
                publicVolume: hit?.publicVolume ?? v.publicVolume ?? '',
                privateVolume: v.privateVolume,
                readOnly: hit?.readOnly ?? v.readOnly ?? false,
            }
        })
    } else {
        next.binds = cfg?.binds || []
    }

    return next
}

export default function ContainerConfigForm({namePrefix, imageUrl, imageTag, appId}) {
    const form = Form.useFormInstance()
    const [meta, setMeta] = useState(null)
    const [loading, setLoading] = useState(true)
    const appliedKey = useRef(null)

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

    // meta 变化后按声明归一化并回写（同一镜像只处理一次，避免覆盖用户后续编辑）
    useEffect(() => {
        if (loading) return
        const key = appId || `${imageUrl || ''}:${imageTag || ''}`
        if (appliedKey.current === key) return
        appliedKey.current = key
        const next = normalize(meta, form.getFieldValue(namePrefix))
        if (JSON.stringify(next) !== JSON.stringify(form.getFieldValue(namePrefix) || {})) {
            form.setFieldValue(namePrefix, next)
        }
    }, [loading, meta])

    if (loading) {
        return <div className='center-box'><Spin/></div>
    }

    const name = f => [...namePrefix, f]
    const strictPorts = !!(meta && meta.strictPorts)
    const strictVolumes = !!(meta && meta.strictVolumes)

    const networkOptions = (!cfg.networkMode || NETWORK_OPTIONS.some(o => o.value === cfg.networkMode))
        ? NETWORK_OPTIONS
        : [...NETWORK_OPTIONS, {label: cfg.networkMode, value: cfg.networkMode}]

    return <>
        <Form.Item label='网络模式' name={name('networkMode')}>
            <Select style={{width: 200}} options={networkOptions}/>
        </Form.Item>

        {(!cfg.networkMode || cfg.networkMode === 'bridge') && (
            <Form.Item label='端口映射' name={name('ports')}
                       tooltip={strictPorts ? '端口来自镜像声明，仅可修改主机端口' : '镜像未声明端口，可自由配置'}>
                <EditTable columns={portsColumns(strictPorts)}
                           canAdd={!strictPorts} canRemove={!strictPorts} extra='暂无端口'/>
            </Form.Item>
        )}

        <Form.Item label='文件映射' name={name('binds')}
                   tooltip={strictVolumes ? '卷来自镜像声明，仅可修改主机路径' : '镜像未声明卷，可自由配置'}>
            <EditTable columns={bindsColumns(strictVolumes)}
                       canAdd={!strictVolumes} canRemove={!strictVolumes} extra='暂无卷'/>
        </Form.Item>

        <Form.Item label='环境变量' tooltip='每行一个环境变量' name={name('envs')}>
            <EditTable columns={envColumns}
                       defaultRow={{name: '', value: ''}}
                       extra='暂无环境变量'/>
        </Form.Item>

        <Form.Item label='启动命令' name={name('cmd')}>
            <Input/>
        </Form.Item>

        <Form.Item label='extraHosts' name={name('extraHosts')} tooltip='域名IP映射,类似dns,hosts文件'>
            <Input placeholder='域名:IP 域名2:IP2'/>
        </Form.Item>

        <Form.Item label='设备请求' name={name('deviceRequests')}
                   tooltip='GPU 等设备透传，对应 docker run --gpus；需目标主机已安装 nvidia-container-toolkit'>
            <EditTable columns={deviceColumns}
                       defaultRow={{driver: 'nvidia', count: -1, capabilities: [['gpu']]}}
                       extra='未配置设备请求'/>
        </Form.Item>
    </>
}
