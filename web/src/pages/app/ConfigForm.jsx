import {Alert, Button, Form, message} from "antd";
import React from "react";
import ContainerConfigForm from "./ContainerConfigForm";
import {HttpClient} from "@jiangood/open-admin";
import LinkButton from "../../components/LinkButton";

/**
 * 应用详情页「容器配置」标签：复用通用容器配置组件，保存后自动重启。
 * 镜像（imageUrl + tag）在镜像标签表中有声明端口/卷时，对应部分按声明只读；否则可自由配置。
 * 端口 / 网络模式会改变隧道转发的目标地址，因此隧道开启时禁止修改。
 */
export default class extends React.Component {

    update = (values) => {
        if (this.props.app.tunnelEnabled) {
            message.warning('该应用已开启隧道，请先在「隧道」标签关闭后再修改容器配置')
            return
        }
        const config = values.config
        const hide = message.loading("修改配置中…", 0)
        HttpClient.post('admin/app/updateConfig?id=' + this.props.app.id, config).then(() => {
            // 保存后会自动重启（重新部署），让父级进入部署观察
            this.props.onChange({redeploy: true})
        }).finally(hide)
    }

    formRef = React.createRef()

    render() {
        const {app, onOpenTunnel} = this.props
        if (!app.config) {
            return null
        }
        const tunnelOn = !!app.tunnelEnabled

        return <>
            {tunnelOn &&
                <Alert type='info' showIcon className='mb-3'
                       title='该应用已开启隧道，修改容器配置（端口 / 网络等）前需先关闭隧道'
                       action={<LinkButton onClick={onOpenTunnel}>去关闭隧道</LinkButton>}/>}

            <Form ref={this.formRef} colon={false} labelCol={{flex: '100px'}} onFinish={this.update}
                  initialValues={{config: app.config}}>

                <ContainerConfigForm namePrefix={['config']} appId={app.id} appName={app.name}
                                     imageUrl={app.imageUrl} imageTag={app.imageTag}/>

                <Form.Item label=' '>
                    <Button type="primary" htmlType='submit' size="large" disabled={tunnelOn}>保存并重启</Button>
                </Form.Item>
            </Form>
        </>
    }
}
