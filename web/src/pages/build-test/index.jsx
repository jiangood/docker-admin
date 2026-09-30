import {Alert, Button, Card, Col, Drawer, Form, Input, message, Modal, Row} from 'antd'
import React from 'react'
import {CloudUploadOutlined, FileTextOutlined, ReloadOutlined} from '@ant-design/icons'
import {HttpClient, Page, PermActions} from '@jiangood/open-admin'
import CodeMirrorEditor from '../../components/CodeMirrorEditor'
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
        writeOpen: false,
        writing: false,
    }

    formRef = React.createRef()

    writeFormRef = React.createRef()

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

    /**
     * 打开写入仓库的确认弹窗，先校验必填项。
     */
    openWriteModal = () => {
        const form = this.formRef.current
        if (!form?.getFieldValue('gitUrl')) {
            message.warning('请先填写 Git 仓库地址')
            return
        }
        if (!form.getFieldValue('dockerfileText')) {
            message.warning('请先填写 Dockerfile 内容')
            return
        }
        this.setState({writeOpen: true})
    }

    /**
     * 将编辑后的 Dockerfile 提交并推送到 Git 仓库。
     */
    submitWrite = () => {
        const values = this.formRef.current.getFieldsValue()
        const writeValues = this.writeFormRef.current?.getFieldsValue()
        this.setState({writing: true})
        HttpClient.post('admin/build-test/write-dockerfile', {
            gitUrl: values.gitUrl,
            dockerfileText: values.dockerfileText,
            commitMessage: writeValues?.commitMessage,
            branch: writeValues?.branch,
        }).then(() => {
            this.setState({writeOpen: false})
        }).finally(() => this.setState({writing: false}))
    }

    render() {
        const {logId, logVisible, submitting, reading, writeOpen, writing} = this.state

        return <Page padding>
            <Alert
                type='info'
                showIcon
                style={{marginBottom: 16}}
                message='以 Git 仓库为构建上下文，粘贴的 Dockerfile 会覆盖仓库中的同名文件'
                description='使用「设置-主机管理」中标记为构建节点的主机进行本地构建，不推送到注册中心。'
            />

            <Form ref={this.formRef} layout='vertical' preserve={false} onFinish={this.onFinish}>
                <Row gutter={16}>
                    <Col xs={24} lg={16} xl={17}>
                        <Card title='Dockerfile'
                              extra={
                                  <PermActions>
                                      <Button perm='build-test:view' size='small' icon={<ReloadOutlined/>}
                                              loading={reading} onClick={this.readRepositoryDockerfile}>
                                          读取仓库 Dockerfile
                                      </Button>
                                      <Button perm='build-test:write' size='small' icon={<CloudUploadOutlined/>}
                                              onClick={this.openWriteModal}>
                                          写入仓库 Dockerfile
                                      </Button>
                                  </PermActions>
                              }>
                            <Form.Item name='dockerfileText' style={{marginBottom: 0}}
                                       rules={[{required: true, message: '请粘贴 Dockerfile 内容'}]}>
                                <CodeMirrorEditor mode='dockerfile' height={520}
                                                  placeholder={'FROM alpine:3.20\nRUN echo hello'}/>
                            </Form.Item>
                        </Card>
                    </Col>

                    <Col xs={24} lg={8} xl={7}>
                        <Card title='构建配置'
                              extra={logId ? (
                                  <Button type='link' size='small' icon={<FileTextOutlined/>}
                                          onClick={() => this.setState({logVisible: true})}>
                                      查看日志
                                  </Button>
                              ) : null}>
                            <Form.Item label='Git 仓库地址' name='gitUrl'
                                       rules={[{required: true, message: '请输入 Git 仓库地址'}]}
                                       tooltip='可直接输入地址，也可从「设置-代码源」的仓库列表中选择；凭据按地址主机自动匹配代码源'>
                                <FieldGitRepository/>
                            </Form.Item>

                            <Form.Item label='镜像名' name='targetImage' initialValue='temp:latest'
                                       tooltip='本地镜像名:标签，可修改'>
                                <Input placeholder='temp:latest'/>
                            </Form.Item>

                            <Form.Item style={{marginBottom: 0}}>
                                <PermActions>
                                    <Button perm='build-test:build' type='primary' htmlType='submit'
                                            loading={submitting} block>
                                        构建
                                    </Button>
                                </PermActions>
                            </Form.Item>
                        </Card>
                    </Col>
                </Row>
            </Form>

            <Drawer title='构建日志' width={860} open={logVisible}
                    mask={{closable: false}}
                    onClose={() => this.setState({logVisible: false})}>
                {logVisible && logId
                    ? <LogView url={'/admin/ws/build-test-log/' + logId} websocket={true}/>
                    : null}
            </Drawer>

            <Modal title='写入仓库 Dockerfile'
                   open={writeOpen}
                   confirmLoading={writing}
                   okText='提交并推送'
                   onOk={this.submitWrite}
                   onCancel={() => this.setState({writeOpen: false})}
                   destroyOnHidden>
                <Alert type='warning' showIcon style={{marginBottom: 16}}
                       message='将编辑器的内容写入仓库根目录的 Dockerfile，并提交推送'
                       description='凭据按 Git 仓库地址主机自动匹配代码源；推送会直接修改远程仓库，请确认内容无误。'/>
                <Form ref={this.writeFormRef} layout='vertical'>
                    <Form.Item label='目标分支' name='branch'
                               tooltip='留空使用仓库默认分支；填写后需为仓库中已存在的分支'>
                        <Input placeholder='留空使用仓库默认分支，如 main'/>
                    </Form.Item>
                    <Form.Item label='提交说明' name='commitMessage'
                               initialValue='chore: 更新 Dockerfile' style={{marginBottom: 0}}>
                        <Input.TextArea rows={2} placeholder='chore: 更新 Dockerfile'/>
                    </Form.Item>
                </Form>
            </Modal>
        </Page>
    }
}
