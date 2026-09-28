import {Button, Card, Form, Input, message, Spin} from 'antd'
import React from 'react'
import {HttpClient, Page, PermActions} from '@jiangood/open-admin'

/**
 * 镜像注册中心（全局唯一）
 */
export default class extends React.Component {

    state = {
        loading: true,
        values: {}
    }

    formRef = React.createRef()

    componentDidMount() {
        this.load()
    }

    onShow() {
        this.load()
    }

    load = () => {
        this.setState({loading: true})
        HttpClient.get('admin/registry/info').then(rs => {
            this.setState({loading: false, values: rs.data || {}})
        }).catch(() => this.setState({loading: false}))
    }

    onFinish = values => {
        const hide = message.loading('保存中...', 0)
        HttpClient.post('admin/registry/save', values).then(rs => {
            message.success(rs.msg || '保存成功')
            this.load()
        }).finally(hide)
    }

    render() {
        if (this.state.loading) {
            return <Page padding><Spin/></Page>
        }
        const {values} = this.state
        const hasPassword = !!values.passwordMasked

        return <Page padding>
            <Card title='镜像注册中心' style={{maxWidth: 640}}>
                <Form ref={this.formRef} labelCol={{flex: '120px'}}
                      initialValues={values} onFinish={this.onFinish} preserve={false}>

                    <Form.Item name='id' noStyle></Form.Item>

                    <Form.Item label='地址' name='url' rules={[{required: true, message: '请输入注册中心地址'}]}
                               tooltip='不含协议，如 ghcr.io / registry.cn-hangzhou.aliyuncs.com'>
                        <Input placeholder='ghcr.io'/>
                    </Form.Item>

                    <Form.Item label='命名空间' name='namespace'
                               rules={[{required: true, message: '请输入命名空间'}]}>
                        <Input placeholder='jiangood'/>
                    </Form.Item>

                    <Form.Item label='用户名' name='username'>
                        <Input/>
                    </Form.Item>

                    <Form.Item label='密码' name='password'
                               tooltip={hasPassword ? '当前已设置密码，留空表示不修改' : ''}>
                        <Input.Password autoComplete='new-password'
                                        placeholder={hasPassword ? '******（留空不修改）' : ''}/>
                    </Form.Item>

                    <Form.Item>
                        <PermActions>
                            <Button perm='registry:save' type='primary' htmlType='submit'>保存</Button>
                        </PermActions>
                    </Form.Item>
                </Form>
            </Card>
        </Page>
    }
}
