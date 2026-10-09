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

/** 访问方式对应的打码字段，用于判断某种方式是否已配置凭据 */
const MASKED_FIELD = {
    PASSWORD: 'passwordMasked',
    TOKEN: 'tokenMasked',
    SSH_KEY: 'privateKeyMasked',
}

const SSH_URL_PLACEHOLDER = 'git@your-git-host.com:group/repo.git'

/**
 * 代码源：托管平台（GitLab/Gitee/GitHub/Gitea）或自定义 git 仓库，按地址主机匹配；
 * 访问方式决定用账号密码、访问令牌还是 SSH 私钥。
 */
export default class extends React.Component {

    state = {
        formValues: {},
        formOpen: false,
        type: 'CUSTOM',
        authType: 'TOKEN',
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
            title: '访问方式',
            dataIndex: 'authType',
            render: v => DictUtils.dictLabel('codeSourceAuthType', v) || v,
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
            title: '凭据',
            dataIndex: 'credential',
            hideInSearch: true,
            render: (_, record) => record[MASKED_FIELD[record.authType]] ? '已配置' : '未配置',
        },
        {
            title: '操作',
            dataIndex: 'option',
            valueType: 'option',
            width: 120,
            render: (_, record) => (
                <PermActions>
                    <Button size='small' perm='code-source:save' onClick={() => this.handleEdit(record)}>修改</Button>
                    <Popconfirm perm='code-source:delete' title='是否确定删除该代码源' onConfirm={() => this.handleDelete(record)}>
                        <Button size='small'>删除</Button>
                    </Popconfirm>
                </PermActions>
            ),
        },
    ]

    handleAdd = () => {
        this.setState({formOpen: true, formValues: {}, type: 'CUSTOM', authType: 'TOKEN'})
    }

    handleEdit = record => {
        this.setState({
            formOpen: true,
            // 敏感字段不回显，留空表示不修改；打码字段（*Masked）用于提示是否已配置
            formValues: {...record, password: '', token: '', privateKey: '', privateKeyPassphrase: ''},
            type: record.type || 'CUSTOM',
            authType: record.authType || 'TOKEN',
        })
    }

    /** 该方式是否已配置凭据（已配置时留空表示不修改，故不必填） */
    isConfigured = authType => !!this.state.formValues[MASKED_FIELD[authType]]

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

    renderCredentialItems() {
        const {authType} = this.state
        const configured = this.isConfigured(authType)
        if (authType === 'PASSWORD') {
            return <>
                <Form.Item label='用户名' name='username' rules={[{required: true, message: '请输入用户名'}]}>
                    <Input placeholder='登录代码仓库的账号'/>
                </Form.Item>
                <Form.Item label='密码' name='password'
                           rules={[{required: !configured, message: '请输入密码'}]}
                           tooltip={configured ? '当前已设置密码，留空表示不修改' : '登录代码仓库的密码'}>
                    <Input.Password autoComplete='new-password'
                                    placeholder={configured ? '******（留空不修改）' : ''}/>
                </Form.Item>
            </>
        }
        if (authType === 'SSH_KEY') {
            return <>
                <Form.Item label='SSH 私钥' name='privateKey'
                           rules={[{required: !configured, message: '请粘贴 SSH 私钥'}]}
                           tooltip={configured ? '当前已设置私钥，留空表示不修改' : 'OpenSSH 格式私钥，仓库地址需为 ssh:// 或 git@host:path'}>
                    <Input.TextArea rows={6} className='mono'
                                    placeholder={configured ? '******（留空不修改）'
                                        : '-----BEGIN OPENSSH PRIVATE KEY-----\n...'}/>
                </Form.Item>
                <Form.Item label='私钥口令' name='privateKeyPassphrase'
                           tooltip={this.state.formValues.privateKeyPassphraseMasked ? '当前已设置口令，留空表示不修改' : '私钥未加密时留空'}>
                    <Input.Password autoComplete='new-password'
                                    placeholder={this.state.formValues.privateKeyPassphraseMasked ? '******（留空不修改）' : ''}/>
                </Form.Item>
            </>
        }
        return <>
            <Form.Item label='用户名' name='username'
                       tooltip='使用访问令牌时，用户名可填任意非空值，留空则默认 oauth2'>
                <Input placeholder='oauth2（可留空）'/>
            </Form.Item>
            <Form.Item label='访问令牌' name='token'
                       rules={[{required: !configured, message: '请输入访问令牌'}]}
                       tooltip={configured ? '当前已设置令牌，留空表示不修改'
                           : 'GitLab/GitHub 等平台的 Personal Access Token'}>
                <Input.Password autoComplete='new-password'
                                placeholder={configured ? '******（留空不修改）' : ''}/>
            </Form.Item>
        </>
    }

    render() {
        const authType = this.state.authType
        const ssh = authType === 'SSH_KEY'

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
                          if ('authType' in changed) {
                              this.setState({authType: changed.authType})
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

                    <Form.Item label='访问方式' name='authType' rules={[{required: true, message: '请选择访问方式'}]}>
                        <FieldDictSelect typeCode='codeSourceAuthType'/>
                    </Form.Item>

                    <Form.Item label='地址' name='url' rules={[{required: true, message: '请输入地址'}]}
                               tooltip='平台地址，按主机匹配，如 https://gitlab.com'>
                        <Input placeholder={ssh ? SSH_URL_PLACEHOLDER : (URL_PLACEHOLDER[this.state.type] || URL_PLACEHOLDER.CUSTOM)}/>
                    </Form.Item>

                    {this.renderCredentialItems()}
                </Form>
            </Modal>
        </Page>
    }
}
