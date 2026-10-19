import {Alert, Button, Descriptions, Form, Modal, Switch, Typography} from 'antd'
import React from 'react'
import {HttpClient} from '@jiangood/open-admin'

/**
 * 同步单个镜像的标签（镜像详情页）。
 *
 * 与列表页的「补录镜像」不同：这里镜像已经确定，只需从「系统设置 → 镜像注册中心」
 * 把该镜像下本地还没有的版本同步进来，不需要选镜像、不需要填地址。
 */
export default class RemoteTagSyncModal extends React.Component {

    state = {
        open: false,
        loading: false,
        image: null,
        tagCount: 0,
        result: null,
    }

    formRef = React.createRef()

    /** 打开弹窗；image 为当前镜像，tagCount 为本地已有的版本数。 */
    show = (image, tagCount) => {
        this.setState({open: true, image, tagCount: tagCount || 0, result: null})
    }

    cancel = () => {
        this.setState({open: false})
        this.props.onClose?.()
    }

    sync = () => {
        const {image} = this.state
        const withMeta = this.formRef.current?.getFieldValue('withMeta') !== false
        this.setState({loading: true})
        HttpClient.postForm('admin/image/remote/tags', {id: image.id, withMeta})
            .then(rs => {
                this.setState({result: rs.data})
                this.props.onSuccess?.()
            })
            .finally(() => this.setState({loading: false}))
    }

    render() {
        const {open, loading, image, tagCount, result} = this.state
        if (!image) {
            return null
        }

        return <Modal open={open}
                      width={560}
                      title='同步镜像标签'
                      onCancel={this.cancel}
                      destroyOnHidden
                      footer={[
                          <Button key='cancel' onClick={this.cancel}>关闭</Button>,
                          <Button key='sync' type='primary' loading={loading} onClick={this.sync}>开始同步</Button>,
                      ]}>
            <Descriptions size='small' column={1} style={{marginBottom: 12}}>
                <Descriptions.Item label='镜像地址'>
                    <Typography.Text copyable code>{image.imageUrl}</Typography.Text>
                </Descriptions.Item>
                <Descriptions.Item label='本地已有版本'>{tagCount}</Descriptions.Item>
            </Descriptions>

            <Form ref={this.formRef} layout='vertical' initialValues={{withMeta: true}}>
                <Form.Item name='withMeta' valuePropName='checked' style={{marginBottom: 12}}>
                    <Switch checkedChildren='同时读取镜像元数据' unCheckedChildren='仅同步版本号'/>
                </Form.Item>
                <Alert type='info' showIcon
                       message='从全局设置的注册中心读取该镜像的全部版本，本地已存在的会补全缺失的构建时间、大小等元数据'/>
            </Form>

            {result
                ? <Alert style={{marginTop: 12}} type='success' showIcon
                         message={`新增 ${result.tagAdded} 个版本，跳过已存在 ${result.tagSkipped} 个`
                                 + (result.metaFilled ? `，填充元数据 ${result.metaFilled} 个` : '')}/>
                : null}
        </Modal>
    }

}
