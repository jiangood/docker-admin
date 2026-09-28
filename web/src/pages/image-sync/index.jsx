import {Alert, Button, Card, Drawer, Form, Input, Select} from 'antd'
import React from 'react'
import {HttpClient, Page, PermActions} from '@jiangood/open-admin'
import LogView from '../../components/LogView'

const PLATFORMS = [
    {value: '', label: '默认（宿主机架构）'},
    {value: 'linux/amd64', label: 'linux/amd64'},
    {value: 'linux/arm64', label: 'linux/arm64'},
]

/**
 * 镜像同步：选择一台网络通畅的主机，拉取公共镜像并推送到本项目配置的镜像注册中心。
 */
export default class extends React.Component {

    state = {
        registry: null,
        hostOptions: [],
        preview: '',
        logId: null,
        logVisible: false,
        submitting: false,
    }

    formRef = React.createRef()

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
     * 目标镜像地址预览（与后端默认命名规则保持一致）。
     */
    calcPreview = values => {
        const {registry} = this.state
        const src = (values.sourceImage || '').trim()
        if (!src || !registry || !registry.url) {
            this.setState({preview: ''})
            return
        }
        let repo = src
        let tag = 'latest'
        const lastSlash = src.lastIndexOf('/')
        const lastColon = src.lastIndexOf(':')
        if (lastColon > lastSlash) {
            tag = src.slice(lastColon + 1)
            repo = src.slice(0, lastColon)
        }
        const name = (values.targetName || '').trim() || repo.split('/').pop()
        const base = registry.url + (registry.namespace ? '/' + registry.namespace : '')
        this.setState({preview: base + '/' + name + ':' + tag})
    }

    onFinish = values => {
        this.setState({submitting: true})
        HttpClient.post('admin/image-sync/sync', values).then(rs => {
            this.setState({logId: rs.data, logVisible: true})
        }).finally(() => this.setState({submitting: false}))
    }

    render() {
        const {registry, hostOptions, preview, logId, logVisible, submitting} = this.state
        const registryConfigured = !!(registry && registry.url)

        return <Page padding>
            <Card title='镜像同步' style={{maxWidth: 760}}>
                <Alert
                    type='info'
                    showIcon
                    style={{marginBottom: 16}}
                    message='选择一台网络通畅的主机，拉取公共镜像并推送到镜像注册中心'
                    description='国内主机直连 Docker Hub 等公共仓库较慢，可借助网络通畅的节点做中转；同步过程可在下方实时查看日志。'
                />

                {!registryConfigured && (
                    <Alert
                        type='warning'
                        showIcon
                        style={{marginBottom: 16}}
                        message='尚未配置镜像注册中心，请先前往【设置 - 镜像注册中心】完成配置'
                    />
                )}

                <Form ref={this.formRef}
                      labelCol={{flex: '100px'}}
                      initialValues={{platform: ''}}
                      preserve={false}
                      onValuesChange={(_, values) => this.calcPreview(values)}
                      onFinish={this.onFinish}>

                    <Form.Item label='同步主机' name='hostId' rules={[{required: true, message: '请选择同步主机'}]}
                               tooltip='请选择网络通畅、能访问公共镜像仓库的主机'>
                        <Select options={hostOptions} showSearch optionFilterProp='label'
                                placeholder='请选择同步主机'/>
                    </Form.Item>

                    <Form.Item label='源镜像' name='sourceImage' rules={[{required: true, message: '请输入源镜像'}]}
                               tooltip='支持完整地址，如 nginx:1.25-alpine、docker.io/library/redis:7'>
                        <Input placeholder='nginx:1.25-alpine'/>
                    </Form.Item>

                    <Form.Item label='目标镜像名' name='targetName'
                               tooltip='留空则取源镜像的最后一段作为镜像名'>
                        <Input placeholder='留空自动使用源镜像名'/>
                    </Form.Item>

                    <Form.Item label='平台' name='platform'>
                        <Select options={PLATFORMS}/>
                    </Form.Item>

                    {preview &&
                        <Form.Item label='目标地址'>
                            <Input readOnly value={preview}/>
                        </Form.Item>
                    }

                    <Form.Item>
                        <PermActions>
                            <Button perm='image-sync:sync' type='primary' htmlType='submit'
                                    loading={submitting} disabled={!registryConfigured}>
                                同步
                            </Button>
                        </PermActions>
                    </Form.Item>
                </Form>
            </Card>

            <Drawer title='同步日志' width={860} open={logVisible}
                    onClose={() => this.setState({logVisible: false})}>
                {logVisible && logId
                    ? <LogView url={'/admin/ws/sync-log/' + logId} websocket={true}/>
                    : null}
            </Drawer>
        </Page>
    }
}
