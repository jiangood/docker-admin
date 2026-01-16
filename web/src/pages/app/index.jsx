import {AutoComplete, Button, Form, Input, Menu, Modal, Splitter} from 'antd';
import React from 'react';
import ContainerStatus from "../../components/ContainerStatus";
import {FieldOrgTreeSelect, FieldRemoteSelect, HttpUtils, PageUtils, ProTable} from "@jiangood/admin-spring-boot-starter";


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
            title: '镜像',
            dataIndex: 'imageUrl',
            sorter: true,
            render: (_, record) => {
                if (record.imageUrl) {
                    return record.imageUrl
                }
                return <a
                    onClick={() => PageUtils.open('project/view?id=' + record.project.id, '镜像-' + record.project.name)}>{record.project.name}</a>
            }
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
            title: '组织机构',
            dataIndex: ['sysOrg', 'name'],

        },

        {
            title: '最近更新',
            dataIndex: 'modifyTime',
        },


    ];
    state = {

        deployVisible: false,
        deployImageVisible: false,


        imageList: [],

        groupData: [],
        selectedKey: null,

    }

    componentDidMount() {
        HttpUtils.get("admin/appGroup/menus").then(rs => {
            this.setState({groupData: rs})
        })
    }

    reload = () => {
        this.tableRef.current.reload()
    }


    handleSave = value => {
        HttpUtils.post('admin/app/save', value).then(() => {
            this.reload()
            this.setState({deployVisible: false})
        })
    }

    formRef = React.createRef()
    tableRef = React.createRef()
    handleAdd = () => {
        this.setState({deployVisible: true})
    }



    render() {
        return (
            <> <Splitter>
                <Splitter.Panel size={200}>

                    <div style={{padding: 12}}>
                        <Menu items={this.state.groupData} onSelect={({key}) => {
                            this.setState({selectedKey: key}, this.reload);
                        }}/>
                    </div>
                </Splitter.Panel>
                <Splitter.Panel style={{paddingLeft: 16}}>
                    <ProTable
                        actionRef={this.tableRef}
                        toolBarRender={() => [
                            <Button type="primary"
                                    onClick={this.handleAdd}>
                                新增
                            </Button>
                        ]}
                        request={(params) => {
                            params.groupId = this.state.selectedKey
                            return HttpUtils.get('admin/app/list', params);
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


            </>

        )
    }

}
