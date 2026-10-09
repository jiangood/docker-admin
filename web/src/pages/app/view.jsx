import {
    Alert,
    AutoComplete,
    Button,
    Card,
    Checkbox,
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
import TunnelForm from "./TunnelForm";
import {history} from "@jiangood/open-admin";

import {FieldRemoteSelect, HttpClient, Page, PageUtils} from "@jiangood/open-admin";
import PublishForm from "./PublishForm";
import LogView from "../../components/LogView";
import ContainerDetail from "../../components/container/ContainerDetail";
import LinkButton from "../../components/LinkButton";
import {stateColor, stateLabel} from "../../components/container/utils";

const Item = Descriptions.Item

export default class extends React.Component {

    state = {
        loading: true,
        app: {},

        containerLoading: true,
        container: {},


        tagOptions: [],


        showEditName: false,
        newName: '',

        deployVisible: false,
        versionOptions: [],
    }

    deployFormRef = React.createRef()

    componentDidMount() {
        let id = PageUtils.currentParams().id
        this.id = id;
        this._mounted = true;
        this.loadApp();
        this.loadContainer();
    }

    componentWillUnmount() {
        this._mounted = false;
    }


    loadApp() {
        HttpClient.get('admin/app/get', {id: this.id}).then(rs => {
            this.setState({app: rs.data, loading: false});
        })
    }

    loadContainer = () => {
        console.log('loadContainer')
        this.setState({containerLoading: true})
        HttpClient.get("admin/app/container", {id: this.id}).then(rs => {
            const container = rs.data;
            this.setState({container})

            if (container.state === 'deploying' && this._mounted) {
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


    openDeploy = () => {
        HttpClient.get('admin/app/versions', {id: this.state.app.id}).then(rs => {
            this.setState({
                versionOptions: rs.data || [],
                deployVisible: true
            })
        })
    }

    submitDeploy = () => {
        this.deployFormRef.current.validateFields().then(values => {
            const {container} = this.state
            container.state = 'deploying'
            this.setState({container})

            const hide = message.loading('部署中…', 0)
            HttpClient.get('admin/app/updateVersion', {
                id: this.state.app.id,
                version: values.version,
                forcePull: values.forcePull
            }).then(() => {
                this.setState({deployVisible: false})
                message.success('部署指令已发送，异步执行中…')
                this.loadApp()
                this.loadContainer()
            }).finally(hide)
        })
    }
    start = () => {
        HttpClient.post('admin/app/start/' + this.state.app.id).then(() => {
            this.loadContainer()
        })
    }
    stop = () => {
        HttpClient.post('admin/app/stop/' + this.state.app.id).then(() => {
            this.loadContainer()
        })
    }

    handleDelete = () => {
        const id = this.state.app.id
        const hide = message.loading('删除中…',0)
        HttpClient.get("admin/app/delete", {id}).then(rs => {
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
                    HttpClient.get("admin/app/delete", {id, force: true}).then(rs => {
                        history.push('/app')
                    })
                }
            })

        })
    }

    rename = () => {
        let appId = this.state.app.id;
        let {newName} = this.state;
        const hide = message.loading('指令发送中…')
        HttpClient.post("admin/app/rename", {appId, newName}).then(rs => {

            this.setState({app: rs.data, showEditName: false})
        }).finally(hide)
    }

    render() {
        const {container, app, loading, containerLoading} = this.state;

        if (loading) {
            return <Page padding><Spin/></Page>
        }
        const {state} = container;


        return (<Page padding>
            
            <Card title={app.name} extra={<Space>
                <Button disabled={state !== 'exited'} onClick={this.start}>启动</Button>
                <Button disabled={state !== 'running'} onClick={this.stop}>停止</Button>
                <Button onClick={this.openDeploy} loading={state === 'deploying'} type="primary">重新部署</Button>
            </Space>}>


                <Descriptions size="small">
                    <Item label='镜像' span={2}>  {app.imageUrl}:{app.imageTag} </Item>
                    <Item label='状态'>
                        {containerLoading ? '检测中…' :
                            <Tag color={stateColor(state)}>
                                {container.status || stateLabel(state)}</Tag>}

                    </Item>

                    <Item label='主机'>  {app.host?.name} </Item>


                    <Item label='组织机构'>  {app.sysOrg?.name} </Item>

                    <Item label='备注'>  {app.remark} </Item>

                </Descriptions>


            </Card>


            <Card className='mt-4'>
                {this.renderTabs()}
            </Card>

            <Modal title='重新部署'
                   open={this.state.deployVisible}
                   onOk={this.submitDeploy}
                   onCancel={() => this.setState({deployVisible: false})}
                   destroyOnHidden>
                <Form ref={this.deployFormRef} layout='vertical'
                      initialValues={{version: app.imageTag, forcePull: false}}>
                    <Form.Item name='version' label='版本'
                               rules={[{required: true, message: '请选择或输入版本'}]}>
                        <AutoComplete options={this.state.versionOptions}
                                      filterOption={(inputValue, option) =>
                                          (option?.value ?? '').toUpperCase().indexOf(inputValue.toUpperCase()) !== -1
                                      }
                                      placeholder='请选择或输入版本号'/>
                    </Form.Item>
                    <Form.Item name='forcePull' valuePropName='checked' style={{marginBottom: 0}}>
                        <Checkbox>强制拉取镜像</Checkbox>
                    </Form.Item>
                </Form>
            </Modal>

        </Page>)
    }

    renderTabs = () => {
        const {app, container} = this.state


        let publishLogUrl = '/admin/sys/log/' + app.id;
        const items = [
            {
                key: '1',
                label: '发布日志',
                children: <LogView url={publishLogUrl} websocket={false}/>
            },
            {
                key: 'container',
                label: '容器',
                children: container && container.id
                    ? <ContainerDetail hostId={app.host?.id} containerId={container.id} appId={app.id}
                                       height={520} onClose={this.reload}/>
                    : <Alert type='info' showIcon title='容器未部署'/>
            },
            {
                key: 'config',
                label: '容器配置',
                children: <ConfigForm app={app} onChange={this.reload}/>
            },

            {
                key: 'tunnel',
                label: '隧道',
                children: <TunnelForm app={app} onChange={this.reload}/>
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
                                {this.state.app.name} <LinkButton onClick={() => this.setState({
                                newName: this.state.app.name,
                                showEditName: true
                            })}>修改名称</LinkButton>
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
                            <Space orientation={"vertical"}>
                                <Alert
                                    title="请注意，删除应用将清除该应用的所有数据，且该操作不能被恢复，您确定要删除吗?"
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



