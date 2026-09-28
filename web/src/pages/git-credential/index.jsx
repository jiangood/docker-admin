import {PlusOutlined} from '@ant-design/icons'
import {Button, Form, Input, Modal, Popconfirm} from 'antd'
import React from 'react'

import {HttpClient, Page, PermActions, ProTable} from '@jiangood/open-admin'

/**
 * Git 仓库凭据，按 url 前缀匹配
 */
export default class extends React.Component {

    state = {
        formValues: {},
        formOpen: false
    }

    formRef = React.createRef()
    tableRef = React.createRef()

    columns = [
        {
            title: '名称',
            dataIndex: 'name',
        },
        {
            title: '仓库地址前缀',
            dataIndex: 'url',
        },
        {
            title: '用户名',
            dataIndex: 'username',
        },
        {
            title: '密码',
            dataIndex: 'passwordMasked',
            hideInSearch: true,
            render: v => v ? '******' : '',
        },
        {
            title: '操作',
            dataIndex: 'option',
            valueType: 'option',
            width: 120,
            render: (_, record) => (
                <PermActions>
                    <a perm='git-credential:save' onClick={() => this.handleEdit(record)}> 修改 </a>
                    <Popconfirm perm='git-credential:delete' title='是否确定删除该凭据' onConfirm={() => this.handleDelete(record)}>
                        <a>删除</a>
                    </Popconfirm>
                </PermActions>
            ),
        },
    ]

    handleAdd = () => {
        this.setState({formOpen: true, formValues: {}})
    }

    handleEdit = record => {
        this.setState({formOpen: true, formValues: {...record, password: ''}})
    }

    onFinish = values => {
        HttpClient.post('admin/git-credential/save', values).then(rs => {
            this.setState({formOpen: false})
            this.tableRef.current.reload()
        })
    }

    handleDelete = record => {
        HttpClient.postForm('admin/git-credential/delete', {id: record.id}).then(() => {
            this.tableRef.current.reload()
        })
    }

    render() {
        return <Page padding>
            <ProTable
                actionRef={this.tableRef}
                toolBarRender={() => (
                    <PermActions>
                        <Button perm='git-credential:save' type='primary' onClick={this.handleAdd}>
                            <PlusOutlined/> 新增
                        </Button>
                    </PermActions>
                )}
                request={(params) => HttpClient.get('admin/git-credential/page', params)}
                columns={this.columns}
                searchFormRender={() => (
                    <Form.Item label='关键字' name='searchText'>
                        <Input/>
                    </Form.Item>
                )}
            />

            <Modal title='Git凭据'
                   open={this.state.formOpen}
                   onOk={() => this.formRef.current.submit()}
                   onCancel={() => this.setState({formOpen: false})}
                   destroyOnHidden
            >
                <Form ref={this.formRef} labelCol={{flex: '120px'}}
                      initialValues={this.state.formValues} onFinish={this.onFinish}>
                    <Form.Item name='id' noStyle></Form.Item>

                    <Form.Item label='名称' name='name'>
                        <Input placeholder='如 Gitee'/>
                    </Form.Item>

                    <Form.Item label='仓库地址前缀' name='url' rules={[{required: true, message: '请输入仓库地址前缀'}]}
                               tooltip='按前缀匹配，如 https://gitee.com'>
                        <Input placeholder='https://gitee.com'/>
                    </Form.Item>

                    <Form.Item label='用户名' name='username'>
                        <Input/>
                    </Form.Item>

                    <Form.Item label='密码/令牌' name='password'
                               tooltip={this.state.formValues.passwordMasked ? '当前已设置密码，留空表示不修改' : ''}>
                        <Input.Password autoComplete='new-password'
                                        placeholder={this.state.formValues.passwordMasked ? '******（留空不修改）' : ''}/>
                    </Form.Item>
                </Form>
            </Modal>
        </Page>
    }
}
