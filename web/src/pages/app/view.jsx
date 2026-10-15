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
    Tag,
    Tooltip
} from 'antd';
import React from 'react';
import {ReloadOutlined} from "@ant-design/icons";
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

/**
 * 等待「部署中」状态出现的最长时间（毫秒）。部署下发是异步的，@Async 任务登记 deploying 有延迟；
 * 触发部署后按 1s 轮询，观察到 deploying 后持续轮询直到它结束，始终没观察到则到此超时收尾。
 */
const DEPLOY_PROBE_TIMEOUT = 10000

/** 详情页各标签的 key，刷新令牌按此逐项递增 */
const TAB_KEYS = ['log', 'container', 'config', 'tunnel', 'publish']

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

        activeTab: 'log',

        stopping: false,
        starting: false,

        deployLoading: false,
        deploySubmitting: false,

        // 各标签的刷新令牌：对应值 +1 只让该标签重挂载并重新拉取数据，其他标签不受影响
        tabTokens: {log: 0, container: 0, config: 0, tunnel: 0, publish: 0},
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
        clearTimeout(this.pollTimer)
    }


    loadApp = () => {
        return HttpClient.get('admin/app/get', {id: this.id}).then(rs => {
            this.setState({app: rs.data, loading: false});
        })
    }

    /**
     * 拉取容器状态。
     * @param silent 静默轮询：不显示「检测中…」，避免部署轮询期间顶部状态每秒闪烁
     */
    loadContainer = (silent = false) => {
        if (!this._mounted) {
            return
        }
        if (!silent) {
            this.setState({containerLoading: true})
        }
        return HttpClient.get("admin/app/container", {id: this.id}).then(rs => {
            if (!this._mounted) {
                return
            }
            const container = rs.data;
            this.setState({container})

            const deploying = container.state === 'deploying'

            // 默认仅在「部署中」时继续轮询
            let keepPolling = deploying
            const watch = this.deployWatch
            if (watch) {
                if (deploying) {
                    watch.seenDeploying = true
                } else if (watch.seenDeploying) {
                    // 部署已结束：刷新部署日志与容器标签，展示最终状态
                    this.deployWatch = null
                    this.bumpTokens(['log', 'container'])
                    keepPolling = false
                } else if (Date.now() - watch.startedAt > DEPLOY_PROBE_TIMEOUT) {
                    // 兜底：始终没观察到 deploying（部署极快已结束，或已失败），不再空转轮询
                    this.deployWatch = null
                    this.bumpTokens(['log', 'container'])
                    keepPolling = false
                } else {
                    // @Async 任务登记 deploying 有延迟，继续探
                    keepPolling = true
                }
            }

            clearTimeout(this.pollTimer)
            if (keepPolling) {
                this.pollTimer = setTimeout(() => this.loadContainer(true), 1000)
            }
        }).catch(() => {
        }).finally(() => {
            if (this._mounted && !silent) {
                this.setState({containerLoading: false})
            }
        })
    }

    reload = () => {
        return Promise.all([this.loadApp(), this.loadContainer()])
    };

    /**
     * 只让指定标签重挂载并重新拉取数据，其余标签保持原样（不再整块骨架屏遮挡）。
     */
    bumpTokens = (keys) => {
        this.setState(s => {
            const tabTokens = {...s.tabTokens}
            keys.forEach(k => tabTokens[k] = (tabTokens[k] || 0) + 1)
            return {tabTokens}
        })
    }

    /**
     * 子标签通知「配置已修改」。只有真正影响容器 / 部署的操作才需要刷新；
     * 修改隧道、自动发布开关这类纯配置改动不回调父级，避免无谓刷新。
     * @param redeploy 是否触发了重新部署；true 时进入部署观察，部署结束后再刷新日志与容器标签
     * @param tabs     需要立即重挂载的标签 key；不传则不动任何标签
     */
    handleChanged = ({redeploy = false, tabs} = {}) => {
        if (redeploy) {
            this.deployWatch = {seenDeploying: false, startedAt: Date.now()}
        }
        this.reload().catch(() => {
        })
        if (tabs && tabs.length > 0) {
            this.bumpTokens(tabs)
        }
    }

    /** 手动刷新：重拉顶部信息并让所有标签重新拉取（不遮挡内容） */
    manualRefresh = () => {
        this.reload().catch(() => {
        })
        this.bumpTokens(TAB_KEYS)
    }


    openDeploy = () => {
        this.setState({deployLoading: true})
        HttpClient.get('admin/app/versions', {id: this.state.app.id}).then(rs => {
            this.setState({
                versionOptions: rs.data || [],
                deployVisible: true
            })
        }).finally(() => {
            this.setState({deployLoading: false})
        })
    }

    submitDeploy = () => {
        this.deployFormRef.current.validateFields().then(values => {
            this.setState({deploySubmitting: true})

            const hide = message.loading('部署中…', 0)
            HttpClient.get('admin/app/updateVersion', {
                id: this.state.app.id,
                version: values.version,
                forcePull: values.forcePull
            }).then(() => {
                this.setState({deployVisible: false, activeTab: 'log'})
                this.handleChanged({redeploy: true})
            }).finally(() => {
                hide()
                this.setState({deploySubmitting: false})
            })
        })
    }
    start = () => {
        this.setState({starting: true})
        HttpClient.post('admin/app/start/' + this.state.app.id).then(() => {
            this.reload().catch(() => {
            })
            this.bumpTokens(['container'])
        }).finally(() => {
            this.setState({starting: false})
        })
    }
    stop = () => {
        this.setState({stopping: true})
        HttpClient.post('admin/app/stop/' + this.state.app.id).then(() => {
            this.reload().catch(() => {
            })
            this.bumpTokens(['container'])
        }).finally(() => {
            this.setState({stopping: false})
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
        const {container, app, loading, containerLoading, stopping, starting, deployLoading, deploySubmitting} = this.state;

        if (loading) {
            return <Page padding><Spin/></Page>
        }
        const {state} = container;


        return (<Page padding>
            
            <Card title={app.name} extra={<Space>
                <Button disabled={state !== 'exited'} loading={starting} onClick={this.start}>启动</Button>
                <Button disabled={state !== 'running'} loading={stopping} onClick={this.stop}>停止</Button>
                <Button onClick={this.openDeploy} loading={state === 'deploying' || deployLoading} type="primary">重新部署</Button>
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
                   confirmLoading={deploySubmitting}
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
        const {app, container, tabTokens} = this.state


        let publishLogUrl = '/admin/sys/log/' + app.id;
        const items = [
            {
                key: 'log',
                label: '部署日志',
                children: <LogView key={'log-' + tabTokens.log} url={publishLogUrl} websocket={false}/>
            },
            {
                key: 'container',
                label: '容器',
                children: container && container.id
                    ? <ContainerDetail key={'container-' + tabTokens.container}
                                       hostId={app.host?.id} containerId={container.id} appId={app.id}
                                       height={520} onClose={this.reload}/>
                    : <Alert type='info' showIcon title='容器未部署'/>
            },
            {
                key: 'config',
                label: '容器配置',
                children: <ConfigForm key={'config-' + tabTokens.config} app={app} onChange={this.handleChanged}/>
            },

            {
                key: 'tunnel',
                label: '隧道',
                children: <TunnelForm key={'tunnel-' + tabTokens.tunnel} app={app} onChange={this.handleChanged}/>
            },

            {
                key: 'publish',
                label: '发布',
                children: <PublishForm key={'publish-' + tabTokens.publish} appId={app.id}
                                       onChange={this.handleChanged}/>
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


        return <Tabs items={items} activeKey={this.state.activeTab}
                     onChange={activeTab => this.setState({activeTab})}
                     tabBarExtraContent={{
                         right: <Tooltip title='刷新'>
                             <Button type='text' size='small' icon={<ReloadOutlined/>}
                                     onClick={this.manualRefresh}/>
                         </Tooltip>
                     }}></Tabs>
    }

}



