import {Alert, AutoComplete, Button, Col, Form, Input, Modal, Row, Select, Typography} from 'antd'
import React from 'react'
import {HttpClient} from '@jiangood/open-admin'
import LogView from './LogView'

/**
 * 最近同步过的镜像记录在浏览器本地，仅用于输入框的自动补全，不落库。
 */
const RECENT_KEY = 'docker-admin:image-sync-history'

/**
 * 最近使用最多保留的条数。
 */
const RECENT_LIMIT = 20

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
 * 同步类型：
 * <ul>
 *   <li>registry：执行主机拉取后推送到注册中心；</li>
 *   <li>host：目标主机直接拉取；</li>
 *   <li>relay：执行主机拉取后中转（save/load）给目标主机。</li>
 * </ul>
 */
export const MODE_REGISTRY = 'registry'
export const MODE_HOST = 'host'
export const MODE_RELAY = 'relay'

/**
 * 每种同步方式的弹窗标题与顶部说明。
 */
const MODE_META = {
    [MODE_REGISTRY]: {
        title: '同步到注册中心',
        tip: '执行主机拉取源镜像，打上注册中心前缀后推送，并登记到镜像列表；镜像名与版本可手动修改。',
    },
    [MODE_HOST]: {
        title: '同步到主机',
        tip: '目标主机直接拉取源镜像，不经过平台注册中心。',
    },
    [MODE_RELAY]: {
        title: '经执行主机中转',
        tip: '中转主机拉取源镜像后打包导入目标主机，目标主机无需能访问镜像仓库。',
    },
}

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
 * 注册中心在镜像地址里的前缀：地址 + 命名空间。
 */
const registryBaseOf = registry => {
    if (!registry) {
        return ''
    }
    if (!registry.url) {
        return registry.namespace || ''
    }
    return registry.url + (registry.namespace ? '/' + registry.namespace : '')
}

/**
 * 目标镜像名：取源镜像去掉 tag 后的最后一段。
 */
