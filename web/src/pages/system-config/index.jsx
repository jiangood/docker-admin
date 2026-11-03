import {Alert, Button, Card, Form, Input, Select, Space} from 'antd'
import React from 'react'
import {HttpClient, Page} from '@jiangood/open-admin'

/**
 * 系统级默认资源配置，以及主机辅助容器镜像。
 * 邮件发件账号已改为框架「系统管理 - 参数设置 - 邮件发送」维护。
 */
export default class extends React.Component {
    state = {
        loading: true,
        saving: false,
        savingAgent: false,
        registries: [],
        hosts: [],
        values: {},
        agent: {}
    }

    // 回填走 state + initialValues：Card 的 loading 会把表单卸载重建，
    // 在 setState 回调里 setFieldsValue 时表单尚未挂载，数据会丢失。
    onShow() {
        this.load()
    }

    load = async () => {
        this.setState({loading: true})
        try {
            const [defaults, agent, registries, hosts] = await Promise.all([
                HttpClient.get('admin/system-config/defaults'),
                HttpClient.get('admin/system-config/agent'),
                HttpClient.get('admin/registry/options'),
                HttpClient.get('admin/host/options')
            ])
            this.setState({
                loading: false,
                registries: registries.data || [],
                hosts: hosts.data || [],
                values: defaults.data || {},
                agent: agent.data || {}
            })
        } catch (e) {
            this.setState({loading: false})
        }
    }

    // 保存状态只用按钮 loading 表达：HttpClient 已自动弹出后端的 msg，
    // 不用 message.loading，避免它被后续刷新拖着不关闭。
    onFinish = async values => {
        this.setState({saving: true})
        try {
            const rs = await HttpClient.post('admin/system-config/defaults', values)
            this.setState({values: rs.data || values})
        } finally {
            this.setState({saving: false})
        }
    }

    onSaveAgent = async values => {
        this.setState({savingAgent: true})
        try {
            const rs = await HttpClient.post('admin/system-config/agent', values)
            const data = rs.data || values
            this.setState({agent: data})
            this.agentFormRef.current?.setFieldsValue({repository: data.repository})
        } finally {
            this.setState({savingAgent: false})
        }
    }

    agentFormRef = React.createRef()

    render() {
        const {loading, saving, savingAgent, registries, hosts, values, agent} = this.state
        return <Page padding>
            <Alert className='mb-4' type='info' showIcon
                   title='邮件发送服务已移到「系统管理 - 参数设置 - 邮件发送」'
                   description='这里只保留平台的默认资源与辅助容器镜像。是否发送构建邮件仍由「项目详情页」的「邮件通知」开关控制，收件人为项目创建人的邮箱。'/>

            <Card title='全局设置' loading={loading} className='page-card mb-4'>
                <Form initialValues={values} labelCol={{flex: '140px'}} onFinish={this.onFinish}>
                    <Form.Item label='默认镜像注册中心' name='defaultRegistryId'
                               tooltip='未指定注册中心的镜像操作使用此默认值；留空表示不设置默认注册中心'>
                        <Select allowClear showSearch optionFilterProp='label' placeholder='选择默认注册中心'
                                options={registries.map(x => ({value: x.value, label: x.label}))}/>
                    </Form.Item>
                    <Form.Item label='默认构建主机' name='defaultBuildHostId'
                               tooltip='未指定构建主机时使用此主机；留空表示不设置默认构建主机'>
                        <Select allowClear showSearch optionFilterProp='label' placeholder='选择默认构建主机'
                                options={hosts.map(x => ({value: x.value, label: x.label}))}/>
                    </Form.Item>
                    <Space>
                        <Button type='primary' htmlType='submit' loading={saving}>保存</Button>
                    </Space>
                </Form>
            </Card>

            <Card title='主机文件 / Docker CLI 辅助容器' loading={loading} className='page-card mt-4'>
                <Form ref={this.agentFormRef} initialValues={agent} labelCol={{flex: '140px'}}
                      onFinish={this.onSaveAgent}>
                    <Alert className='mb-4' type='info' showIcon
                           title='全局只配置镜像地址；tag 在每台主机的部署弹窗里选择，默认 latest'/>
                    <Form.Item label='Agent 镜像地址' name='repository'
                               rules={[{
                                   validator: (_, value) => {
                                       const text = (value || '').trim()
                                       if (!text) {
                                           return Promise.resolve()
                                       }
                                       if (/\s/.test(text) || text.includes('@')) {
                                           return Promise.reject(new Error('只填写镜像仓库地址，不要带 digest'))
                                       }
                                       const lastSlash = text.lastIndexOf('/')
                                       const lastSegment = lastSlash >= 0 ? text.substring(lastSlash + 1) : text
                                       if (lastSegment.includes(':')) {
                                           return Promise.reject(new Error('镜像地址不要带 tag，tag 在主机部署时选择'))
                                       }
                                       return Promise.resolve()
                                   }
                               }]}
                               tooltip='例如 ghcr.io/jiangood/docker-admin-agent；国内网络可改为阿里云或私有仓库地址'>
                        <Input placeholder={agent.defaultRepository || 'ghcr.io/jiangood/docker-admin-agent'}
                               autoComplete='off' style={{maxWidth: 520}}/>
                    </Form.Item>
                    <div style={{fontSize: 12, color: 'var(--oa-color-text-secondary, #8c8c8c)', marginBottom: 16}}>
                        实际镜像 = 上面的地址 + 主机部署时选择的 tag。留空表示使用 application.yml 的默认地址。
                        <br/>
                        目标主机必须能拉取该镜像，或预先 <code>docker load</code>；无外网主机请先预加载。
                    </div>
                    <Space>
                        <Button type='primary' htmlType='submit' loading={savingAgent}>保存</Button>
                    </Space>
                </Form>
            </Card>
        </Page>
    }
}
