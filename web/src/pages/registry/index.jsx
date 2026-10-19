import {Button, Card, Form, Input, Modal, Popconfirm, Space, Table, message} from 'antd'
import React from 'react'
import {HttpClient, Page, PermActions} from '@jiangood/open-admin'

/** 镜像注册中心实例管理。 */
export default class extends React.Component {
    state = {loading: true, rows: [], modalOpen: false, editing: false, formValues: {}}
    formRef = React.createRef()

    // 首次加载由 onShow 触发（PageFrame 挂载时会调一次），这里不再重复请求
    onShow() { this.load() }

    load = () => {
        this.setState({loading: true})
        HttpClient.get('admin/registry/list')
            .then(rs => this.setState({rows: rs.data || [], loading: false}))
            .catch(() => this.setState({loading: false}))
    }

    // 回填走 initialValues：Modal 的 children 首次 open 才挂载，
    // 在 setState 回调里 setFieldsValue 时表单尚未挂载，数据会丢失。
    create = () => {
        this.setState({modalOpen: true, editing: false, formValues: {}})
    }

    edit = record => {
        this.setState({modalOpen: true, editing: true, formValues: {...record, password: ''}})
    }

    save = values => {
        const hide = message.loading('保存中…', 0)
        HttpClient.post('admin/registry/save', values)
            .then(() => {
                message.success('保存成功')
                this.setState({modalOpen: false})
                this.load()
            }).finally(hide)
    }

    remove = id => {
        HttpClient.post('admin/registry/delete?id=' + encodeURIComponent(id))
            .then(() => { message.success('删除成功'); this.load() })
    }

    render() {
        const {loading, rows, modalOpen, editing, formValues} = this.state
        const columns = [
            {title: '地址', dataIndex: 'url'},
            {title: '命名空间', dataIndex: 'namespace'},
            {title: '用户名', dataIndex: 'username'},
            {title: '凭据', dataIndex: 'passwordMasked', render: v => v ? '已配置' : '未配置'},
            {title: '操作', key: 'action', render: (_, row) => <Space>
                <Button type='link' onClick={() => this.edit(row)}>编辑</Button>
                <Popconfirm title='确定删除该注册中心？' description='默认注册中心需先到设置-全局设置中更换；已被项目使用的注册中心需先解除绑定。'
                            onConfirm={() => this.remove(row.id)}>
                    <Button type='link' danger>删除</Button>
                </Popconfirm>
            </Space>}
        ]
        return <Page padding>
            <Card title='镜像注册中心' className='page-card'
                  extra={<PermActions actions={[
                      {label: '新增注册中心', perm: 'registry:save', type: 'primary', onClick: this.create},
                  ]}/>}>
                <Table rowKey='id' loading={loading} dataSource={rows} columns={columns} pagination={false}/>
            </Card>
            <Modal title={editing ? '编辑注册中心' : '新增注册中心'} open={modalOpen} destroyOnHidden
                   onCancel={() => this.setState({modalOpen: false})}
                   onOk={() => this.formRef.current?.submit()}>
                <Form ref={this.formRef} layout='vertical' onFinish={this.save}
                      initialValues={formValues} preserve={false}>
                    <Form.Item name='id' hidden><Input/></Form.Item>
                    <Form.Item label='地址' name='url' rules={[{required: true, message: '请输入注册中心地址'}]}
                               tooltip='不含协议，如 ghcr.io / registry.cn-hangzhou.aliyuncs.com'>
                        <Input placeholder='ghcr.io'/>
                    </Form.Item>
                    <Form.Item label='命名空间' name='namespace' rules={[{required: true, message: '请输入命名空间'}]}>
                        <Input placeholder='jiangood'/>
                    </Form.Item>
                    <Form.Item label='用户名' name='username'><Input/></Form.Item>
                    <Form.Item label='密码' name='password' tooltip='编辑时留空表示不修改当前密码'>
                        <Input.Password autoComplete='new-password' placeholder={editing ? '留空不修改' : ''}/>
                    </Form.Item>
                </Form>
            </Modal>
        </Page>
    }
}
