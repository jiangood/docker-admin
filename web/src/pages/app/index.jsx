import {AutoComplete, Button, Form, Input, Menu, Modal, Splitter} from 'antd';
import React from 'react';
import ContainerStatus from "../../components/ContainerStatus";
import {
    PermActions,
    FieldOrgTreeSelect,
    FieldRemoteSelect,
    HttpClient,
    OrgTree,
    Page,
    PageUtils,
    ProTable
} from "@jiangood/open-admin";


export default class extends React.Component {


    columns = [
        {
            title: '应用名称',
            dataIndex: 'name',
            sorter: true,
            render: (name, row) => {
                return <a onClick={() => PageUtils.open('/app/view?id=' + row.id, '应用-' + name)}>{name}</a>
            }
        },
        {
            title: '中文名称',
            dataIndex: 'cnName',
            sorter: true,
        },


        {
            title: '版本',
            dataIndex: 'imageTag',
        },
        {
            title: '运行主机',
            dataIndex: ['host', 'name'],
            sorter: true,
        },
        {
            title: '状态',
            dataIndex: 'containerStatus',
            hideInForm: true,
            render: (_, row) => {
                return <ContainerStatus hostId={row.host?.id} appName={row.name}></ContainerStatus>
            }
        },
        {
            title: '标签',
            dataIndex: 'tag',
        },
        {
            title: '组织机构',
            dataIndex: ['sysOrg', 'name'],

        },

        {
            title: '最近更新',
            dataIndex: 'updateTime',
        },
        {
            title: '操作',
            dataIndex: 'option',
            valueType: 'option',
            render: (_, record) => (
                <PermActions>
                    <Button size='small' perm='app:save' onClick={() => this.handleEdit(record)}>修改</Button>
                </PermActions>
            ),
        },


    ];
    state = {
        hostId: null,
        selectedOrgId: null,
        deployVisible: false,
        deployImageVisible: false,
        editVisible: false,
        editValues: {},
        imageList: [],
    }



    reload = () => {
        this.tableRef.current.reload()
    }


    handleSave = value => {
        HttpClient.post('admin/app/save', value).then(() => {
            this.reload()
            this.setState({deployVisible: false})
        })
    }

    formRef = React.createRef()
    editFormRef = React.createRef()
    tableRef = React.createRef()
    handleAdd = () => {
        this.setState({deployVisible: true})
    }

    handleEdit = record => {
        this.setState({editVisible: true, editValues: record})
    }

    handleEditFinish = values => {
        HttpClient.post('admin/app/updateBaseInfo', values).then(() => {
            this.setState({editVisible: false})
            this.reload()
        })
    }



    render() {
        return (
            <Page padding>
                <Splitter>
                    <Splitter.Panel size={250}>
                        <OrgTree onChange={(v) => {
                            this.setState({selectedOrgId: v}, () => this.reload())
                        }}/>
                    </Splitter.Panel>
                    <Splitter.Panel style={{paddingLeft: 16}}>
                        <ProTable
                            actionRef={this.tableRef}
                            toolBarRender={() => [
                                <FieldRemoteSelect key="hostFilter" allowClear showSearch
                                                   url="admin/host/options" placeholder="过滤主机"
                                                   onChange={value => {
                                                       this.setState({hostId: value}, () => this.reload())
                                                   }}
                                />,
                                <Button type="primary"
                                        onClick={this.handleAdd}>
                                    新增
                                </Button>
                            ]}
                            request={(params) => {
                                params.hostId = this.state.hostId
                                params.orgId = this.state.selectedOrgId
                                return HttpClient.get('admin/app/list', params);
                            }}
                            columns={this.columns}
                            showToolbarSearch

                        />
                    </Splitter.Panel>
                </Splitter>
                <Modal title='新增应用' open={this.state.deployVisible} destroyOnHidden={true}
                       onOk={() => this.formRef.current.submit()}
                       onCancel={() => this.setState({deployVisible: false})}
                       width={800}
                >
                    <Form
                        layout='horizontal'
                        labelCol={{flex: '100px'}}
                        ref={this.formRef}
                        onFinish={this.handleSave}
                    >
                        <Form.Item name='name' label='应用名称' required rules={[{required: true}]}>
                            <Input/>
                        </Form.Item>

                        <Form.Item name='cnName' label='中文名称'>
                            <Input/>
                        </Form.Item>

                        <Form.Item name='imageUrl' label='镜像' required rules={[{required: true}]}>
                            <AutoComplete options={this.state.imageList} onSearch={this.loadImageList}></AutoComplete>
                        </Form.Item>


                        <Form.Item name='imageTag' label='版本' required rules={[{required: true}]}>
                            <Input placeholder='请输入版本'></Input>
                        </Form.Item>


                        <Form.Item name={['host', 'id']} label='部署主机' required rules={[{required: true}]}>
                            <FieldRemoteSelect showSearch url="admin/host/options"/>
                        </Form.Item>


                        <Form.Item label='所属组织' name={['sysOrg', 'id']}>
                            <FieldOrgTreeSelect/>
                        </Form.Item>

                    </Form>
                </Modal>

                <Modal title='应用基本信息'
                       open={this.state.editVisible}
                       onOk={() => this.editFormRef.current.submit()}
                       onCancel={() => this.setState({editVisible: false})}
                       destroyOnHidden
                       width={600}
                >
                    <Form ref={this.editFormRef} labelCol={{flex: '100px'}}
                          initialValues={this.state.editValues}
                          onFinish={this.handleEditFinish}>
                        <Form.Item name='id' noStyle></Form.Item>

                        <Form.Item name='cnName' label='中文名称'>
                            <Input/>
                        </Form.Item>

                        <Form.Item name='imageUrl' label='镜像' required rules={[{required: true}]}>
                            <Input/>
                        </Form.Item>

                        <Form.Item name='imageTag' label='版本' required rules={[{required: true}]}>
                            <Input placeholder='请输入版本'/>
                        </Form.Item>

                        <Form.Item label='所属组织' name={['sysOrg', 'id']}>
                            <FieldOrgTreeSelect/>
                        </Form.Item>
                    </Form>
                </Modal>

            </Page>

        )
    }

}
