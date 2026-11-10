import {PlusOutlined, SearchOutlined} from '@ant-design/icons'
import {Form, Input, Modal, Select, Typography} from 'antd'
import React from 'react'
import {HttpClient, Page, PageUtils, PermActions, ProTable} from '@jiangood/open-admin'

/**
 * 工具 - 主机 Agent：管理进入主机终端的「Agent 配置」。
 * <p>
 * 一条配置绑定一台主机，保存期望使用的 agent 镜像 tag 与挂载目录；
 * 行操作「终端」打开独立页面，真正部署辅助容器仍需要 host:save，终端需要 host:cli。
 * 部署或切换目录成功后，后端会把实际使用的 tag / 目录回写到配置里。
 */
export default class extends React.Component {

    state = {
        hostOptions: [],
        formOpen: false,
        formValues: {},
    }

    formRef = React.createRef()
    tableRef = React.createRef()

    componentDidMount() {
        this.loadHostOptions()
    }

    loadHostOptions = () => {
        HttpClient.get('admin/host/options', null, {toastError: false})
            .then(rs => this.setState({hostOptions: rs.data || []}))
            .catch(() => {
            })
    }

    /** hostId -> 主机名，列表直接展示名字，避免每行再查一次主机接口 */
    hostNameOf = hostId => {
        const option = this.state.hostOptions.find(o => String(o.value) === String(hostId))
        return option ? option.label : (hostId || '-')
    }

    columns = [
        {
            title: '名称',
            dataIndex: 'name',
        },
        {
            title: '主机',
            dataIndex: 'hostId',
            render: hostId => this.hostNameOf(hostId),
        },
        {
            title: '镜像 Tag',
            dataIndex: 'imageTag',
            width: 140,
            render: v => v || 'latest',
        },
        {
            title: '挂载目录',
            dataIndex: 'fileRoot',
            width: 240,
            render: v => v || '-',
        },
        {
            title: '备注',
            dataIndex: 'remark',
            render: v => v
                ? <Typography.Text ellipsis={{tooltip: v}} style={{maxWidth: 260}}>{v}</Typography.Text>
                : '-',
        },
        {
            title: '更新时间',
            dataIndex: 'updateTime',
            width: 165,
        },
        {
            title: '操作',
            width: 180,
            render: (_, record) => (
                <PermActions size='small' actions={[
                    {
                        label: '终端', perm: 'host:cli',
                        onClick: () => PageUtils.open('/host-agent/view?id=' + record.id),
                    },
                    {label: '修改', perm: 'host-agent:save', onClick: () => this.handleEdit(record)},
                    {
                        label: '删除', perm: 'host-agent:delete',
                        confirm: `确定删除 Agent 配置「${record.name}」？目标主机上已运行的 docker-admin-agent 容器不会被删除`,
                        onClick: () => this.handleDelete(record),
                    },
                ]}/>
            ),
        },
    ]

    handleAdd = () => {
        this.loadHostOptions()
        const {hostOptions} = this.state
        this.setState({
            formOpen: true,
            formValues: {
                imageTag: 'latest',
                fileRoot: '/home',
                // 常用场景是给还没配置过的主机新增，选中第一台即可少点一次
                hostId: hostOptions.length === 1 ? hostOptions[0].value : undefined,
            },
        })
    }

    handleEdit = record => {
        this.loadHostOptions()
        this.setState({formOpen: true, formValues: {...record}})
    }

    onFinish = values => {
        HttpClient.post('admin/host-agent-config/save', values).then(() => {
            this.setState({formOpen: false})
            this.tableRef.current.reload()
        })
    }

    handleDelete = record => {
        HttpClient.postForm('admin/host-agent-config/delete', {id: record.id}).then(() => {
            this.tableRef.current.reload()
        })
    }

    render() {
        const {formOpen, formValues, hostOptions} = this.state
        const isCreate = !formValues.id

        return <Page padding>
            <ProTable
                actionRef={this.tableRef}
                searchInline
                searchFormRender={() => (
                    <Form.Item name='searchText' noStyle>
                        <Input prefix={<SearchOutlined/>} placeholder='搜索名称 / 备注' allowClear/>
                    </Form.Item>
                )}
                toolBarRightRender={() => (
                    <PermActions actions={[
                        {
                            label: '新增', perm: 'host-agent:save', type: 'primary',
                            icon: <PlusOutlined/>, onClick: this.handleAdd,
                        },
                    ]}/>
                )}
                request={(params) => HttpClient.get('admin/host-agent-config/page', params)}
                columns={this.columns}
            />

            <Modal title={isCreate ? '新增 Agent 配置' : '修改 Agent 配置'}
                   width={560}
                   open={formOpen}
                   onOk={() => this.formRef.current.submit()}
                   onCancel={() => this.setState({formOpen: false})}
                   destroyOnHidden>
                <Form ref={this.formRef} initialValues={formValues} labelCol={{flex: '100px'}}
                      onFinish={this.onFinish}>
                    <Form.Item name='id' noStyle/>

                    <Form.Item label='名称' name='name'
                               tooltip='留空默认使用主机名'>
                        <Input placeholder='如 生产环境' maxLength={100}/>
                    </Form.Item>

                    <Form.Item label='主机' name='hostId'
                               rules={[{required: true, message: '请选择主机'}]}
                               tooltip='一台主机只能有一条 Agent 配置'>
                        <Select options={hostOptions} showSearch optionFilterProp='label'
                                placeholder='请选择主机'/>
                    </Form.Item>

                    <Form.Item label='镜像 Tag' name='imageTag'
                               tooltip='镜像地址在「系统管理 - 参数设置 - 主机辅助容器」维护（不含 tag）；留空表示 latest，生产建议固定版本 tag'>
                        <Input placeholder='latest' maxLength={128}/>
                    </Form.Item>

                    <Form.Item label='挂载目录' name='fileRoot'
                               tooltip='只接受 Docker daemon 可见的 Linux 绝对路径；留空沿用主机已保存的目录（首次 /home）'>
                        <Input placeholder='/home' maxLength={512}/>
                    </Form.Item>

                    <Form.Item label='备注' name='remark'>
                        <Input.TextArea rows={3} maxLength={500} showCount
                                        placeholder='用途、负责人等，可留空'/>
                    </Form.Item>
                </Form>
            </Modal>
        </Page>
    }

}
