import {PlusOutlined} from '@ant-design/icons'
import {Button, InputNumber, Popconfirm, Modal, Form, Input} from 'antd'
import React from 'react'
import {ButtonList, HttpUtils, Page, ProTable} from "@jiangood/admin-spring-boot-starter";


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
            title: '编码',
            dataIndex: 'code',
        },
        {
            title: '排序',
            dataIndex: 'seq',
        },


        {
            title: '操作',
            dataIndex: 'option',
            render: (_, record) => (
                <ButtonList>
                    <Button size='small' perm='appGroup:save' onClick={() => this.handleEdit(record)}>编辑</Button>
                    <Popconfirm perm='appGroup:delete' title='是否确定删除应用分组'
                                onConfirm={() => this.handleDelete(record)}>
                        <Button size='small'>删除</Button>
                    </Popconfirm>
                </ButtonList>
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
        HttpUtils.post('admin/appGroup/save', values).then(rs => {
            this.setState({formOpen: false})
            this.tableRef.current.reload()
        })
    }


    handleDelete = record => {
        HttpUtils.get('admin/appGroup/delete', {id: record.id}).then(rs => {
            this.tableRef.current.reload()
        })
    }

    render() {
        return <Page padding>
            <ProTable
                actionRef={this.tableRef}
                toolBarRender={(params, {selectedRows, selectedRowKeys}) => {
                    return <ButtonList>
                        <Button perm='appGroup:save' type='primary' onClick={this.handleAdd}>
                            <PlusOutlined/> 新增
                        </Button>
                    </ButtonList>
                }}
                request={(params) => HttpUtils.get('admin/appGroup/page', params)}
                columns={this.columns}

            >


            </ProTable>

            <Modal title='应用分组'
                   open={this.state.formOpen}
                   onOk={() => this.formRef.current.submit()}
                   onCancel={() => this.setState({formOpen: false})}
                   destroyOnHidden
                   maskClosable={false}
                   width={800}
            >

                <Form ref={this.formRef} labelCol={{flex: '100px'}}
                      initialValues={this.state.formValues}
                      onFinish={this.onFinish}
                >
                    <Form.Item name='id' noStyle></Form.Item>

                    <Form.Item label='名称' name='name' rules={[{required: true}]}>
                        <Input/>
                    </Form.Item>
                    <Form.Item label='编码' name='code' rules={[{required: true}]}>
                        <Input/>
                    </Form.Item>
                    <Form.Item label='排序' name='seq' rules={[{required: true}]}>
                        <InputNumber/>
                    </Form.Item>


                </Form>
            </Modal>
        </Page>


    }
}


