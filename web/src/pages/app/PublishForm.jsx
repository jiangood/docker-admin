import React from "react";
import {AutoComplete, Button, Divider, Form, Input, message, Modal, Skeleton, Switch, Typography} from "antd";
import {EventBus, FieldRemoteSelect, HttpClient, PageUtils, PermActions} from "@jiangood/open-admin";

/**
 * 发布标签页
 */
export default class extends React.Component {

    state = {
        app: {},
        appLoading: true,

        tagOptions: [],
        versionOptions: []
    }

    componentDidMount() {
        this.id = this.props.appId

        this.setState({appLoading: true})
        HttpClient.get('admin/app/get', {id: this.id}).then(rs => {
            this.setState({app: rs.data})


        }).finally(() => {
            this.setState({appLoading: false})
        })

        HttpClient.get('admin/app/versions', {id: this.id}).then(rs => {
            this.setState({versionOptions: rs.data || []})
        })
    }

    setAutoDeploy = (autoDeploy) => {
        HttpClient.get("admin/app/autoDeploy", {id: this.id, autoDeploy}).then(rs => {
            this.props.onChange()
        })
    }


    updateVersion = (values) => {
        HttpClient.get("admin/app/updateVersion", {id: this.id, version: values.version}).then(rs => {
            this.props.onChange()
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
                    PageUtils.open('/app/view?id=' + newAppId, '应用-' + rs.data.name)
                }
            })
        }).finally(hide)
    }

    migrateApp = (values) => {
        const hide = message.loading('迁移中..', 0)
        HttpClient.post('admin/app/migrateApp', {
            appId: this.id,
            hostId: values.hostId,
            name: values.name
        }).then(rs => {
            const newAppId = rs.data.id;
            Modal.confirm({
                icon: null,
                title: '迁移完成',
                content: '原应用已删除，是否打开迁移后的应用？不打开将关闭当前页。',
                okText: '打开',
                cancelText: '关闭',
                onOk() {
                    // 先打开新应用，再关闭原（已删除的）应用标签页。
                    // 顺序不能反：原标签页处于激活态时关闭会触发一次延迟跳转，覆盖新标签页。
                    const oldUrl = PageUtils.currentUrl()
                    PageUtils.open('/app/view?id=' + newAppId, '应用-' + rs.data.name)
                    EventBus.emit('closePage', oldUrl)
                },
                onCancel() {
                    // 原应用已删除，留在原页无意义，关闭当前标签页
                    PageUtils.closeCurrent()
                }
            })
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

    validateMigrateName = (_, value) => {
        // 留空沿用原名，或与当前应用同名（迁移会删除原应用）时不校验
        if (!value || value === this.state.app.name) {
            return Promise.resolve()
        }
        return this.checkNameUnique(value)
    }


    render() {
        const {app, appLoading} = this.state;

        if (appLoading) {
            return <Skeleton active={true}/>
        }


        const rowStyle = {display: 'flex', justifyContent: 'space-between', alignItems: 'center'}

        return <>

            <div style={rowStyle}>
                <div>
                    <div>自动发布</div>
                    <Typography.Text type='secondary'>镜像构建成功后，自动更新</Typography.Text>
                </div>
                <Form onValuesChange={changedValues => this.setAutoDeploy(changedValues.autoDeploy)}>
                    <Form.Item
                        name='autoDeploy'
                        valuePropName="checked"
                        style={{marginBottom: 0}}
                        initialValue={app.autoDeploy}>
                        <Switch/>
                    </Form.Item>
                </Form>
            </div>

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
                    <Typography.Text type='secondary'>迁移到其他主机，完成后删除当前应用；隧道需在新应用上重新配置</Typography.Text>
                </div>
                <Form onFinish={this.migrateApp} layout='inline'>
                    <Form.Item name='name' style={{marginBottom: 0}} validateTrigger='onBlur'
                               rules={[
                                   {pattern: /^[A-Za-z]/, message: '必须以字母开头'},
                                   {validator: this.validateMigrateName},
                               ]}>
                        <Input style={{width: 200}} placeholder={'默认沿用原名 ' + app.name}/>
                    </Form.Item>

                    <Form.Item name='hostId' style={{marginBottom: 0}} rules={[{required: true}]}>
                        <FieldRemoteSelect url='admin/host/options' placeholder='请选择目标主机' style={{width: 300}}/>
                    </Form.Item>

                    <PermActions>
                        <Button danger type="primary" htmlType='submit' perm='app:migrate'>迁移应用</Button>
                    </PermActions>
                </Form>
            </div>

        </>
    }
}
