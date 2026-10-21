import {Button, Card, Drawer, Form, Input, Select} from 'antd'
import {FileTextOutlined} from '@ant-design/icons'
import React from 'react'
import {HttpClient, Page, Perm} from '@jiangood/open-admin'
import LogView from '../../components/LogView'

/**
 * 镜像下载 tar：在指定主机上拉取镜像（支持公共镜像），再用 docker save 打成 tar 包。
 * <p>
 * 只有镜像地址与执行主机两个输入；提交后直接弹出实时日志窗口。
 */
export default class extends React.Component {

    state = {
        hostOptions: [],
        logId: null,
        logVisible: false,
        submitting: false,
    }

    componentDidMount() {
        HttpClient.get('admin/host/options')
            .then(rs => this.setState({hostOptions: rs.data || []}))
            .catch(() => {
            })
    }

    submit = values => {
        this.setState({submitting: true})
        HttpClient.post('admin/image-download/download', values).then(rs => {
            this.setState({logId: rs.data, logVisible: true})
        }).catch(() => {
            // 参数校验、无权限等请求未成功，不会产生日志，直接恢复按钮
            this.setState({submitting: false})
        })
    }

    /**
     * 日志流关闭即表示后台下载已结束，恢复「开始下载」按钮。
     */
    onLogClose = () => {
        this.setState({submitting: false})
    }

    render() {
        const {hostOptions, logId, logVisible, submitting} = this.state

        return <Page padding>
            <Card title='镜像下载' className='page-card'
                  extra={logId ? (
                      <Button type='link' size='small' icon={<FileTextOutlined/>}
                              onClick={() => this.setState({logVisible: true})}>
                          查看日志
                      </Button>
                  ) : null}>
                <Form style={{maxWidth: 520}} labelCol={{flex: '80px'}}
                      preserve={false} onFinish={this.submit}>
                    <Form.Item label='镜像地址' name='image'
                               rules={[{required: true, message: '请输入镜像地址'}]}>
                        <Input placeholder='mysql:8'/>
                    </Form.Item>

                    <Form.Item label='执行主机' name='hostId'
                               rules={[{required: true, message: '请选择执行主机'}]}>
                        <Select options={hostOptions} showSearch optionFilterProp='label'
                                placeholder='请选择执行主机'/>
                    </Form.Item>

                    <Form.Item>
                        <Perm code='image-download:download'>
                            <Button type='primary' htmlType='submit' loading={submitting}>
                                开始下载
                            </Button>
                        </Perm>
                    </Form.Item>
                </Form>
            </Card>

            <Drawer title='下载日志' size={860} open={logVisible}
                    mask={{closable: false}}
                    onClose={() => this.setState({logVisible: false})}>
                {logVisible && logId
                    ? <LogView key={logId} url={'/admin/ws/image-download-log/' + logId}
                               websocket={true} onClose={this.onLogClose}/>
                    : null}
            </Drawer>
        </Page>
    }
}
