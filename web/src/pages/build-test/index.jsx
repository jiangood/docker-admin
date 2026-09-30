import {Alert, Button, Card, Drawer, Form, Input, message, Space, Tooltip} from 'antd'
import React from 'react'
import {HttpClient, Page, PermActions} from '@jiangood/open-admin'
import FieldGitRepository from '../../components/FieldGitRepository'
import LogView from '../../components/LogView'

/**
 * 构建测试：以 Git 仓库为上下文，用粘贴的 Dockerfile 覆盖后，在默认构建节点本地构建镜像（不推送）。
 */
export default class extends React.Component {

    state = {
        logId: null,
        logVisible: false,
        submitting: false,
        reading: false,
    }

    formRef = React.createRef()

    onFinish = values => {
        this.setState({submitting: true})
        HttpClient.post('admin/build-test/build', values).then(rs => {
            this.setState({logId: rs.data, logVisible: true})
        }).finally(() => this.setState({submitting: false}))
    }

    /**
     * 克隆 Git 仓库并读取根目录下的 Dockerfile，回填到编辑框。
     */
    readRepositoryDockerfile = e => {
        e.preventDefault()
        const gitUrl = this.formRef.current?.getFieldValue('gitUrl')
        if (!gitUrl) {
            message.warning('请先填写 Git 仓库地址')
            return
        }
        this.setState({reading: true})
        HttpClient.get('admin/build-test/read-dockerfile', {gitUrl}).then(rs => {
            this.formRef.current?.setFieldsValue({dockerfileText: rs.data})
            message.success('已读取仓库中的 Dockerfile')
        }).finally(() => this.setState({reading: false}))
    }

    render() {
        const {logId, logVisible, submitting, reading} = this.state

        return <Page padding>
            <Card title='构建测试' style={{maxWidth: 760}}>
                <Alert
                    type='info'
                    showIcon
                    style={{marginBottom: 16}}
                    message='以 Git 仓库为构建上下文，粘贴的 Dockerfile 会覆盖仓库中的同名文件'
                    description='使用「设置-主机管理」中标记为构建节点的主机进行本地构建，不推送到注册中心。'
                />

                <Form ref={this.formRef} labelCol={{flex: '150px'}} preserve={false} onFinish={this.onFinish}>
                    <Form.Item label='Git 仓库地址' name='gitUrl'
                               rules={[{required: true, message: '请输入 Git 仓库地址'}]}
                               tooltip='可直接输入地址，也可从「设置-代码源」的仓库列表中选择；凭据按地址主机自动匹配代码源'>
                        <FieldGitRepository/>
                    </Form.Item>

                    <Form.Item label={
                        <Space size={4}>
                            Dockerfile
                            <Tooltip title='克隆 Git 仓库并读取根目录下的 Dockerfile'>
                                <Button type='link' size='small' style={{padding: 0, height: 'auto'}}
                                        loading={reading} onClick={this.readRepositoryDockerfile}>
                                    读取仓库
                                </Button>
                            </Tooltip>
                        </Space>
                    } name='dockerfileText'
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
