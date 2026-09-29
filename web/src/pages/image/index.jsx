import {PlusOutlined} from '@ant-design/icons'
import {Button, Form, Input, Modal, Popconfirm, Space, Splitter} from 'antd'
import React from 'react'

import {
    PermActions,
    FieldOrgTreeSelect,
    HttpClient,
    OrgTree,
    Page,
    PageUtils,
    ProTable
} from "@jiangood/open-admin"

import CodeSourceProjectPicker from './CodeSourceProjectPicker'


export default class extends React.Component {

    state = {
        formValues: {},
        formOpen: false,
        pickerOpen: false,


        selectedOrgId: null

    }

    formRef = React.createRef()
    tableRef = React.createRef()

    columns = [


        {
            title: '镜像',
            dataIndex: 'name',
            render: (name, row) => {
                return <a onClick={() => PageUtils.open('/image/view?id=' + row.id, "镜像-" + name)}>{name}</a>
            },

        },
        {
            title: '中文名称',
            dataIndex: 'cnName',
            sorter: true,
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
                    <Button size='small' perm='image:save' onClick={() => this.handleEdit(record)}> 修改 </Button>
                    <Popconfirm perm='image:delete' title='是否确定删除镜像'
                                onConfirm={() => this.handleDelete(record)}>
                        <Button size='small'>删除</Button>
                    </Popconfirm>
                </PermActions>
            ),
        },
    ]



    handleAdd = () => {
        this.setState({formOpen: true, formValues: {}})
    }

    handleEdit = record => {
        this.setState({formOpen: true, formValues: record})
    }


    onFinish = values => {
        HttpClient.post('admin/image/save', values).then(rs => {
            this.setState({formOpen: false})
            this.tableRef.current.reload()
        })
    }


    handleDelete = record => {
        HttpClient.postForm('admin/image/delete', {id: record.id}).then(rs => {
            this.tableRef.current.reload()
        })
    }

    render() {
        return <Page padding>
            <Splitter>
                <Splitter.Panel size={250}>
                    <OrgTree onChange={(v) => {
                        this.setState({selectedOrgId: v}, () => {
                            this.tableRef.current.reload()
                        })

                    }}/>

                </Splitter.Panel>
                <Splitter.Panel style={{paddingLeft: 16}}>
                    <ProTable
                        actionRef={this.tableRef}
                        toolBarRender={() => {
                            return <PermActions>
                                <Button perm='image:save' type='primary' onClick={this.handleAdd}>
                                    <PlusOutlined/> 新增
                                </Button>
                            </PermActions>
                        }}
                        request={(params) => {
                            params.orgId = this.state.selectedOrgId
                            return HttpClient.get('admin/image/page', params);
                        }}
                        columns={this.columns}
                        showToolbarSearch
                    >
                    </ProTable>
                </Splitter.Panel>
            </Splitter>


            <Modal title='镜像信息'
                   open={this.state.formOpen}
                   onOk={() => this.formRef.current.submit()}
                   onCancel={() => this.setState({formOpen: false})}
                   destroyOnHidden

                   width={600}

            >

                <Form ref={this.formRef} labelCol={{flex: '120px'}}
                      initialValues={this.state.formValues}
                      onFinish={this.onFinish}>
                    <Form.Item name='id' noStyle></Form.Item>
                    <Form.Item label='镜像名' name='name' rules={[{required: true}]} help='不能包含中文，小写字母开头'>
                        <Input/>
                    </Form.Item>

                    <Form.Item label='中文名称' name='cnName'>
                        <Input/>
                    </Form.Item>

                    <Form.Item label='代码仓库' required>
                        <Space.Compact style={{width: '100%'}}>
                            <Form.Item name='gitUrl' noStyle rules={[{required: true, message: '请输入代码仓库'}]}>
                                <Input/>
                            </Form.Item>
                            <Button onClick={() => this.setState({pickerOpen: true})}>从代码源选择</Button>
                        </Space.Compact>
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

            <CodeSourceProjectPicker
                open={this.state.pickerOpen}
                onCancel={() => this.setState({pickerOpen: false})}
                onSelect={url => {
                    this.formRef.current.setFieldsValue({gitUrl: url})
                    this.setState({pickerOpen: false})
                }}
            />
        </Page>


    }
}
