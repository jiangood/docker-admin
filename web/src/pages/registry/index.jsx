import {Alert, Button, Form, Input, Modal, Spin, Typography, message} from 'antd'
import {PlusOutlined, SearchOutlined} from '@ant-design/icons'
import React from 'react'
import {HttpClient, Page, PermActions, PermUtils, ProTable} from '@jiangood/open-admin'

/** 镜像注册中心实例管理。 */
export default class extends React.Component {

    tableRef = React.createRef()
    formRef = React.createRef()

    state = {
        modalOpen: false,
        editing: false,
        formValues: {},
        pwdOpen: false,
        pwdLoading: false,
        pwdRow: null,
        pwdValue: '',
        testOpen: false,
        testRow: null,
        testing: false,
        testResult: null,
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
            .then(rs => {
                message.success(rs.message || '保存成功')
                this.setState({modalOpen: false})
                this.tableRef.current?.reload()
            }).finally(hide)
    }

    remove = id => {
        HttpClient.post('admin/registry/delete?id=' + encodeURIComponent(id))
            .then(rs => {
                message.success(rs.message || '删除成功')
                this.tableRef.current?.reload()
            })
    }

    // 列表只返回打码密码，明文按需单独请求（后端另有 registry:password 权限）
    viewPassword = row => {
        this.setState({pwdOpen: true, pwdLoading: true, pwdRow: row, pwdValue: ''})
        HttpClient.get('admin/registry/password?id=' + encodeURIComponent(row.id))
            .then(rs => this.setState({pwdValue: rs.data || '', pwdLoading: false}))
            .catch(() => this.setState({pwdLoading: false}))
    }

    // 打开弹窗即测一次；失败原因由后端放在结果里返回，弹窗里完整展示
    openTest = row => {
        this.setState({testOpen: true, testRow: row, testing: false, testResult: null})
        this.runTest(row)
    }

    runTest = (row = this.state.testRow) => {
        if (!row) return
        this.setState({testing: true, testResult: null})
        HttpClient.get('admin/registry/test?id=' + encodeURIComponent(row.id))
            .then(rs => this.setState({testResult: rs.data || null}))
            .catch(() => {
            })
            .finally(() => this.setState({testing: false}))
    }

    getColumns = () => {
        const canViewPassword = PermUtils.hasPermission('registry:password')

        return [
            {title: '地址', dataIndex: 'url', width: 280},
            {title: '命名空间', dataIndex: 'namespace', width: 180},
            {title: '用户名', dataIndex: 'username', width: 180, render: v => v || '-'},
            {
                title: '凭据',
                dataIndex: 'passwordMasked',
                width: 100,
                render: v => v ? '已配置' : '未配置',
            },
            {title: '更新时间', dataIndex: 'updateTime', width: 165},
            {
                title: '操作',
                width: 260,
                render: (_, row) => {
                    const actions = [
                        {label: '测试', perm: 'registry:test', onClick: () => this.openTest(row)},
                    ]
                    if (canViewPassword && row.passwordMasked) {
                        actions.push({label: '查看密码', onClick: () => this.viewPassword(row)})
                    }
                    actions.push(
                        {label: '编辑', perm: 'registry:save', onClick: () => this.edit(row)},
                        {
                            label: '删除', perm: 'registry:save', danger: true,
                            confirm: '确定删除该注册中心？默认注册中心需先到「系统管理 - 系统参数」中更换，已被项目使用的注册中心需先解除绑定。',
                            onClick: () => this.remove(row.id),
                        },
                    )
                    return <PermActions size='small' actions={actions}/>
                },
            },
        ]
    }

    render() {
        const {modalOpen, editing, formValues, pwdOpen, pwdLoading, pwdRow, pwdValue,
            testOpen, testRow, testing, testResult} = this.state

        return <Page padding>
            <ProTable
                actionRef={this.tableRef}
                searchInline
                toolBarRightRender={() => (
                    <PermActions actions={[
                        {
                            label: '新增注册中心', perm: 'registry:save', type: 'primary',
                            icon: <PlusOutlined/>, onClick: this.create,
                        },
                    ]}/>
                )}
                searchFormRender={() => (
                    <Form.Item name='searchText' noStyle>
                        <Input prefix={<SearchOutlined/>} placeholder='搜索地址 / 命名空间 / 用户名' allowClear/>
                    </Form.Item>
                )}
                request={(params) => HttpClient.get('admin/registry/page', params)}
                columns={this.getColumns()}
            />

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

            <Modal title='测试注册中心' open={testOpen} destroyOnHidden width={560}
                   onCancel={() => this.setState({testOpen: false})}
                   footer={[
                       <Button key='close' onClick={() => this.setState({testOpen: false})}>关闭</Button>,
                       <Button key='test' type='primary' loading={testing}
                               onClick={() => this.runTest()}>重新测试</Button>,
                   ]}>
                <div style={{marginBottom: 12}}>
                    <Typography.Text strong>{testRow?.url || ''}</Typography.Text>
                    <Typography.Text type='secondary' style={{marginLeft: 8}}>
                        {testRow?.namespace}{testRow?.username ? '，账号 ' + testRow.username : '，匿名'}
                    </Typography.Text>
                </div>
                {testing && <div style={{marginBottom: 12}}><Spin/> <Typography.Text type='secondary'>正在测试…</Typography.Text></div>}
                {testResult &&
                    <Alert type={testResult.ok ? 'success' : 'error'} showIcon
                           message={testResult.ok ? '测试通过' : '测试失败'}
                           description={<div>
                               <div>{testResult.message}</div>
                               <Typography.Text type='secondary'>耗时 {testResult.elapsedMs} ms</Typography.Text>
                           </div>}/>}
            </Modal>

            <Modal title='查看密码' open={pwdOpen} destroyOnHidden
                   onCancel={() => this.setState({pwdOpen: false})}
                   footer={<Button onClick={() => this.setState({pwdOpen: false})}>关闭</Button>}>
                <div style={{marginBottom: 8}}>
                    <Typography.Text type='secondary'>
                        {pwdRow ? pwdRow.url + '/' + pwdRow.namespace : ''}
                    </Typography.Text>
                </div>
                {pwdLoading
                    ? <Spin/>
                    : pwdValue
                        ? <Typography.Text copyable code style={{wordBreak: 'break-all'}}>{pwdValue}</Typography.Text>
                        : <Typography.Text type='secondary'>未配置密码</Typography.Text>}
            </Modal>
        </Page>
    }
}
