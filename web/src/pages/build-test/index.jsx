import {Alert, Button, Card, Drawer, Form, Input} from 'antd'
import React from 'react'
import {HttpClient, Page, PermActions} from '@jiangood/open-admin'
import LogView from '../../components/LogView'

/**
 * 构建测试：以 Git 仓库为上下文，用粘贴的 Dockerfile 覆盖后，在默认构建节点本地构建镜像（不推送）。
 */
export default class extends React.Component {

    state = {
        logId: null,
        logVisible: false,
        submitting: false,
    }

    onFinish = values => {
        this.setState({submitting: true})
        HttpClient.post('admin/build-test/build', values).then(rs => {
            this.setState({logId: rs.data, logVisible: true})
        }).finally(() => this.setState({submitting: false}))
    }

    render() {
        const {logId, logVisible, submitting} = this.state

        return <Page padding>
            <Card title='构建测试' style={{maxWidth: 760}}>
                <Alert
                    type='info'
                    showIcon
                    style={{marginBottom: 16}}
                    message='以 Git 仓库为构建上下文，粘贴的 Dockerfile 会覆盖仓库中的同名文件'
                    description='使用「设置-主机管理」中标记为构建节点的主机进行本地构建，不推送到注册中心。'
                />

                <Form labelCol={{flex: '110px'}} preserve={false} onFinish={this.onFinish}>
                    <Form.Item label='Git 仓库地址' name='gitUrl'
                               rules={[{required: true, message: '请输入 Git 仓库地址'}]}
                               tooltip='凭据按地址主机自动匹配「设置-代码源」中的配置'>
                        <Input placeholder='https://github.com/user/repo.git'/>
                    </Form.Item>

                    <Form.Item label='Dockerfile' name='dockerfileText'
                               rules={[{required: true, message: '请粘贴 Dockerfile 内容'}]}>
                        <Input.TextArea rows={12} style={{fontFamily: 'monospace'}}
                                        placeholder={'FROM alpine:3.20\nRUN echo hello'} />
                    </Form.Item>

                    <Form.Item label='镜像名' name='targetImage' initialValue='temp:latest'
                               tooltip='本地镜像名:标签，可修改'>
                        <Input placeholder='temp:latest'/>
                    </Form.Item>

                    <Form.Item>
                        <PermActions>
                            <Button perm='build-test:build' type='primary' htmlType='submit' loading={submitting}>
                                构建
                            </Button>
                        </PermActions>
                    </Form.Item>
                </Form>
            </Card>

            <Drawer title='构建日志' width={860} open={logVisible}
                    onClose={() => this.setState({logVisible: false})}>
                {logVisible && logId
                    ? <LogView url={'/admin/ws/build-test-log/' + logId} websocket={true}/>
                    : null}
            </Drawer>
        </Page>
    }
}
