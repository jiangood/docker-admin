import {Alert, Button, Descriptions, Form, Input, Modal, Space, Switch, Table, Tag, Typography} from 'antd'
import React from 'react'
import {HttpClient} from '@jiangood/open-admin'

/**
 * 补录镜像仓库（镜像仓库列表页）。
 * 作用是把注册中心里已有的仓库补录到本平台：连接地址与凭据一律取
 * 「系统设置 → 镜像注册中心」里已保存的那一条，这里只负责列出仓库、勾选要补录的。
 *
 * 注册中心是否开放仓库列举（/v2/_catalog）由后端探测：阿里云个人版不开放，
 * 此时自动降级为手工填写镜像地址。
 */
export default class RemoteSyncModal extends React.Component {

    state = {
        open: false,
        loading: false,
        catalogLoading: false,
        // 后端返回 {supported, repos, existing, registry, message}
        supported: null,
        registry: null,
        repos: [],
        existing: [],
        selected: [],
        tip: null,
        result: null,
    }

    formRef = React.createRef()

    /** 打开弹窗 */
    show = () => {
        this.setState({
            open: true, supported: null, repos: [], existing: [], selected: [],
            tip: null, result: null,
        })
    }

    cancel = () => {
        this.setState({open: false})
        this.props.onClose?.()
    }

    /** 从系统配置的注册中心列举可补录的仓库 */
    catalog = () => {
        this.setState({catalogLoading: true})
        HttpClient.post('admin/image-repo/remote/catalog', {})
            .then(rs => {
                const data = rs.data || {}
                this.setState({
                    supported: data.supported !== false,
                    registry: data.registry || null,
                    repos: data.repos || [],
                    existing: data.existing || [],
                    selected: [],
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

    /** 勾选全部尚未导入的仓库 */
    selectAllNew = () => {
        const {repos, existing} = this.state
        this.setState({selected: repos.filter(r => !existing.includes(r))})
    }

    sync = () => {
        this.setState({loading: true})
        HttpClient.post('admin/image-repo/remote/sync', this.payload())
            .then(rs => {
                this.setState({result: rs.data})
                this.props.onSuccess?.()
            })
            .finally(() => this.setState({loading: false}))
    }

    /** 勾选的仓库 + 手工填写的地址，去重后提交 */
    payload = () => {
        const values = this.formRef.current?.getFieldsValue() || {}
        const manual = (values.imageUrls || '')
            .split('\n')
            .map(v => v.trim())
            .filter(Boolean)
        return {
            imageUrls: [...new Set([...this.state.selected, ...manual])],
            withMeta: values.withMeta !== false,
        }
    }

    render() {
        const {open, loading, catalogLoading, supported, registry, repos, existing, selected, tip, result} = this.state
        const manualOnly = supported === false
        const count = this.payload().imageUrls.length

        return <Modal open={open}
                     width={760}
                     title='补录镜像仓库'
                     onCancel={this.cancel}
                     destroyOnHidden
                     footer={[
                         <Button key='cancel' onClick={this.cancel}>取消</Button>,
                         <Button key='sync' type='primary' loading={loading} onClick={this.sync}
                                 disabled={!count}>
                             确认补录{count ? `（${count} 个仓库）` : ''}
                         </Button>,
                     ]}>
            <Form ref={this.formRef} layout='vertical' initialValues={{withMeta: true}}>
                <Alert style={{marginBottom: 12}} type='info' showIcon
                       message='补录使用「系统设置 → 镜像注册中心」里已保存的地址与凭据，无需再次输入'/>

                {registry
                    ? <Descriptions size='small' column={3} bordered style={{marginBottom: 12}}>
                        <Descriptions.Item label='注册中心'>{registry.url}</Descriptions.Item>
                        <Descriptions.Item label='命名空间'>{registry.namespace || '（不过滤）'}</Descriptions.Item>
                        <Descriptions.Item label='账号'>{registry.username || '（匿名）'}</Descriptions.Item>
                    </Descriptions>
                    : null}

                <Space align='center' wrap style={{marginBottom: 8}}>
                    <Button loading={catalogLoading} onClick={this.catalog}>
                        {manualOnly ? '重新尝试列举' : '读取远程仓库'}
                    </Button>
                    {repos.length
                        ? <Button type='link' size='small' onClick={this.selectAllNew}>选择全部未补录</Button>
                        : null}
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

                {repos.length
                    ? <Table size='small'
                             rowKey='repo'
                             pagination={false}
                             scroll={{y: 300}}
                             dataSource={repos.map(r => ({repo: r, imported: existing.includes(r)}))}
                             rowSelection={{
                                 selectedRowKeys: selected,
                                 onChange: keys => this.setState({selected: keys}),
                             }}
                             columns={[
                                 {title: '仓库', dataIndex: 'repo'},
                                 {
                                     title: '状态', dataIndex: 'imported', width: 100,
                                     render: imported => imported
                                         ? <Tag>已补录</Tag> : <Tag color='green'>未补录</Tag>,
                                 },
                             ]}
                       />
                    : null}

                <Form.Item style={{marginTop: 12, marginBottom: 0}}
                           label={manualOnly ? '镜像地址' : '手工指定镜像地址（可选）'}
                           extra={<>每行一个，可写相对路径（ztcn/nginx）或完整地址。{manualOnly
                               ? '该注册中心不支持自动列举，请手工填写要补录的镜像地址。'
                               : ''}</>}>
                    <Input.TextArea rows={3} placeholder='ztcn/camera-vision-search'/>
                </Form.Item>
            </Form>

            {result
                ? <Alert style={{marginTop: 12}} type='success' showIcon
                         message={`已补录：新增 ${result.repoAdded} 个仓库、${result.tagAdded} 个版本`
                                 + `，跳过已存在 ${result.repoSkipped} 个仓库、${result.tagSkipped} 个版本`
                                 + (result.metaFilled ? `，填充元数据 ${result.metaFilled} 个` : '')}
                         description={result.failures?.length
                             ? `部分失败：${result.failures.join('；')}`
                             : null}
                  />
                : null}
        </Modal>
    }

}