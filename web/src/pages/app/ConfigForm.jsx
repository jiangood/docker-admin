import {Button, Form, message} from "antd";
import React from "react";
import ContainerConfigForm from "./ContainerConfigForm";
import {HttpClient} from "@jiangood/open-admin";

/**
 * 应用详情页「容器配置」标签：复用通用容器配置组件，保存后自动重启。
 * 镜像（imageUrl + tag）在镜像标签表中有声明端口/卷时，对应部分按声明只读；否则可自由配置。
 */
export default class extends React.Component {

    update = (values) => {
        const config = values.config
        const hide = message.loading("修改配置中...", 0)
        HttpClient.post('admin/app/updateConfig?id=' + this.props.app.id, config).then(rs => {
            this.props.onChange(rs.data)
        }).finally(hide)
    }

    formRef = React.createRef()

    render() {
        const {app} = this.props
        if (!app.config) {
            return null
        }

        return <Form ref={this.formRef} colon={false} labelCol={{flex: '100px'}} onFinish={this.update}
                     initialValues={{config: app.config}}>

            <ContainerConfigForm namePrefix={['config']} appId={app.id}
                                 imageUrl={app.imageUrl} imageTag={app.imageTag}/>

            <Form.Item label=' '>
                <Button type="primary" danger htmlType='submit' size={"large"}>保存并重启</Button>
            </Form.Item>
        </Form>
    }
}