const targetNameOf = sourceImage => {
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
const tagOf = sourceImage => {
    const src = (sourceImage || '').trim()
    const lastSlash = src.lastIndexOf('/')
    const lastColon = src.lastIndexOf(':')
    return lastColon > lastSlash ? src.slice(lastColon + 1) : 'latest'
}

/**
 * 发起一次镜像同步（镜像同步页的「增加」弹窗）。
 * <p>
 * 三种同步方式共用一个表单，由打开时的 {@code mode} 决定字段：
 * 注册中心只要执行主机，直连主机只要目标主机，中转两者都要。
 * 提交后弹窗原地切换为实时日志，关闭弹窗不影响后台任务，记录仍在「同步记录」表格里。
 * <p>
 * 用法：{@code ref.current.show(MODE_REGISTRY)}
 */
export default class ImageSyncModal extends React.Component {

    formRef = React.createRef()

    state = {
        open: false,
        mode: MODE_REGISTRY,
        // 只覆盖 POST 请求本身，不代表后台任务状态（任务状态看同步记录）
        submitting: false,
        // 本次同步的日志，非空时弹窗展示日志而不是表单
        logId: null,
        recentImages: [],
        hostOptions: [],
        registryOptions: [],
        registry: null,
        registryPreview: '',
    }

    /**
     * 打开弹窗：mode 决定同步方式，表单由 destroyOnHidden 保证每次都是空的。
     */
    show = mode => {
        this.autoName = ''
        this.autoTag = ''
        this.setState({
            open: true,
            mode,
            submitting: false,
            logId: null,
            registryPreview: '',
            recentImages: loadRecent(),
        })
        this.loadHosts()
        this.loadRegistry()
    }

    /**
     * 关闭弹窗：同步可能还在跑，刷新一次列表让记录状态跟上（记录已落库，不依赖本页面）。
     */
    close = () => {
        const {logId} = this.state
        this.setState({open: false})
        if (logId) {
            this.props.onSuccess?.()
        }
    }

    loadHosts = () => {
        HttpClient.get('admin/host/options', null, {toastError: false})
            .then(rs => this.setState({hostOptions: rs.data || []}))
            .catch(() => {})
    }

    /**
     * 注册中心列表只用于目标选择与目标镜像预览；没有 registry:view 权限时静默降级。
     */
    loadRegistry = () => {
        Promise.all([
            HttpClient.get('admin/registry/info', null, {toastError: false}),
            HttpClient.get('admin/registry/list', null, {toastError: false}),
        ]).then(([current, list]) => {
            this.setState({
                registry: current.data || null,
                registryOptions: (list.data || []).map(r => ({value: r.id, label: registryBaseOf(r)})),
            }, this.recalcPreview)
        }).catch(() => {})
    }

    /**
     * 自动补全候选项：最近使用 + 常用镜像，附上分组作为提示。
     */
    imageOptions = () => {
        const label = (value, group) => (
            <span style={{display: 'flex', justifyContent: 'space-between', gap: 16}}>
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
     * 目标镜像预览：镜像在平台注册中心上的完整地址（与后端默认命名规则保持一致）。
     * 镜像名/版本留空时按源镜像推导，填写后以填写的为准。
     */
    recalcPreview = () => {
        const {mode, registry, registryOptions} = this.state
        if (mode !== MODE_REGISTRY) {
            this.setState({registryPreview: ''})
            return
        }
        const values = this.formRef.current?.getFieldsValue() || {}
        const name = (values.targetName || '').trim() || targetNameOf(values.sourceImage)
        const tag = (values.targetTag || '').trim() || tagOf(values.sourceImage)
        const option = registryOptions.find(o => o.value === values.targetRegistryId)
        const base = option ? option.label : registryBaseOf(registry)
        this.setState({
            registryPreview: name && base ? base + '/' + name + ':' + tag : '',
        })
    }

    /**
     * 源镜像变化时给「镜像名 / 版本」补默认值：
     * 用户改过的字段保持不动（用「当前值是否还等于上次自动填入的值」判断），没改过的跟着源镜像走。
     */
    fillTargetDefaults = () => {
        const form = this.formRef.current
        if (!form) {
            return
        }
        const sourceImage = form.getFieldValue('sourceImage')
        const autoName = targetNameOf(sourceImage)
        const autoTag = sourceImage ? tagOf(sourceImage) : ''
        const currentName = (form.getFieldValue('targetName') || '').trim()
        const currentTag = (form.getFieldValue('targetTag') || '').trim()
        const patch = {}
        if (!currentName || currentName === this.autoName) {
            patch.targetName = autoName
        }
        if (!currentTag || currentTag === this.autoTag) {
            patch.targetTag = autoTag
        }
        this.autoName = autoName
        this.autoTag = autoTag
        if (Object.keys(patch).length) {
            form.setFieldsValue(patch)
        }
    }

    onValuesChange = changed => {
        if ('sourceImage' in changed) {
            this.fillTargetDefaults()
        }
        this.recalcPreview()
    }

    submit = values => {
        const {mode} = this.state
        const sourceImage = (values.sourceImage || '').trim()
        const payload = {
            syncType: mode,
            sourceImage,
            // 直连主机不需要执行主机；其余两种都要
            hostId: mode === MODE_HOST ? null : values.hostId,
            targetRegistryId: mode === MODE_REGISTRY ? values.targetRegistryId : null,
            targetHostId: mode === MODE_REGISTRY ? null : values.targetHostId,
            // 留空交给后端按源镜像推导
            targetName: mode === MODE_REGISTRY ? ((values.targetName || '').trim() || null) : null,
            targetTag: mode === MODE_REGISTRY ? ((values.targetTag || '').trim() || null) : null,
        }
        this.setState({submitting: true})
        HttpClient.post('admin/image-sync/sync', payload).then(rs => {
            this.setState({
                submitting: false,
                logId: rs.data,
                recentImages: pushRecent(sourceImage),
            })
            // 后端在起任务前已落库「同步中」记录，立刻刷新即可看到
            this.props.onSuccess?.()
        }).catch(() => {
            // 请求未成功（参数校验、无权限等）不会产生日志，恢复按钮
            this.setState({submitting: false})
        })
    }

    footer = () => {
        const {logId, submitting, mode, registryOptions} = this.state
        if (logId) {
            return [<Button key='close' onClick={this.close}>关闭</Button>]
        }
        return [
            <Button key='cancel' onClick={this.close}>取消</Button>,
            <Button key='submit' type='primary' loading={submitting}
                    disabled={mode === MODE_REGISTRY && registryOptions.length === 0}
                    onClick={() => this.formRef.current?.submit()}>
                开始同步
            </Button>,
        ]
    }

    renderLog = () => {
        const {logId} = this.state
        return <>
            <Alert type='info' showIcon className='mb-4'
                   title='同步已开始，日志会实时输出；关闭本窗口不影响后台任务，可稍后在「同步记录」中回看'/>
            <LogView key={logId} url={'/admin/ws/sync-log/' + logId} websocket={true} height={400}
                     onClose={() => this.props.onSuccess?.()}/>
        </>
    }

    renderForm = () => {
        const {mode, hostOptions, registryOptions, registryPreview} = this.state
        const isRegistry = mode === MODE_REGISTRY
        const isRelay = mode === MODE_RELAY
        const needExecHost = mode !== MODE_HOST
        const needTargets = mode !== MODE_REGISTRY
        const registryConfigured = registryOptions.length > 0

        return <Form ref={this.formRef} layout='vertical' preserve={false}
                     onValuesChange={this.onValuesChange}
                     onFinish={this.submit}>
            <Typography.Text type='secondary'>{MODE_META[mode].tip}</Typography.Text>

            {isRegistry && !registryConfigured && (
                <Alert className='mt-4' type='warning' showIcon
                       title='尚未配置镜像注册中心，请先前往【设置 - 镜像注册中心】完成配置'/>
            )}

            <Form.Item label='源镜像' name='sourceImage' className='mt-4'
                       rules={[{required: true, message: '请输入源镜像'}]}
                       tooltip='支持完整地址，如 nginx:1.25-alpine、docker.io/library/redis:7；私有镜像会按地址自动匹配已配置的注册中心凭据，无需另行选择。可从下拉列表选择常用镜像或最近同步过的镜像'>
                <AutoComplete options={this.imageOptions()} placeholder='nginx:1.25-alpine'
                              filterOption={(input, option) => option.value.toLowerCase().includes(input.toLowerCase())}/>
            </Form.Item>

            {isRegistry && (
                <Form.Item label='目标注册中心' name='targetRegistryId'
                           tooltip='默认使用全局设置中的注册中心；可在此为本次同步单独指定目标'>
                    <Select allowClear showSearch optionFilterProp='label' options={registryOptions}
                            placeholder='使用系统默认注册中心'/>
                </Form.Item>
            )}

            {isRegistry && (
                <Row gutter={16}>
                    <Col span={13}>
                        <Form.Item label='镜像名' name='targetName'
                                   tooltip='推送到注册中心后的镜像名，注册中心前缀不变；留空按源镜像的镜像名推导'
                                   rules={[{
                                       pattern: /^[a-z0-9]+(?:[._-][a-z0-9]+)*$/,
                                       message: '只能包含小写字母、数字与 _ . -，且不能以分隔符开头或结尾',
                                   }]}>
                            <Input allowClear placeholder='留空按源镜像推导'/>
                        </Form.Item>
                    </Col>
                    <Col span={11}>
                        <Form.Item label='版本' name='targetTag'
                                   tooltip='推送到注册中心后的版本号；留空沿用源镜像的版本'
                                   rules={[{
                                       pattern: /^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$/,
                                       message: '只能包含字母、数字与 _ . -，且不能以 . 或 - 开头',
                                   }]}>
                            <Input allowClear placeholder='留空沿用源镜像版本'/>
                        </Form.Item>
                    </Col>
                </Row>
            )}

            {isRegistry && registryPreview && (
                <Form.Item label='目标镜像' tooltip='推送后在平台注册中心上的完整地址，由上一步的镜像名与版本拼成'>
                    <Typography.Text copyable code>{registryPreview}</Typography.Text>
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

            {needTargets && (
                <Form.Item label='目标主机' name='targetHostId' dependencies={['hostId']}
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
        </Form>
    }

    render() {
        const {open, mode, logId} = this.state
        const meta = MODE_META[mode] || MODE_META[MODE_REGISTRY]
        return <Modal open={open}
                      width={640}
                      title={meta.title}
                      destroyOnHidden
                      footer={this.footer()}
                      onCancel={this.close}>
            {logId ? this.renderLog() : this.renderForm()}
        </Modal>
    }
}
