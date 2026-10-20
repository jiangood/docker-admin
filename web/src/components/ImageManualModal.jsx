import {Alert, Button, Form, Input, Modal, Select} from 'antd'
import React from 'react'
import {HttpClient} from '@jiangood/open-admin'

/**
 * 手工补录镜像（镜像列表页）。
 * <p>
 * 用于登记平台外已经构建/推送、但本平台还没有记录的镜像：镜像地址与版本号都由用户填写，
 * 不访问注册中心。已存在的镜像与版本自动跳过，只补新增的。
 */
export default class ImageManualModal extends React.Component {

    formRef = React.createRef()

    state = {
        open: false,
        loading: false,
        result: null,
    }

    /** 打开弹窗 */
    show = () => {
        this.setState({open: true, loading: false, result: null})
    }

    cancel = () => {
        this.setState({open: false})
        this.props.onClose?.()
    }

    submit = () => {
        this.formRef.current.validateFields().then(values => {
            this.setState({loading: true})
            HttpClient.post('admin/image/manual/save', {
                imageUrl: (values.imageUrl || '').trim(),
                tags: values.tags || [],
                remark: (values.remark || '').trim(),
            }).then(rs => {
                this.setState({result: rs.data})
                this.props.onSuccess?.()
            }).finally(() => this.setState({loading: false}))
        }).catch(() => {
            // 表单校验失败时保持弹窗
        })
    }

    /** 再录一个：清空表单与上次结果 */
    reset = () => {
        this.formRef.current?.resetFields()
        this.setState({result: null})
    }

    render() {
        const {open, loading, result} = this.state
        return <Modal open={open}
                      width={620}
                      title='补录镜像'
                      onCancel={this.cancel}
                      destroyOnHidden
                      footer={result
                          ? [
                              <Button key='close' onClick={this.cancel}>关闭</Button>,
                              <Button key='again' type='primary' onClick={this.reset}>再录一个</Button>,
                          ]
                          : [
                              <Button key='cancel' onClick={this.cancel}>取消</Button>,
                              <Button key='save' type='primary' loading={loading} onClick={this.submit}>确认补录</Button>,
                          ]}>
            <Alert style={{marginBottom: 16}} type='info' showIcon
                   message='手工登记平台外的镜像'
                   description='镜像地址与版本号都由你填写，不会访问注册中心；已存在的镜像与版本自动跳过。'/>

            <Form ref={this.formRef} layout='vertical' preserve={false}>
                <Form.Item name='imageUrl'
                           label='镜像地址'
                           rules={[{required: true, message: '请填写镜像地址'}]}
                           extra='一般填写完整地址（含注册中心主机名），与项目构建出来的镜像地址保持一致；地址末尾带 :版本 时会自动拆出版本。'>
                    <Input allowClear placeholder='registry.cn-hangzhou.aliyuncs.com/ztcn/nginx'/>
                </Form.Item>

                <Form.Item name='tags'
                           label='版本号'
                           rules={[{required: true, message: '请至少填写一个版本号'}]}
                           extra='输入后回车，可一次录入多个版本，如 1.0.0、1.0.1。'>
                    <Select mode='tags'
                            tokenSeparators={[',', '，', ' ']}
                            notFoundContent={null}
                            placeholder='输入版本号后回车'/>
                </Form.Item>

                <Form.Item name='remark' label='备注' style={{marginBottom: 0}}>
                    <Input allowClear placeholder='可选，填写后写入镜像备注'/>
                </Form.Item>
            </Form>

            {result
                ? <Alert style={{marginTop: 12}} type='success' showIcon
                         message={`已补录：新增 ${result.imageAdded} 个镜像、${result.tagAdded} 个版本`
                                 + `，跳过已存在 ${result.imageSkipped} 个镜像、${result.tagSkipped} 个版本`}/>
                : null}
        </Modal>
    }

}
