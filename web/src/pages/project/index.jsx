import {PlusOutlined} from '@ant-design/icons'
import {Button, Form, Input, Modal, Popconfirm, Select} from 'antd'
import React from 'react'

import {
    PermActions,
    FieldOrgTreeSelect,
    HttpClient,
    Page,
    PageUtils,
    PermUtils,
    ProTable
} from "@jiangood/open-admin"

import FieldGitRepository from '../../components/FieldGitRepository'
import LinkButton from '../../components/LinkButton'


export default class extends React.Component {

    state = {
        formValues: {},
        formOpen: false,
        // 可选目标注册中心，供项目绑定（为空使用系统默认注册中心）
        registryOptions: [],
        // 系统默认注册中心 id，新建项目时默认选中
        defaultRegistryId: '',
    }

    formRef = React.createRef()
    tableRef = React.createRef()

    componentDidMount() {
        // 无注册中心查看权限时静默跳过，避免报错；此时下拉只剩「系统默认」
        if (PermUtils.hasPermission('registry:view')) {
            HttpClient.get('admin/registry/options').then(rs => this.setState({registryOptions: rs.data || []}))
            // info 返回的即「系统默认」：配置了默认就用默认，未配置但只有一条时回退到该条
            HttpClient.get('admin/registry/info').then(rs => this.setState({defaultRegistryId: rs.data?.id || ''}))
        }
    }

    /**
     * 目标注册中心选项：首项为「系统默认」（空值），
     * 用显式空值而非 allowClear，避免清除后值为 undefined 而被后端 X-Body-Fields 过滤、无法真正清空。
     */
    registrySelectOptions = () => [{value: '', label: '系统默认'}, ...this.state.registryOptions]

    // 用户是否手动改过项目名；改过之后不再随代码仓库自动覆盖
    nameTouched = false

    columns = [


        {
            title: '项目',
            dataIndex: 'name',
            render: (name, row) => {
                return <LinkButton
                    onClick={() => PageUtils.open('/project/view?id=' + row.id, "项目-" + name)}>{name}</LinkButton>
            },

        },
        {
            title: '镜像地址',
            dataIndex: 'imageUrl',
        },
        {
            title: '代码仓库',
            dataIndex: 'gitUrl',
        },
        {
            title: '备注',
            dataIndex: 'remark',
        },


        {
            title: 'dockerfile',
            dataIndex: 'dockerfile',
        },


        {
            title: '组织机构',
            dataIndex: ['sysOrg', 'name'],

        },

        {
            title: '操作',
            dataIndex: 'option',
            valueType: 'option',
            render: (_, record) => (
                <PermActions>
                    <Button size='small' perm='project:save' onClick={() => this.handleEdit(record)}> 修改 </Button>
                    <Popconfirm perm='project:delete' title='是否确定删除项目'
                                onConfirm={() => this.handleDelete(record)}>
                        <Button size='small'>删除</Button>
                    </Popconfirm>
                </PermActions>
            ),
        },
    ]



    handleAdd = () => {
        this.nameTouched = false
        // 新建时默认选中系统默认注册中心；没有默认值则不预选，留空（placeholder 提示系统默认）
        this.setState({formOpen: true, formValues: {registryId: this.state.defaultRegistryId || undefined}})
    }

    handleEdit = record => {
        // 已有项目的项目名视为用户设定，不随代码仓库自动覆盖
        this.nameTouched = true
        this.setState({formOpen: true, formValues: record})
    }

    /**
     * 由代码仓库地址推导项目名：取仓库路径最后一段，去掉 .git 后缀，
     * 转小写并将非法字符替换为短横线（镜像名需以小写字母开头）。
     */
    deriveProjectName = gitUrl => {
        if (!gitUrl) {
            return ''
        }
        const path = String(gitUrl).trim().replace(/\/+$/, '').replace(/\.git$/i, '')
        const segment = path.split(/[\\/:]+/).filter(Boolean).pop() || ''
        return segment.toLowerCase()
            .replace(/[^a-z0-9._-]+/g, '-')
            .replace(/^[^a-z]+/, '')
    }

    onValuesChange = changedValues => {
        if ('name' in changedValues) {
            this.nameTouched = true
            return
        }
        if ('gitUrl' in changedValues && !this.nameTouched) {
            this.formRef.current?.setFieldsValue({name: this.deriveProjectName(changedValues.gitUrl)})
        }
    }


    onFinish = values => {
        HttpClient.post('admin/project/save', values).then(rs => {
            this.setState({formOpen: false})
            this.tableRef.current.reload()
        })
    }


    handleDelete = record => {
        HttpClient.postForm('admin/project/delete', {id: record.id}).then(rs => {
            this.tableRef.current.reload()
        })
    }

    render() {
        return <Page padding>
            <ProTable
                actionRef={this.tableRef}
                toolBarRender={() => {
                    return <PermActions>
                        <Button perm='project:save' type='primary' onClick={this.handleAdd}>
                            <PlusOutlined/> 新增
                        </Button>
                    </PermActions>
                }}
                searchFormRender={() => (
                    <Form.Item label='组织机构' name='orgId'>
                        <FieldOrgTreeSelect placeholder='全部组织机构'/>
                    </Form.Item>
                )}
                request={(params) => HttpClient.get('admin/project/page', params)}
                columns={this.columns}
            >
            </ProTable>


            <Modal title='项目信息'
                   open={this.state.formOpen}
                   onOk={() => this.formRef.current.submit()}
                   onCancel={() => this.setState({formOpen: false})}
                   destroyOnHidden

                   width={600}

            >

                <Form ref={this.formRef} labelCol={{flex: '120px'}}
                      initialValues={this.state.formValues}
                      onValuesChange={this.onValuesChange}
                      onFinish={this.onFinish}>
                    <Form.Item name='id' noStyle></Form.Item>
                    <Form.Item label='代码仓库' name='gitUrl'
                               rules={[{required: true, message: '请输入代码仓库'}]}
                               tooltip='可直接输入地址，也可从「设置-代码源」的仓库列表中选择；选择后自动填入项目名'>
                        <FieldGitRepository/>
                    </Form.Item>

                    <Form.Item label='项目名' name='name' rules={[{required: true}]}
                               help='不能包含中文，小写字母开头；默认取代码仓库名，决定镜像地址'>
                        <Input/>
                    </Form.Item>

                    <Form.Item label='dockerfile' name='dockerfile' rules={[{required: true}]}
                               initialValue='Dockerfile'
                               help='相对代码仓库根目录的路径，可位于子目录，如 docker/Dockerfile'>
                        <Input/>
                    </Form.Item>
                    <Form.Item label='构建参数' name='buildArg' help='格式: key=value&key2=value2'>
                        <Input/>
                    </Form.Item>

                    <Form.Item label='目标注册中心' name='registryId'
                               tooltip='本项目构建产物推送的目标注册中心，配置一次即可，手动构建与 Webhook 触发都沿用'>
                        <Select options={this.registrySelectOptions()} showSearch
                                optionFilterProp='label' placeholder='系统默认注册中心'/>
                    </Form.Item>


                    <Form.Item label='所属组织' name={['sysOrg', 'id']}>
                        <FieldOrgTreeSelect/>
                    </Form.Item>

                    <Form.Item label='备注' name='remark'>
                        <Input/>
                    </Form.Item>
                </Form>
            </Modal>
        </Page>


    }
}
