import {Alert, Button, Descriptions, Form, Input, Modal, Select, Space, Switch, Table, Tag, Typography} from 'antd'
import React from 'react'
import {HttpClient} from '@jiangood/open-admin'

/**
 * 补录镜像（镜像列表页）。
 * 作用是把注册中心里已有的镜像补录到本平台：连接地址与凭据一律取
 * 「系统设置 → 镜像注册中心」里已保存的那一条，这里只负责列出镜像、勾选要补录的。
 *
 * 注册中心是否开放仓库列举（/v2/_catalog）由后端探测：阿里云个人版不开放，
 * 此时自动降级为手工填写镜像地址。
 *
 * 一次只能补录一个镜像：远程列表用单选，手工填写也只有一个地址，两者互斥。
 */
export default class RemoteSyncModal extends React.Component {

    state = {
        open: false,
        loading: false,
        catalogLoading: false,
        // 后端返回 {supported, images, existing, registry, message}
        supported: null,
        registry: null,
        registryOptions: [],
        registryId: undefined,
        images: [],
        existing: [],
        // 一次只能补录一个镜像，这里存被选中的镜像名（未选为 null）
        selected: null,
        tip: null,
        result: null,
    }

    formRef = React.createRef()

    /** 打开弹窗 */
    show = () => {
        HttpClient.get('admin/registry/options').then(rs => this.setState({registryOptions: rs.data || []}))
        this.setState({
            open: true, supported: null, images: [], existing: [], selected: null,
            tip: null, result: null,
        })
    }

    cancel = () => {
        this.setState({open: false})
        this.props.onClose?.()
    }

    /** 从系统配置的注册中心列举可补录的镜像 */
    catalog = () => {
        this.setState({catalogLoading: true})
        HttpClient.post('admin/image/remote/catalog', {registryId: this.state.registryId})
            .then(rs => {
                const data = rs.data || {}
                this.setState({
                    supported: data.supported !== false,
                    registry: data.registry || null,
                    images: data.images || [],
                    existing: data.existing || [],
                    selected: null,
                    tip: data.supported === false
                        ? {type: 'warning', text: data.message}
                        : (data.message ? {type: 'success', text: data.message} : null),
                })
            })
            .catch(() => {
                this.setState({tip: {type: 'error', text: '列举失败，请检查「镜像注册中心」里的地址与账号密码'}})
            })
            .finally(() => this.setState({catalogLoading: false}))
    }

    /** 选中一个待补录的镜像；同时清掉手工填写的地址（一次只能补录一个） */
    selectImage = image => {
        this.formRef.current?.setFieldValue('imageUrl', undefined)
        this.setState({selected: image || null})
    }

    sync = () => {
        this.setState({loading: true})
        HttpClient.post('admin/image/remote/sync', this.payload())
            .then(rs => {
                this.setState({result: rs.data})
                this.props.onSuccess?.()
            })
            .finally(() => this.setState({loading: false}))
    }

    /** 单个镜像地址：远程列表选中的一项，或手工填写的一个地址，两者互斥 */
    payload = () => {
        const values = this.formRef.current?.getFieldsValue() || {}
        const manual = (values.imageUrl || '').trim()
        const imageUrls = [...new Set([this.state.selected, manual].filter(Boolean))]
        return {
            imageUrls,
            registryId: this.state.registryId,
            withMeta: values.withMeta !== false,
        }
    }

