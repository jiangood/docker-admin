import {
    Alert,
    AutoComplete,
    Button,
    Card,
    Col,
    Descriptions,
    Divider,
    Form,
    Input,
    message,
    Modal,
    Row,
    Space,
    Spin,
    Tabs,
    Tag
} from 'antd';
import React from 'react';
import ConfigForm from "./ConfigForm";
import {history} from "umi";

import {FieldOrgTreeSelect, FieldRemoteSelect, HttpUtils, PageUtils} from "@jiangood/admin-spring-boot-starter";
import PublishForm from "./PublishForm";
import LogView from "../../components/LogView";

const Item = Descriptions.Item

export default class extends React.Component {

    state = {
        loading: true,
        app: {},

        containerLoading: true,
        container: {},


        tagOptions: [],


        publishApp: {
            targetVersion: null
        },
        showEditName: false,
        newName: '',


        formValues: {},
        formOpen: false,

    }
    formRef = React.createRef()

    componentDidMount() {
        let id = PageUtils.currentLocationQuery().id
        this.id = id;
        this.loadApp();
        this.loadContainer();
    }


    loadApp() {
        HttpUtils.get('admin/app/get', {id: this.id}).then(rs => {
            this.setState({app: rs, loading: false});
        })
    }

    loadContainer = () => {
        console.log('loadContainer')
        this.setState({containerLoading: true})
        HttpUtils.get("admin/app/container", {id: this.id}).then(container => {
            this.setState({container})

            if (container.state === 'deploying') {
                setTimeout(() => this.loadContainer(), 1000)
            }

        }).catch(() => {
        }).finally(() => {
            this.setState({containerLoading: false})
        })
    }

    reload = () => {
        this.loadApp();
        this.loadContainer()
    };


    deploy = () => {
        const {container} = this.state
        container.state = 'deploying'
        this.setState({container})
        HttpUtils.post('admin/app/deploy/' + this.state.app.id).then(rs => {
            message.success('部署指令已发送，异步执行中...')

            this.loadContainer()
        })
    }
    start = () => {
        HttpUtils.post('admin/app/start/' + this.state.app.id).then(() => {
            this.loadContainer()
        })
    }
    stop = () => {
        HttpUtils.post('admin/app/stop/' + this.state.app.id).then(() => {
            this.loadContainer()
        })
    }
    handleEdit = record => {
        this.setState({formOpen: true, formValues: record})
    }


    onFinish = values => {
        HttpUtils.post('admin/app/updateBaseInfo', values).then(rs => {
            this.setState({formOpen: false})
            this.reload()
        })
    }


    handleDelete = () => {
        const id = this.state.app.id
        const hide = message.loading('删除中...')
        HttpUtils.get("admin/app/delete", {id}).then(rs => {
            hide();

            history.push('/app')

        }).catch(rs => {
            hide();
            Modal.confirm({
                title: '删除失败',
                content: '是否强制删除数据',
                okText: '强制删除数据',
                cancelText: '取消',
                onOk: () => {
                    HttpUtils.get("admin/app/delete", {id, force: true}).then(rs => {
                        history.push('/app')
                    })
                }
            })

        })
    }

    rename = () => {
        let appId = this.state.app.id;
        let {newName} = this.state;
        const hide = message.loading('指令发送中...')
        HttpUtils.post("admin/app/rename", {appId, newName}).then(rs => {

            message.success(rs.message)
            this.setState({app: rs, showEditName: false})
        }).finally(hide)
    }

