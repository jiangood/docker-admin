import React from "react";
import {AutoComplete, Button, Card, Col, Form, message, Modal, Row, Skeleton, Switch} from "antd";
import {FieldRemoteSelect, Gap, HttpClient, PageUtils} from "@jiangood/open-admin";

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
        HttpClient.post("admin/app/copyApp", {appId: this.id, hostId: values.hostId}).then(rs => {
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


    render() {
        const {app, appLoading} = this.state;

        if (appLoading) {
            return <Skeleton active={true}/>
        }


        return <>


            <Row gutter={24}>

                <Col span={12}> <Card title='自动发布'>
                    <Form onValuesChange={changedValues => this.setAutoDeploy(changedValues.autoDeploy)}>
                        <Form.Item
                            name='autoDeploy'
                            valuePropName="checked"
                            initialValue={app.autoDeploy}>
                            <Switch/>
                        </Form.Item>
                    </Form>
                    镜像构建成功后，自动更新
                </Card>
                </Col>
                <Col span={12}>
                    <Card title='手动发布'>
                        <Form onFinish={this.updateVersion} layout={'inline'}>
                            <Form.Item name='version' rules={[{required: true, message: '请选择或输入版本'}]}>
                                <AutoComplete style={{width: 180}}
                                              options={this.state.versionOptions}
                                              filterOption={(inputValue, option) =>
                                                  (option?.value ?? '').toUpperCase().indexOf(inputValue.toUpperCase()) !== -1
                                              }
                                              placeholder='请选择或输入版本号'/>
                            </Form.Item>
                            <Form.Item label=' '>
                                <Button type="primary" danger htmlType='submit'>更新应用</Button>
                            </Form.Item>
                        </Form>
                    </Card>
                </Col>
            </Row>


            <Gap/>

            <Card title='复制应用'>
                <Form onFinish={this.copyApp} layout='inline'>
                    <Form.Item name='hostId' rules={[{required: true}]}>
                        <FieldRemoteSelect url='admin/host/options' placeholder='请选择新主机' style={{width: 300}}/>
                    </Form.Item>

                    <Button type="primary" danger htmlType='submit'>确定复制</Button>
                </Form>
                <Gap/>
                注意：复制应用不会自动部署，也不会复制主机上的文件
            </Card>

        </>
    }
}