    render() {
        const {open, loading, catalogLoading, supported, registry, registryOptions, registryId, images, existing, selected, tip, result} = this.state
        const manualOnly = supported === false
        const count = this.payload().imageUrls.length

        return <Modal open={open}
                     width={760}
                     title='补录镜像'
                     onCancel={this.cancel}
                     destroyOnHidden
                     footer={[
                         <Button key='cancel' onClick={this.cancel}>取消</Button>,
                         <Button key='sync' type='primary' loading={loading} onClick={this.sync}
                                 disabled={count !== 1}>
                             确认补录
                         </Button>,
                     ]}>
            <Form ref={this.formRef} layout='vertical' initialValues={{withMeta: true}}
                  onValuesChange={changed => {
                      // 手工填写地址时，取消远程列表里的选中，保证一次只补录一个
                      if (typeof changed.imageUrl === 'string' && changed.imageUrl.trim()) {
                          this.setState({selected: null})
                      }
                  }}>
                <Alert style={{marginBottom: 12}} type='info' showIcon
                       message='一次只能补录一个镜像：勾选下方远程列表中的一项，或手工填写一个镜像地址'
                       description='地址与凭据取「系统设置 → 镜像注册中心」里已保存的那一条，无需再次输入'/>

                {registry
                    ? <Descriptions size='small' column={3} bordered style={{marginBottom: 12}}>
                        <Descriptions.Item label='注册中心'>{registry.url}</Descriptions.Item>
                        <Descriptions.Item label='命名空间'>{registry.namespace || '（不过滤）'}</Descriptions.Item>
                        <Descriptions.Item label='账号'>{registry.username || '（匿名）'}</Descriptions.Item>
                    </Descriptions>
                    : null}

                <Form.Item label='源注册中心' style={{marginBottom: 12}}>
                    <Select allowClear showSearch optionFilterProp='label' value={registryId}
                            options={registryOptions} placeholder='使用系统默认注册中心'
                            onChange={value => this.setState({registryId: value, supported: null, images: [], existing: [], selected: null, registry: null})}/>
                </Form.Item>
                <Space align='center' wrap style={{marginBottom: 8}}>
                    <Button loading={catalogLoading} onClick={this.catalog}>
                        {manualOnly ? '重新尝试列举' : '读取远程镜像'}
                    </Button>
                    <Form.Item name='withMeta' valuePropName='checked' noStyle>
                        <Switch checkedChildren='同时读取镜像元数据' unCheckedChildren='仅补录版本号'/>
                    </Form.Item>
                    <Typography.Text type='secondary' style={{fontSize: 12}}>
                        读取元数据可得到构建时间与大小，每个版本需额外 2 次请求
                    </Typography.Text>
                </Space>

                {tip
                    ? <Alert style={{marginBottom: 12}} type={tip.type} message={tip.text} showIcon/>
                    : null}

                {images.length
                    ? <Table size='small'
                             rowKey='image'
                             pagination={false}
                             scroll={{y: 300}}
                             dataSource={images.map(r => ({image: r, imported: existing.includes(r)}))}
                             rowSelection={{
                                 type: 'radio',
                                 selectedRowKeys: selected ? [selected] : [],
                                 onChange: keys => this.selectImage(keys[0]),
                             }}
                             columns={[
                                 {title: '镜像', dataIndex: 'image'},
                                 {
                                     title: '状态', dataIndex: 'imported', width: 100,
                                     render: imported => imported
                                         ? <Tag>已补录</Tag> : <Tag color='green'>未补录</Tag>,
                                 },
                             ]}
                       />
                    : null}

                <Form.Item style={{marginTop: 12, marginBottom: 0}}
                           name='imageUrl'
                           label={manualOnly ? '镜像地址' : '手工指定镜像地址（可选）'}
                           extra={<>一次只能补录一个镜像，这里填写一个地址即可；可写相对路径（ztcn/nginx）或完整地址。{manualOnly
                               ? '该注册中心不支持自动列举，请手工填写要补录的镜像地址。'
                               : ''}</>}>
                    <Input allowClear placeholder='ztcn/camera-vision-search'/>
                </Form.Item>
            </Form>

            {result
                ? <Alert style={{marginTop: 12}} type='success' showIcon
                         message={`已补录：新增 ${result.imageAdded} 个镜像、${result.tagAdded} 个版本`
                                 + `，跳过已存在 ${result.imageSkipped} 个镜像、${result.tagSkipped} 个版本`
                                 + (result.metaFilled ? `，填充元数据 ${result.metaFilled} 个` : '')}
                         description={result.failures?.length
                             ? `部分失败：${result.failures.join('；')}`
                             : null}
                  />
                : null}
        </Modal>
    }

}
