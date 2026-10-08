import {AutoComplete, Button, Form, Input, Modal} from 'antd';
import React from 'react';
import ContainerStatus from "../../components/ContainerStatus";
import {
    PermActions,
    FieldOrgTreeSelect,
    FieldRemoteSelect,
    HttpClient,
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
                return <Button type='link' style={{padding: 0}}
                               onClick={() => PageUtils.open('/app/view?id=' + row.id, '应用-' + name)}>{name}</Button>
            }
        },
        {
            title: '镜像仓库',
            dataIndex: 'imageUrl',
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
            title: '备注',
            dataIndex: 'remark',
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
        deployVisible: false,
        editVisible: false,
        editValues: {},
        imageList: [],
        tagOptions: [],
    }



    reload = () => {
        this.tableRef.current.reload()
    }

    componentDidMount() {
        this.loadImageList()
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

    loadImageList = searchText => {
        HttpClient.get('admin/image-repo/options', {searchText}).then(rs => {
            this.setState({imageList: rs.data || []})
        })
    }

    onImageSelect = imageUrl => {
        HttpClient.get('admin/image-repo/tags', {imageUrl}).then(rs => {
            this.setState({tagOptions: rs.data || []})
        })
    }



    render() {
        return (
            <Page padding>
                <ProTable
                    actionRef={this.tableRef}
                    toolBarRender={() => [
                        <Button key="add" type="primary"
                                onClick={this.handleAdd}>
                            新增
                        </Button>
                    ]}
                    searchFormRender={() => (
                        <>
                            <Form.Item label='组织机构' name='orgId'>
                                <FieldOrgTreeSelect placeholder='全部组织机构'/>
                            </Form.Item>
                            <Form.Item label='运行主机' name='hostId'>
                                <FieldRemoteSelect url="admin/host/options" placeholder='全部主机'/>
                            </Form.Item>
                        </>
                    )}
                    request={(params) => HttpClient.get('admin/app/list', params)}
                    columns={this.columns}

                />
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

                        <Form.Item name='imageUrl' label='镜像仓库' required rules={[{required: true}]}
                                   tooltip='可从已登记镜像中选择，也可直接输入任意镜像地址，如 ghcr.io/jiangood/http-tunnel'>
                            <AutoComplete options={this.state.imageList}
                                           onSelect={this.onImageSelect}
                                           placeholder='选择或输入镜像地址'/>
                        </Form.Item>


                        <Form.Item name='imageTag' label='版本' required rules={[{required: true}]}
                                   tooltip='可从该镜像已有的 tag 中选择，也可直接输入版本号'>
                            <AutoComplete options={this.state.tagOptions}
                                           placeholder='选择或输入版本，如 latest'/>
                        </Form.Item>


                        <Form.Item name={['host', 'id']} label='部署主机' required rules={[{required: true}]}>
                            <FieldRemoteSelect showSearch url="admin/host/options"/>
                        </Form.Item>


                        <Form.Item label='所属组织' name={['sysOrg', 'id']}>
                            <FieldOrgTreeSelect/>
                        </Form.Item>

                        <Form.Item name='remark' label='备注'>
                            <Input/>
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

                        <Form.Item name='imageUrl' label='镜像仓库' required rules={[{required: true}]}
                                   tooltip='可从已登记镜像中选择，也可直接输入任意镜像地址，如 ghcr.io/jiangood/http-tunnel'>
                            <AutoComplete options={this.state.imageList}
                                           onSelect={this.onImageSelect}
                                           placeholder='选择或输入镜像地址'/>
                        </Form.Item>

                        <Form.Item name='imageTag' label='版本' required rules={[{required: true}]}>
                            <Input placeholder='请输入版本'/>
                        </Form.Item>

                        <Form.Item label='所属组织' name={['sysOrg', 'id']}>
                            <FieldOrgTreeSelect/>
                        </Form.Item>

                        <Form.Item name='remark' label='备注'>
                            <Input/>
                        </Form.Item>
                    </Form>
                </Modal>

            </Page>

        )
    }

}
