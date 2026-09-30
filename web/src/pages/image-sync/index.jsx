import {Alert, Button, Card, Form, Input, Select} from 'antd'
import React from 'react'
import {HttpClient, Page, PermActions} from '@jiangood/open-admin'
import LogView from '../../components/LogView'

const PLATFORMS = [
    {value: '', label: '默认（宿主机架构）'},
    {value: 'linux/amd64', label: 'linux/amd64'},
    {value: 'linux/arm64', label: 'linux/arm64'},
]

/**
 * 镜像同步：选择一台网络通畅的主机拉取公共镜像，然后推送到平台注册中心，或直接传输到其他主机。
 * 两种目标左右并排，各自的表单字段独立维护、独立提交。
 */
export default class extends React.Component {

    state = {
        registry: null,
        hostOptions: [],
        registryPreview: '',
        logId: null,
        submitting: false,
    }

    registryFormRef = React.createRef()
    hostFormRef = React.createRef()

    componentDidMount() {
        this.loadRegistry()
        this.loadHosts()
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
     * 注册中心卡片的目标地址预览（与后端默认命名规则保持一致）。
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

    /**
     * 提交同步：注册中心卡片固定推送到注册中心，目标主机卡片固定传输到目标主机。
     */
    submit = (values, toRegistry) => {
        const payload = {
            hostId: values.hostId,
            sourceImage: values.sourceImage,
            targetName: values.targetName,
            platform: values.platform || '',
            toRegistry,
            targetHostIds: toRegistry ? [] : (values.targetHostIds || []),
        }
        this.setState({submitting: true})
        HttpClient.post('admin/image-sync/sync', payload).then(rs => {
            this.setState({logId: rs.data})
        }).finally(() => this.setState({submitting: false}))
    }

    render() {
        const {registry, hostOptions, registryPreview, logId, submitting} = this.state
        const registryConfigured = !!(registry && registry.url)
        const formProps = {labelCol: {flex: '100px'}, preserve: false}

        return <Page padding>
            <Alert
                type='info'
                showIcon
                style={{marginBottom: 16}}
                message='选择一台网络通畅的主机拉取公共镜像，再分发到注册中心或目标主机'
                description='国内主机直连 Docker Hub 等公共仓库较慢，可借助网络通畅的节点做中转；两种目标各自独立同步，过程可在下方实时查看日志。'
            />

            <div style={{display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'flex-start'}}>
                <Card title='同步到镜像注册中心' style={{flex: '1 1 480px'}}>
                    {!registryConfigured && (
                        <Alert
                            type='warning'
                            showIcon
                            style={{marginBottom: 16}}
                            message='尚未配置镜像注册中心，请先前往【设置 - 镜像注册中心】完成配置'
                        />
                    )}

                    <Form {...formProps}
                          ref={this.registryFormRef}
                          initialValues={{platform: ''}}
                          onValuesChange={(_, values) => this.calcRegistryPreview(values)}
                          onFinish={values => this.submit(values, true)}>

                        <Form.Item label='同步节点' name='hostId' rules={[{required: true, message: '请选择同步节点'}]}
                                   tooltip='请选择网络通畅、能访问公共镜像仓库的主机，负责拉取源镜像'>
                            <Select options={hostOptions} showSearch optionFilterProp='label'
                                    placeholder='请选择同步节点'/>
                        </Form.Item>

                        <Form.Item label='源镜像' name='sourceImage' rules={[{required: true, message: '请输入源镜像'}]}
                                   tooltip='支持完整地址，如 nginx:1.25-alpine、docker.io/library/redis:7'>
                            <Input placeholder='nginx:1.25-alpine'/>
                        </Form.Item>

                        <Form.Item label='目标镜像名' name='targetName' tooltip='留空则取源镜像的最后一段作为镜像名'>
                            <Input placeholder='留空自动使用源镜像名'/>
                        </Form.Item>

                        <Form.Item label='平台' name='platform'>
                            <Select options={PLATFORMS}/>
                        </Form.Item>

                        {registryPreview &&
                            <Form.Item label='目标地址'>
                                <Input readOnly value={registryPreview}/>
                            </Form.Item>
                        }

                        <Form.Item>
                            <PermActions>
                                <Button perm='image-sync:sync' type='primary' htmlType='submit'
                                        loading={submitting} disabled={!registryConfigured}>
                                    同步到注册中心
                                </Button>
                            </PermActions>
                        </Form.Item>
                    </Form>
                </Card>

                <Card title='同步到目标主机' style={{flex: '1 1 480px'}}>
                    <Form {...formProps}
                          ref={this.hostFormRef}
                          initialValues={{platform: '', targetHostIds: []}}
                          onFinish={values => this.submit(values, false)}>

                        <Form.Item label='同步节点' name='hostId' rules={[{required: true, message: '请选择同步节点'}]}
                                   tooltip='请选择网络通畅、能访问公共镜像仓库的主机，负责拉取源镜像'>
                            <Select options={hostOptions} showSearch optionFilterProp='label'
                                    placeholder='请选择同步节点'/>
                        </Form.Item>

                        <Form.Item label='源镜像' name='sourceImage' rules={[{required: true, message: '请输入源镜像'}]}
                                   tooltip='支持完整地址，如 nginx:1.25-alpine、docker.io/library/redis:7'>
                            <Input placeholder='nginx:1.25-alpine'/>
                        </Form.Item>

                        <Form.Item label='目标镜像名' name='targetName' tooltip='留空则取源镜像的最后一段作为镜像名'>
                            <Input placeholder='留空自动使用源镜像名'/>
                        </Form.Item>

                        <Form.Item label='平台' name='platform'>
                            <Select options={PLATFORMS}/>
                        </Form.Item>

                        <Form.Item label='目标主机' name='targetHostIds'
                                   rules={[{required: true, message: '请选择至少一台目标主机'}]}
                                   tooltip='镜像以 save/load 方式直接传输到所选主机，可多选'>
                            <Select mode='multiple' options={hostOptions} showSearch optionFilterProp='label'
                                    allowClear placeholder='请选择目标主机'/>
                        </Form.Item>

                        <Form.Item>
                            <PermActions>
                                <Button perm='image-sync:sync' type='primary' htmlType='submit' loading={submitting}>
                                    同步到目标主机
                                </Button>
                            </PermActions>
                        </Form.Item>
                    </Form>
                </Card>
            </div>

            {logId &&
                <Card title='同步日志' style={{marginTop: 16}}
                      extra={<Button type='link' onClick={() => this.setState({logId: null})}>清空</Button>}>
                    <LogView url={'/admin/ws/sync-log/' + logId} websocket={true}/>
                </Card>
            }
        </Page>
    }
}
