import React from "react";
import {Alert, AutoComplete, Button, Divider, Form, Input, message, Modal, Select, Skeleton, Space, Switch, Tag, Tooltip, Typography} from "antd";
import {ReloadOutlined} from "@ant-design/icons";
import {FieldRemoteSelect, HttpClient, PageUtils, Perm, PermUtils} from "@jiangood/open-admin";
import {DEFAULT_UPDATE_INTERVAL, UPDATE_INTERVAL_OPTIONS} from "./updateSchedule";

/**
 * 发布标签页
 */
export default class extends React.Component {

    state = {
        app: {},
        appLoading: true,

        tagOptions: [],
        versionOptions: [],

        // 定时更新设置保存中 / 手动刷新应用信息中
        savingScheduledUpdate: false,
        refreshing: false,
    }

    componentDidMount() {
        this.id = this.props.appId

        this.setState({appLoading: true})
        this.reloadApp().finally(() => {
            this.setState({appLoading: false})
        })

        HttpClient.get('admin/app/versions', {id: this.id}).then(rs => {
            this.setState({versionOptions: rs.data || []})
        })
    }

    /** 重新读取应用信息：定时更新是后台任务改的，页面不会自动感知 */
    reloadApp = () => {
        this.setState({refreshing: true})
        return HttpClient.get('admin/app/get', {id: this.id}).then(rs => {
            this.setState({app: rs.data})
        }).finally(() => {
            this.setState({refreshing: false})
        })
    }

    setAutoDeploy = (autoDeploy) => {
        // 自动发布开关不影响容器，不通知父级刷新；保持开关自身状态即可
        HttpClient.get("admin/app/autoDeploy", {id: this.id, autoDeploy}).then(() => {
            this.setState(s => ({app: {...s.app, autoDeploy}}))
        })
    }

    /**
     * 保存定时更新设置。开关和间隔共用一个接口，后端会返回保存后的应用，
     * 以避免开关状态与「上次检查」快照不同步。
     */
    saveScheduledUpdate = values => {
        const {app, savingScheduledUpdate} = this.state
        if (savingScheduledUpdate) {
            return
        }
        const payload = {
            id: this.id,
            scheduledUpdate: values.scheduledUpdate !== undefined
                ? values.scheduledUpdate
                : !!app.scheduledUpdate,
            intervalMinutes: values.intervalMinutes !== undefined
                ? values.intervalMinutes
                : (Number(app.scheduledUpdateIntervalMinutes) || DEFAULT_UPDATE_INTERVAL),
        }

        this.setState({savingScheduledUpdate: true})
        HttpClient.get('admin/app/scheduledUpdate', payload).then(rs => {
            this.setState({app: rs.data || {}})
            // 顶部信息卡也展示「定时更新」，同步刷新父级
            this.props.onChange && this.props.onChange({refreshApp: true})
        }).finally(() => {
            this.setState({savingScheduledUpdate: false})
        })
    }

    /** 上次检查结果的短标签，完整内容放在 Tooltip 里 */
    renderScheduledResult = app => {
        if (app.scheduledUpdateLastSuccess === false) {
            return <Tag color='red'>检查失败</Tag>
        }
        const result = app.scheduledUpdateLastResult || ''
        if (result.indexOf('发现新版本') >= 0) {
            return <Tag color='blue'>发现新版本</Tag>
        }
        if (result.indexOf('已是最新') >= 0) {
            return <Tag color='green'>已是最新</Tag>
        }
        if (result.indexOf('跳过') >= 0) {
            return <Tag>跳过</Tag>
        }
        return result ? <Tag>{result}</Tag> : null
    }

    renderScheduledStatus = () => {
        const {app, refreshing} = this.state
        if (!app.scheduledUpdate) {
            return <Typography.Text type='secondary'>未启用</Typography.Text>
        }
        return <Space size={6} wrap>
            <Typography.Text type='secondary'>
                {app.scheduledUpdateLastCheckTime ? `上次检查：${app.scheduledUpdateLastCheckTime}` : '等待首次检查'}
            </Typography.Text>
            {app.scheduledUpdateLastResult && <Tooltip title={app.scheduledUpdateLastResult}>
                {this.renderScheduledResult(app)}
            </Tooltip>}
            <Button type='link' size='small' icon={<ReloadOutlined/>} loading={refreshing}
                    onClick={this.reloadApp}>刷新</Button>
        </Space>
    }


