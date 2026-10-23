import {Button, Card, Form, Input, InputNumber, Space, Switch} from 'antd'
import React from 'react'
import {HttpClient, Page} from '@jiangood/open-admin'

/**
 * 系统级默认资源配置，以及构建结果邮件通知。
 */
export default class extends React.Component {
    state = {
        loading: true,
        saving: false,
        savingEmail: false,
        testing: false,
        registries: [],
        hosts: [],
        values: {},
        email: {}
    }

    // 回填走 state + initialValues：Card 的 loading 会把表单卸载重建，
    // 在 setState 回调里 setFieldsValue 时表单尚未挂载，数据会丢失。
    onShow() {
        this.load()
    }

    load = async () => {
        this.setState({loading: true})
        try {
            const [defaults, email, registries, hosts] = await Promise.all([
                HttpClient.get('admin/system-config/defaults'),
                HttpClient.get('admin/system-config/email'),
                HttpClient.get('admin/registry/options'),
                HttpClient.get('admin/host/options')
            ])
            this.setState({
                loading: false,
                registries: registries.data || [],
                hosts: hosts.data || [],
                values: defaults.data || {},
                email: email.data || {}
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

    onSaveEmail = async values => {
        this.setState({savingEmail: true})
        try {
            const rs = await HttpClient.post('admin/system-config/email', values)
            // 保存后刷新：授权码不会回显，passwordSet 也会随之更新
            this.setState({email: rs.data || values})
        } finally {
            this.setState({savingEmail: false})
        }
    }

    onTestEmail = async () => {
        this.setState({testing: true})
        try {
            await HttpClient.post('admin/system-config/email/test')
        } finally {
            this.setState({testing: false})
        }
    }

    render() {
        const {loading, saving, savingEmail, testing, registries, hosts, values, email} = this.state
        return <Page padding>
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

            <Card title='构建结果邮件通知' loading={loading} className='page-card'>
                <Form initialValues={email} labelCol={{flex: '140px'}} onFinish={this.onSaveEmail}>
                    <Form.Item label='邮件通知' name='enabled' valuePropName='checked'
                               tooltip='开启后，每次构建结束（成功或失败）都会把构建结果发送到收件邮箱'>
                        <Switch checkedChildren='开启' unCheckedChildren='关闭'/>
                    </Form.Item>
                    <Form.Item label='发件邮箱' name='username'
                               rules={[{type: 'email', message: '请输入正确的邮箱地址'}]}
                               tooltip='目前支持 QQ 邮箱；请先在 QQ 邮箱「设置 - 账号」中开启 SMTP 服务并获取授权码'>
                        <Input placeholder='如 123456@qq.com' autoComplete='off'/>
                    </Form.Item>
                    <Form.Item label='邮箱授权码' name='password'
                               tooltip='QQ 邮箱生成的授权码（不是 QQ 登录密码）；留空表示不修改已保存的授权码'>
                        <Input.Password autoComplete='new-password'
                                        placeholder={email.passwordSet ? '已设置，留空表示不修改' : '请输入 QQ 邮箱授权码'}/>
                    </Form.Item>
                    <Form.Item label='收件邮箱' name='to'
                               tooltip='接收构建结果的邮箱，可以是任意邮箱；多个邮箱用英文逗号分隔'>
                        <Input placeholder='如 dev@example.com；多个用英文逗号分隔' autoComplete='off'/>
                    </Form.Item>
                    <Form.Item label='发件人名称' name='fromName'
                               tooltip='可选，显示在收件人看到的发件人位置；留空则使用发件邮箱'>
                        <Input placeholder='可选，默认使用发件邮箱'/>
                    </Form.Item>
                    <Form.Item label='SMTP 服务器' name='smtpHost' tooltip='QQ 邮箱为 smtp.qq.com'>
                        <Input placeholder='smtp.qq.com' autoComplete='off'/>
                    </Form.Item>
                    <Form.Item label='SMTP 端口' name='smtpPort'
                               tooltip='465 使用 SSL（QQ 邮箱推荐）；587 使用 STARTTLS'>
                        <InputNumber min={1} max={65535} placeholder='465' style={{width: 160}}/>
                    </Form.Item>
                    <Space>
                        <Button type='primary' htmlType='submit' loading={savingEmail}>保存</Button>
                        <Button onClick={this.onTestEmail} loading={testing}>发送测试邮件</Button>
                        <span style={{color: 'var(--oa-color-text-secondary, #8c8c8c)'}}>
                            测试使用已保存的配置，修改后请先保存
                        </span>
                    </Space>
                </Form>
            </Card>
        </Page>
    }
}
