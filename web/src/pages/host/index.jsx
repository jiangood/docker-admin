import {PlusOutlined} from '@ant-design/icons'
import {Button, Form, Input, Modal, Popconfirm} from 'antd'
import React from 'react'

import {ButtonList, FieldBoolean, HttpUtils, PageUtils, ProTable} from "@jiangood/springboot-admin-starter"
import {history} from "umi";


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
            title: '构建节点',
            dataIndex: 'isRunner',
            valueType: 'boolean',
            render(v) {
                return v ? '是':'否';
            }

        },


        {
            title: 'dockerHost',
            dataIndex: 'dockerHost',

        },


        {
            title: '备注',
            dataIndex: 'remark',
        },
        {
            title: '操作',
            dataIndex: 'option',
            valueType: 'option',
            render: (_, record) => (
                <ButtonList>
                    <a perm='host:save' onClick={() => this.handleEdit(record)}> 修改 </a>
                    <Popconfirm perm='host:delete' title='是否确定删除主机' onConfirm={() => this.handleDelete(record)}>
                        <a>删除</a>
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
        HttpUtils.post('admin/host/save', values).then(rs => {
            this.setState({formOpen: false})
            this.tableRef.current.reload()
        })
    }


    handleDelete = record => {
        HttpUtils.postForm('admin/host/delete', {id: record.id}).then(rs => {
            this.tableRef.current.reload()
        })
    }

    render() {
        return <>
            <ProTable
                actionRef={this.tableRef}
                toolBarRender={() => {
                    return <ButtonList>
                        <Button perm='host:save' type='primary' onClick={this.handleAdd}>
                            <PlusOutlined/> 新增
                        </Button>
                    </ButtonList>
                }}
                search={false}
                request={(jobParamDescs, sort) => HttpUtils.get('admin/host/page', jobParamDescs, sort)}
                columns={this.columns}
                rowKey='id'
            />

            <Modal title='主机'
                   open={this.state.formOpen}
                   onOk={() => this.formRef.current.submit()}
                   onCancel={() => this.setState({formOpen: false})}
                   destroyOnHidden
            >

                <Form ref={this.formRef} labelCol={{flex: '100px'}}
                      initialValues={this.state.formValues}
                      onFinish={this.onFinish}>
                    <Form.Item name='id' noStyle></Form.Item>

                    <Form.Item label='名称' name='name' rules={[{required: true}]}>
                        <Input/>
                    </Form.Item>
                    <Form.Item label='构建节点' name='isRunner' rules={[{required: true}]}>
                        <FieldBoolean/>
                    </Form.Item>

                    <Form.Item label='dockerHost' name='dockerHost' rules={[{required: true}]}
                               tooltip={<div style={{width: 500}}>linux本机：unix:///var/run/docker.sock <br/>IP方式：tcp://192.168.1.2:2375
                               </div>}>
                        <Input/>
                    </Form.Item>

                    <Form.Item label='备注' name='remark' rules={[{required: true}]}>
                        <Input/>
                    </Form.Item>

                </Form>
            </Modal>
        </>


    }
}



