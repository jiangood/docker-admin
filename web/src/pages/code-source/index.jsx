import {PlusOutlined} from '@ant-design/icons'
import {Button, Form, Input, Modal, Popconfirm} from 'antd'
import React from 'react'

import {DictUtils, FieldDictSelect, HttpClient, Page, PermActions, ProTable} from '@jiangood/open-admin'

/** 各类型的默认地址提示 */
const URL_PLACEHOLDER = {
    GITLAB: 'https://gitlab.com',
    GITEE: 'https://gitee.com',
    GITHUB: 'https://github.com',
    GITEA: 'https://gitea.com',
    CUSTOM: 'https://your-git-host.com',
}

/**
 * 代码源：托管平台（GitLab/Gitee/GitHub/Gitea）或自定义 git 仓库，按地址主机匹配
 */
export default class extends React.Component {

    state = {
        formValues: {},
        formOpen: false,
        type: 'CUSTOM',
    }

    formRef = React.createRef()
    tableRef = React.createRef()

    columns = [
        {
            title: '名称',
            dataIndex: 'name',
        },
        {
            title: '类型',
            dataIndex: 'type',
            render: v => DictUtils.dictLabel('codeSourceType', v) || v,
        },
        {
            title: '地址',
            dataIndex: 'url',
        },
        {
            title: '用户名',
            dataIndex: 'username',
        },
        {
            title: '访问令牌',
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
                    <a perm='code-source:save' onClick={() => this.handleEdit(record)}> 修改 </a>
                    <Popconfirm perm='code-source:delete' title='是否确定删除该代码源' onConfirm={() => this.handleDelete(record)}>
                        <a>删除</a>
                    </Popconfirm>
                </PermActions>
            ),
        },
    ]

    handleAdd = () => {
        this.setState({formOpen: true, formValues: {}, type: 'CUSTOM'})
    }

    handleEdit = record => {
        this.setState({formOpen: true, formValues: {...record, password: ''}, type: record.type || 'CUSTOM'})
    }

    onFinish = values => {
        HttpClient.post('admin/code-source/save', values).then(rs => {
            this.setState({formOpen: false})
            this.tableRef.current.reload()
        })
    }

    handleDelete = record => {
        HttpClient.postForm('admin/code-source/delete', {id: record.id}).then(() => {
            this.tableRef.current.reload()
        })
    }

    render() {
        return <Page padding>
            <ProTable
                actionRef={this.tableRef}
                toolBarRender={() => (
                    <PermActions>
                        <Button perm='code-source:save' type='primary' onClick={this.handleAdd}>
                            <PlusOutlined/> 新增
                        </Button>
                    </PermActions>
                )}
                request={(params) => HttpClient.get('admin/code-source/page', params)}
                columns={this.columns}
                searchFormRender={() => (
                    <Form.Item label='关键字' name='searchText'>
                        <Input/>
                    </Form.Item>
                )}
            />

            <Modal title='代码源'
                   open={this.state.formOpen}
                   onOk={() => this.formRef.current.submit()}
                   onCancel={() => this.setState({formOpen: false})}
                   destroyOnHidden
            >
                <Form ref={this.formRef} labelCol={{flex: '120px'}}
                      initialValues={this.state.formValues}
                      onValuesChange={(changed) => {
                          if ('type' in changed) {
                              this.setState({type: changed.type})
                          }
                      }}
                      onFinish={this.onFinish}>
                    <Form.Item name='id' noStyle></Form.Item>

                    <Form.Item label='名称' name='name'>
                        <Input placeholder='如 公司 GitLab'/>
                    </Form.Item>

                    <Form.Item label='类型' name='type' rules={[{required: true, message: '请选择类型'}]}>
                        <FieldDictSelect typeCode='codeSourceType'/>
                    </Form.Item>

                    <Form.Item label='地址' name='url' rules={[{required: true, message: '请输入地址'}]}
                               tooltip='平台地址，按主机匹配，如 https://gitlab.com'>
                        <Input placeholder={URL_PLACEHOLDER[this.state.type] || URL_PLACEHOLDER.CUSTOM}/>
                    </Form.Item>

                    <Form.Item label='用户名' name='username'>
                        <Input/>
                    </Form.Item>

                    <Form.Item label='访问令牌/密码' name='password'
                               tooltip={this.state.formValues.passwordMasked ? '当前已设置令牌，留空表示不修改' : 'GitLab 等平台建议填写 Personal Access Token'}>
                        <Input.Password autoComplete='new-password'
                                        placeholder={this.state.formValues.passwordMasked ? '******（留空不修改）' : ''}/>
                    </Form.Item>
                </Form>
            </Modal>
        </Page>
    }
}
