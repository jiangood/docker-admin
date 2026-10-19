import {Button, Card, Form, Select, Space, message} from 'antd'
import React from 'react'
import {HttpClient, Page} from '@jiangood/open-admin'

/**
 * 系统级默认资源配置。
 */
export default class extends React.Component {
    state = {loading: true, registries: [], hosts: [], values: {}}

    // 回填走 state + initialValues：Card 的 loading 会把表单卸载重建，
    // 在 setState 回调里 setFieldsValue 时表单尚未挂载，数据会丢失。
    onShow() {
        this.load()
    }

    load = async () => {
        this.setState({loading: true})
        try {
            const [defaults, registries, hosts] = await Promise.all([
                HttpClient.get('admin/system-config/defaults'),
                HttpClient.get('admin/registry/options'),
                HttpClient.get('admin/host/options')
            ])
            this.setState({
                loading: false,
                registries: registries.data || [],
                hosts: hosts.data || [],
                values: defaults.data || {}
            })
        } catch (e) {
            this.setState({loading: false})
        }
    }

    onFinish = values => {
        const hide = message.loading('保存中…', 0)
        HttpClient.post('admin/system-config/defaults', values)
            .then(() => message.success('保存成功'))
            .then(() => this.load())
            .finally(hide)
    }

    render() {
        const {loading, registries, hosts, values} = this.state
        return <Page padding>
            <Card title='全局设置' loading={loading} className='page-card'>
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
                        <Button type='primary' htmlType='submit'>保存</Button>
                    </Space>
                </Form>
            </Card>
        </Page>
    }
}
