import {Alert, AutoComplete, Button, Card, Drawer, Form, Input, Select, Typography} from 'antd'
import React from 'react'
import {FileTextOutlined} from '@ant-design/icons'
import {HttpClient, Page, PermActions} from '@jiangood/open-admin'
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
 * 镜像同步：选一台网络通畅的主机拉取公共镜像，推送到平台注册中心；
 * 目标主机（可选）再从注册中心拉取并打成不带注册中心前缀的镜像名。
 * <p>
 * 注册中心是必经环节，因此只有一个表单。
 */
export default class extends React.Component {

    state = {
        registry: null,
        hostOptions: [],
        registryPreview: '',
        recentImages: [],
        logId: null,
        logVisible: false,
        syncing: false,
    }

    componentDidMount() {
        this.loadRegistry()
        this.loadHosts()
        this.setState({recentImages: loadRecent()})
    }

    loadRegistry = () => {
        HttpClient.get('admin/registry/info')
            .then(rs => this.setState({registry: rs.data || null}))
            .catch(() => {
            })
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
     * 目标镜像名：留空则取源镜像的最后一段。
     */
    targetNameOf = (sourceImage, targetName) => {
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
        return (targetName || '').trim() || repo.split('/').pop()
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
     * 注册中心的目标地址预览（与后端默认命名规则保持一致）。
     */
    calcRegistryPreview = values => {
        const {registry} = this.state
        const name = this.targetNameOf(values.sourceImage, values.targetName)
        if (!name || !registry || !registry.url) {
            this.setState({registryPreview: ''})
            return
        }
        const base = registry.url + (registry.namespace ? '/' + registry.namespace : '')
        this.setState({registryPreview: base + '/' + name + ':' + this.tagOf(values.sourceImage)})
    }

    submit = values => {
        const payload = {
            hostId: values.hostId,
            sourceImage: values.sourceImage,
            targetName: values.targetName,
            targetHostIds: values.targetHostIds || [],
        }
        this.setState({syncing: true})
        HttpClient.post('admin/image-sync/sync', payload).then(rs => {
            this.setState({recentImages: pushRecent(values.sourceImage.trim()), logId: rs.data, logVisible: true})
        }).catch(() => {
            // 请求未成功（参数校验、无权限等）不会产生日志，直接恢复按钮
            this.setState({syncing: false})
        })
    }

    /**
     * 日志流关闭即表示后台同步已结束，恢复「开始同步」按钮。
     */
    onSyncFinished = () => {
        this.setState({syncing: false})
    }

    /**
     * 重新打开上一次的同步日志，避免日志面板被误关后无法再次查看。
     */
    openLog = () => {
        if (this.state.logId) {
            this.setState({logVisible: true})
        }
    }

    render() {
        const {registry, hostOptions, registryPreview, logId, logVisible, syncing} = this.state
        const registryConfigured = !!(registry && registry.url)
        const formProps = {labelCol: {flex: '100px'}, preserve: false}

        return <Page padding>
            <Alert
                type='info'
                showIcon
                style={{marginBottom: 16}}
                message='选择一台网络通畅的主机拉取公共镜像，推送到注册中心'
                description='国内主机直连 Docker Hub 等公共仓库较慢，可借助网络通畅的主机做中转。镜像会先推送到注册中心，再按需分发到目标主机；同步过程中会从右侧弹出实时日志。'
            />

            <Card title='镜像同步' className='page-card'
                  extra={logId ? (
                      <Button type='link' size='small' icon={<FileTextOutlined/>} onClick={this.openLog}>
                          查看同步日志
                      </Button>
                  ) : null}>
                {!registryConfigured && (
                    <Alert
                        type='warning'
                        showIcon
                        style={{marginBottom: 16}}
                        message='尚未配置镜像注册中心，请先前往【设置 - 镜像注册中心】完成配置'
                    />
                )}

                <Form {...formProps}
                      disabled={logVisible}
                      initialValues={{targetHostIds: []}}
                      onValuesChange={(_, values) => this.calcRegistryPreview(values)}
                      onFinish={this.submit}>

                    <Form.Item label='源镜像' name='sourceImage' rules={[{required: true, message: '请输入源镜像'}]}
                               tooltip='支持完整地址，如 nginx:1.25-alpine、docker.io/library/redis:7；可从下拉列表选择常用镜像或最近同步过的镜像'>
                        <AutoComplete options={this.imageOptions()} placeholder='nginx:1.25-alpine'
                                      filterOption={(input, option) => option.value.toLowerCase().includes(input.toLowerCase())}/>
                    </Form.Item>

                    <Form.Item label='目标镜像名' name='targetName' tooltip='留空则取源镜像的最后一段作为镜像名'>
                        <Input placeholder='留空自动使用源镜像名'/>
                    </Form.Item>

                    {registryPreview &&
                        <Form.Item label='注册中心地址'>
                            <Input readOnly value={registryPreview}/>
                        </Form.Item>
                    }

                    <Form.Item label='同步主机' name='hostId' rules={[{required: true, message: '请选择同步主机'}]}
                               tooltip='网络通畅、能访问公共镜像仓库的主机，负责拉取源镜像并推送到注册中心'>
                        <Select options={hostOptions} showSearch optionFilterProp='label'
                                placeholder='请选择同步主机'/>
                    </Form.Item>

                    <Form.Item label='目标主机' name='targetHostIds'
                               dependencies={['hostId']}
                               rules={[({getFieldValue}) => ({
                                   validator: (_, value) => {
                                       const hostId = getFieldValue('hostId')
                                       if (hostId && value && value.includes(hostId)) {
                                           return Promise.reject(new Error('目标主机不能与同步主机相同'))
                                       }
                                       return Promise.resolve()
                                   },
                               })]}
                               tooltip='可选。同步主机把镜像推到注册中心后，这些主机会从注册中心拉取并重新打成上面的目标镜像名；不选则只推送到注册中心'>
                        <Select mode='multiple' options={hostOptions} showSearch optionFilterProp='label'
                                allowClear placeholder='留空则只推送到注册中心'/>
                    </Form.Item>

                    <Form.Item>
                        <PermActions>
                            <Button perm='image-sync:sync' type='primary' htmlType='submit'
                                    loading={syncing} disabled={!registryConfigured}>
                                开始同步
                            </Button>
                        </PermActions>
                    </Form.Item>
                </Form>
            </Card>

            <Drawer title='同步日志' width={860} open={logVisible}
                    mask={{closable: false}}
                    onClose={() => this.setState({logVisible: false})}>
                {logId
                    ? <LogView key={logId} url={'/admin/ws/sync-log/' + logId} websocket={true}
                               onClose={this.onSyncFinished}/>
                    : null}
            </Drawer>
        </Page>
    }
}
