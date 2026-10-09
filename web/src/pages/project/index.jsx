import {PlusOutlined} from '@ant-design/icons'
import {Button, Form, Input, Modal, Popconfirm} from 'antd'
import React from 'react'

import {
    PermActions,
    FieldOrgTreeSelect,
    HttpClient,
    Page,
    PageUtils,
    ProTable
} from "@jiangood/open-admin"

import FieldGitRepository from '../../components/FieldGitRepository'
import LinkButton from '../../components/LinkButton'


export default class extends React.Component {

    state = {
        formValues: {},
        formOpen: false,
    }

    formRef = React.createRef()
    tableRef = React.createRef()

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
        this.setState({formOpen: true, formValues: {}})
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
                               initialValue='Dockerfile'>
                        <Input/>
                    </Form.Item>
                    <Form.Item label='构建参数' name='buildArg' help='格式: key=value&key2=value2'>
                        <Input/>
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
