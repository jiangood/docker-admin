import {Button, Form, message} from "antd";
import React from "react";
import ContainerConfigForm from "./ContainerConfigForm";
import {HttpClient} from "@jiangood/open-admin";

/**
 * 应用详情页「参数」标签：复用通用容器配置组件，保存后自动重启。
 * 端口 / 文件映射始终可自由增删改（镜像声明只作默认值）。
 */
export default class extends React.Component {

    update = (values) => {
        const config = values.config
        const hide = message.loading("修改配置中…", 0)
        HttpClient.post('admin/app/updateConfig?id=' + this.props.app.id, config).then(() => {
            // 保存后会自动重启（重新部署），让父级进入部署观察
            this.props.onChange({redeploy: true})
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

            <ContainerConfigForm namePrefix={['config']} appId={app.id} appName={app.name}
                                 imageUrl={app.imageUrl} imageTag={app.imageTag}/>

            <Form.Item label=' '>
                <Button type="primary" htmlType='submit' size="large">保存并重启</Button>
            </Form.Item>
        </Form>
    }
}
