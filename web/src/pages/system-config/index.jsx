import {Alert, Button, Card, Form, Input, InputNumber, Select, Space} from 'antd'
import React from 'react'
import {HttpClient, Page} from '@jiangood/open-admin'

// Alert 里的代码片段：antd 的 reset 会抹掉 <code> 的默认样式，这里显式给一点区分
const CODE_STYLE = {
    background: 'rgba(0, 0, 0, 0.06)',
    borderRadius: 4,
    padding: '0 4px',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace'
}

/**
 * 系统级默认资源配置，以及邮件通知。
 */
export default class extends React.Component {
    state = {
        loading: true,
        saving: false,
        savingEmail: false,
        testing: false,
        showProxyHelp: false,
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

    toggleProxyHelp = () => this.setState(state => ({showProxyHelp: !state.showProxyHelp}))

    render() {
        const {loading, saving, savingEmail, testing, showProxyHelp, registries, hosts, values, email} = this.state
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

            <Card title='邮件发送服务（QQ 邮箱）' loading={loading} className='page-card'>
                <Form initialValues={email} labelCol={{flex: '140px'}} onFinish={this.onSaveEmail}>
                    <Alert className='mb-4' type='info' showIcon
                           title='这里只配置发件邮箱。是否发送邮件在「项目详情页」按项目开启，收件人为项目创建人的邮箱。'/>
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
                    <Form.Item label='代理类型' name='proxyType'
                               tooltip='宿主机不能直连外网时，经另一台能出网的主机中转邮件；默认不使用代理'
                               extra={
                                   <Button type='link' size='small' style={{padding: 0, height: 'auto'}}
                                           onClick={this.toggleProxyHelp}>
                                       {showProxyHelp ? '收起 gost 部署说明' : '没有现成代理？用 gost 容器部署一个'}
                                   </Button>
                               }>
                        <Select style={{width: 200}} options={[
                            {value: 'none', label: '不使用'},
                            {value: 'http', label: 'HTTP 代理'},
                            {value: 'socks5', label: 'SOCKS5 代理'}
                        ]}/>
                    </Form.Item>
                    {showProxyHelp && <Alert className='mb-4' type='info' showIcon
                                             title='在能访问外网的主机上用 gost 起一个代理'
                                             description={<>
                                                 <pre style={{margin: '8px 0 0', whiteSpace: 'pre-wrap'}}>{`docker run -d --name mail-proxy --restart=always \\
  -p 192.168.1.10:8080:8080 \\
  gogost/gost -L http://user:pass@:8080`}</pre>
                                                 <div style={{marginTop: 8}}>
                                                     把 <code style={CODE_STYLE}>192.168.1.10</code> 换成代理主机的内网 IP，
                                                     <code style={CODE_STYLE}>user:pass</code> 换成自己的账号密码；
                                                     端口只映射到内网，不要暴露到公网。
                                                     <br/>
                                                     平台跑在容器里时，「代理地址」要填宿主机网桥网关（如
                                                     <code style={CODE_STYLE}>172.17.0.1</code>）或代理主机的内网 IP，不能填
                                                     <code style={CODE_STYLE}>127.0.0.1</code>。
                                                     <br/>
                                                     需要 SOCKS5 就把 <code style={CODE_STYLE}>-L http://...</code> 换成
                                                     <code style={CODE_STYLE}>-L socks5://:1080</code>（JDK 的 SOCKS 不支持账号密码，
                                                     有认证需求时用 HTTP 代理）。
                                                 </div>
                                             </>}/>}
                    <Form.Item noStyle shouldUpdate={(prev, cur) => prev.proxyType !== cur.proxyType}>
                        {({getFieldValue}) => {
                            const proxyType = getFieldValue('proxyType')
                            if (!proxyType || proxyType === 'none') {
                                return null
                            }
                            return <>
                                <Form.Item label='代理地址' name='proxyHost'
                                           rules={[{required: true, message: '请输入代理地址'}]}
                                           tooltip='平台运行在容器里时不能填 127.0.0.1，请填宿主机网桥网关（如 172.17.0.1）或局域网 IP'>
                                    <Input placeholder='如 192.168.1.10' autoComplete='off'/>
                                </Form.Item>
                                <Form.Item label='代理端口' name='proxyPort'
                                           rules={[{required: true, message: '请输入代理端口'}]}>
                                    <InputNumber min={1} max={65535}
                                                 placeholder={proxyType === 'socks5' ? '1080' : '8080'}
                                                 style={{width: 160}}/>
                                </Form.Item>
                                {proxyType === 'http' && <>
                                    <Form.Item label='代理用户名' name='proxyUsername'
                                               tooltip='可选，HTTP 代理的 Basic 认证账号'>
                                        <Input placeholder='可选' autoComplete='off'/>
                                    </Form.Item>
                                    <Form.Item label='代理密码' name='proxyPassword'
                                               tooltip='可选；留空表示不修改已保存的密码'>
                                        <Input.Password autoComplete='new-password'
                                                        placeholder={email.proxyPasswordSet ? '已设置，留空表示不修改' : '可选'}/>
                                    </Form.Item>
                                </>}
                                {proxyType === 'socks5' &&
                                    <Alert className='mb-4' type='warning' showIcon
                                           title='SOCKS5 不支持账号密码认证；需要认证请改用 HTTP 代理（如 gost 的 -L http://user:pass@:8080）。'/>}
                            </>
                        }}
                    </Form.Item>
                    <Space>
                        <Button type='primary' htmlType='submit' loading={savingEmail}>保存</Button>
                        <Button onClick={this.onTestEmail} loading={testing}>发送测试邮件</Button>
                        <span style={{color: 'var(--oa-color-text-secondary, #8c8c8c)'}}>
                            测试邮件发到发件邮箱自己；修改后请先保存
                        </span>
                    </Space>
                </Form>
            </Card>
        </Page>
    }
}