    updateVersion = (values) => {
        HttpClient.get("admin/app/updateVersion", {id: this.id, version: values.version}).then(rs => {
            // 版本变化会影响容器配置元数据（镜像声明的端口 / 卷），一并刷新
            this.props.onChange({redeploy: true, tabs: ['config']})
        })
    }
    copyApp = (values) => {
        const hide = message.loading('复制中..', 0)
        HttpClient.post("admin/app/copyApp", {
            appId: this.id,
            hostId: values.hostId,
            name: values.name
        }).then(rs => {
            const newAppId = rs.data.id;
            Modal.confirm({
                icon: null,
                title: '复制完成',
                content: '是否打开新的应用？',
                onOk() {
                    PageUtils.open('/app/view?id=' + newAppId)
                }
            })
        }).finally(hide)
    }

    migrateApp = (values) => {
        const hide = message.loading('迁移中..', 0)
        HttpClient.post('admin/app/migrateApp', {
            appId: this.id,
            hostId: values.hostId
        }).then(() => {
            // 应用记录仍在，仅主机变化：重新部署后，容器配置（绑定路径）要跟着刷新
            this.props.onChange({redeploy: true, tabs: ['config']})
        }).finally(hide)
    }

    /**
     * 名称是否已被其他应用占用（配合后端 admin/app/existsName）。
     */
    checkNameUnique = (value) => {
        // 第二个回调只处理“接口请求失败”，放行交给提交时后端校验兜底；
        // 命中重名时在上面 reject，不会被这里吞掉。
        return HttpClient.get('admin/app/existsName', {name: value}).then(rs => {
            if (rs.data) {
                return Promise.reject(new Error('应用名称已存在'))
            }
            return Promise.resolve()
        }, () => Promise.resolve())
    }

    validateCopyName = (_, value) => {
        return value ? this.checkNameUnique(value) : Promise.resolve()
    }