    render() {
        const {container, app, loading, containerLoading} = this.state;

        if (loading) {
            return <Spin/>
        }
        const {state} = container;


        return (<>

            <Card title={app.name} extra={<Space>
                <Button disabled={state !== 'exited'} onClick={this.start} type="primary">启动</Button>
                <Button disabled={state !== 'running'} onClick={this.stop} type="primary" danger>停止</Button>
                <Button onClick={this.deploy} loading={state === 'deploying'} type="primary">重新部署</Button>
                <Button onClick={() => this.handleEdit(this.state.app)}>修改基本信息</Button>
            </Space>}>


                <Descriptions size="small">
                    <Item label='镜像' span={2}>  {app.imageUrl}:{app.imageTag} </Item>
                    <Item label='状态'>
                        {containerLoading ? "检测中..." :
                            <Tag color={state === 'running' ? 'green' : 'red'}>
                                {container.status}</Tag>}

                    </Item>

                    <Item label='主机'>  {app.host?.name} </Item>
                    <Item label='主机备注'> {app.host?.remark} </Item>


                    <Item label='组织机构'>  {app.sysOrg?.name} </Item>

                </Descriptions>


            </Card>


            <Card className='mt-2'>
                {this.renderTabs()}
            </Card>


            <Modal title='应用基本信息'
                   open={this.state.formOpen}
                   onOk={() => this.formRef.current.submit()}
                   onCancel={() => this.setState({formOpen: false})}
                   destroyOnHidden

                   width={600}

            >

                <Form ref={this.formRef} labelCol={{flex: '100px'}}
                      initialValues={this.state.formValues}
                      onFinish={this.onFinish}>
                    <Form.Item name='id' noStyle></Form.Item>

                    <Form.Item name='imageUrl' label='镜像' required rules={[{required: true}]}>
                        <AutoComplete options={this.state.imageList} onSearch={this.loadImageList}></AutoComplete>
                    </Form.Item>


                    <Form.Item name='imageTag' label='版本' required rules={[{required: true}]}>
                        <AutoComplete options={this.state.imageTagList}
                                      onSearch={this.loadImageTagList}></AutoComplete>
                    </Form.Item>


                    <Form.Item label='所属组织' name={['sysOrg', 'id']}>
                        <FieldOrgTreeSelect/>
                    </Form.Item>

                </Form>
            </Modal>

        </>)
    }

    renderTabs = () => {
        const {app} = this.state



        let consoleLogUrl = '/admin/ws/log/' + app.id;
        let publishLogUrl = '/admin/sys/log/' + app.id;
        const items = [
            {
                key: '1',
                label: '发布日志',
                children: <LogView url={publishLogUrl} websocket={false}/>
            },
            {
                key: 'containerLog',
                label: '控制台日志',
                children: <LogView url={consoleLogUrl} websocket={true}/>
            },
            {
                key: 'config',
                label: '容器配置',
                children: <ConfigForm app={app} onChange={this.reload}/>
            },

            {
                key: 'publish',
                label: '发布',
                children: <PublishForm appId={app.id} onChange={this.reload}/>
            },
            {
                key: 'setting',
                label: "设置",
                children: <>
                    <Row wrap={false}>
                        <Col flex="100px">名称</Col>
                        <Col flex="auto">

                            {!this.state.showEditName ? <div>
                                {this.state.app.name} <a onClick={() => this.setState({
                                newName: this.state.app.name,
                                showEditName: true
                            })}>修改名称</a>
                            </div> : <div>

                                <Input value={this.state.newName} style={{width: 200}}
                                       onChange={e => this.setState({newName: e.target.value})}></Input>
                                <Button type={"primary"} onClick={this.rename}>确定</Button>
                            </div>}

                        </Col>

                    </Row>


                    <Divider></Divider>
                    <Row wrap={false}>
                        <Col flex="100px">删除应用</Col>
                        <Col flex="auto">
                            <Space direction={"vertical"}>
                                <Alert
                                    message="请注意，删除应用将清除该应用的所有数据，且该操作不能被恢复，您确定要删除吗?"
                                    type="warning"
                                ></Alert>
                                <Button danger type="primary" onClick={this.handleDelete}>删除应用</Button>
                            </Space>
                        </Col>
                    </Row>
                </>
            }
        ]


        return <Tabs items={items}></Tabs>
    }

}