    render() {
        const {app, appLoading, savingScheduledUpdate} = this.state;

        if (appLoading) {
            return <Skeleton active={true}/>
        }

        const canDeploy = PermUtils.hasPermission('app:deploy')
        const scheduledUpdate = !!app.scheduledUpdate
        const intervalMinutes = Number(app.scheduledUpdateIntervalMinutes) || DEFAULT_UPDATE_INTERVAL
        const containerState = this.props.containerState
        // 容器确实处于停止 / 暂停状态时才提示；部署中（deploying）不提示
        const containerStopped = ['exited', 'created', 'paused', 'dead'].includes(containerState)
        const rowStyle = {
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 16,
            flexWrap: 'wrap',
        }

        return <>

            <Divider titlePlacement='left' plain>更新策略</Divider>

            <div style={rowStyle}>
                <div style={{flex: '1 1 260px'}}>
                    <div>构建后自动部署</div>
                    <Typography.Text type='secondary'>本项目镜像构建成功后，立即更新应用</Typography.Text>
                </div>
                <Form onValuesChange={changedValues => this.setAutoDeploy(changedValues.autoDeploy)}>
                    <Form.Item
                        name='autoDeploy'
                        valuePropName="checked"
                        style={{marginBottom: 0}}
                        initialValue={app.autoDeploy}>
                        <Switch disabled={!canDeploy}/>
                    </Form.Item>
                </Form>
            </div>

            <Divider className='mt-3 mb-3'/>

            <div style={rowStyle}>
                <div style={{flex: '1 1 260px'}}>
                    <div>定时更新</div>
                    <Typography.Text type='secondary'>
                        由平台定时查询注册中心（只读清单，不下载镜像），镜像有变化时才重新部署（适用于外部镜像）
                    </Typography.Text>
                    <div style={{marginTop: 4}}>
                        {this.renderScheduledStatus()}
                    </div>
                    {!canDeploy && <div>
                        <Typography.Text type='secondary'>无「应用部署」权限，仅可查看</Typography.Text>
                    </div>}
                </div>
                <Space wrap>
                    <Select style={{width: 130}}
                            value={intervalMinutes}
                            options={UPDATE_INTERVAL_OPTIONS}
                            disabled={!scheduledUpdate || !canDeploy || savingScheduledUpdate}
                            onChange={value => this.saveScheduledUpdate({intervalMinutes: value})}/>
                    <Switch checked={scheduledUpdate}
                            loading={savingScheduledUpdate}
                            disabled={!canDeploy}
                            onChange={checked => this.saveScheduledUpdate({scheduledUpdate: checked})}/>
                </Space>
            </div>

            {scheduledUpdate && app.imageTag && app.imageTag !== 'latest' && (
                <Alert className='mt-3' type='warning' showIcon
                       title={`当前版本固定为 ${app.imageTag}，只有该标签在仓库中被重新推送时才会触发更新`}/>
            )}

            {scheduledUpdate && containerStopped && (
                <Alert className='mt-3' type='info' showIcon
                       title='容器当前未运行，定时更新不会执行'/>
            )}

            <Divider className='mt-3 mb-3'/>

            <div style={rowStyle}>
                <div>
                    <div>手动发布</div>
                    <Typography.Text type='secondary'>选择版本后更新应用</Typography.Text>
                </div>
                <Form onFinish={this.updateVersion} layout={'inline'}>
                    <Form.Item name='version' style={{marginBottom: 0}}
                               rules={[{required: true, message: '请选择或输入版本'}]}>
                        <AutoComplete style={{width: 180}}
                                      options={this.state.versionOptions}
                                      filterOption={(inputValue, option) =>
                                          (option?.value ?? '').toUpperCase().indexOf(inputValue.toUpperCase()) !== -1
                                      }
                                      placeholder='请选择或输入版本号'/>
                    </Form.Item>
                    <Button type="primary" htmlType='submit'>更新应用</Button>
                </Form>
            </div>

            <Divider className='mt-3 mb-3'/>

            <div style={rowStyle}>
                <div>
                    <div>复制应用</div>
                    <Typography.Text type='secondary'>复制应用不会自动部署，也不会复制主机上的文件</Typography.Text>
                </div>
                <Form onFinish={this.copyApp} layout='inline'>
                    <Form.Item name='name' style={{marginBottom: 0}} validateTrigger='onBlur'
                               rules={[
                                   {required: true, message: '请输入应用名称'},
                                   {pattern: /^[A-Za-z]/, message: '必须以字母开头'},
                                   {validator: this.validateCopyName},
                               ]}>
                        <Input style={{width: 200}} placeholder='请输入新应用名称'/>
                    </Form.Item>

                    <Form.Item name='hostId' style={{marginBottom: 0}} rules={[{required: true}]}>
                        <FieldRemoteSelect url='admin/host/options' placeholder='请选择新主机' style={{width: 300}}/>
                    </Form.Item>

                    <Button type="primary" htmlType='submit'>确定复制</Button>
                </Form>
            </div>

            <Divider className='mt-3 mb-3'/>

            <div style={rowStyle}>
                <div>
                    <div>迁移应用</div>
                    <Typography.Text type='secondary'>在原主机停止并移除容器，切换到目标主机后重新部署</Typography.Text>
                </div>
                <Form onFinish={this.migrateApp} layout='inline'>
                    <Form.Item name='hostId' style={{marginBottom: 0}} rules={[{required: true}]}>
                        <FieldRemoteSelect url='admin/host/options' placeholder='请选择目标主机' style={{width: 300}}/>
                    </Form.Item>

                    <Perm code='app:migrate'>
                        <Button danger type="primary" htmlType='submit'>迁移应用</Button>
                    </Perm>
                </Form>
            </div>

        </>
    }
}
