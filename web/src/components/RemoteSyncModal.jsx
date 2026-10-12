import {Alert, Button, Form, Input, Modal, Space, Switch, Table, Tag, Typography} from 'antd'
import React from 'react'
import {HttpClient} from '@jiangood/open-admin'

/**
 * 远程注册中心同步弹窗。
 *
 * 走标准 Docker Registry V2 协议，凭据只在本次请求内使用、不落库。
 * 注册中心是否开放仓库列举（/v2/_catalog）由后端探测：阿里云个人版不开放，
 * 此时自动降级为手工填写镜像地址。
 */
export default class RemoteSyncModal extends React.Component {

    state = {
        open: false,
        loading: false,
        catalogLoading: false,
        previewLoading: false,
        catalogTip: null,
        groups: [],
        result: null,
    }

    formRef = React.createRef()

    /** 打开弹窗；initialImageUrl 用于从镜像仓库详情页唤起时预填地址。 */
    show = (initialImageUrl) => {
        this.setState({open: true, groups: [], result: null, catalogTip: null})
        this.formRef.current?.resetFields()
        if (initialImageUrl) {
            this.formRef.current?.setFieldsValue({imageUrls: initialImageUrl})
        }
    }

    cancel = () => {
        this.setState({open: false})
        this.props.onClose?.()
    }

    /** 后端返回 {supported, repos, message} */
    catalog = () => {
        this.setState({catalogLoading: true})
        HttpClient.post('admin/image-repo/remote/catalog', this.payload())
            .then(rs => {
                const data = rs.data || {}
                if (data.supported) {
                    this.formRef.current?.setFieldsValue({imageUrls: (data.repos || []).join('\n')})
                    this.setState({catalogTip: {type: 'success', text: data.message || `共发现 ${(data.repos || []).length} 个仓库，已填入下方`}})
                } else {
                    this.setState({catalogTip: {type: 'warning', text: data.message}})
                }
            })
            .catch(() => {
                this.setState({catalogTip: {type: 'error', text: '列举失败，请检查注册中心地址与账号密码'}})
            })
            .finally(() => this.setState({catalogLoading: false}))
    }

    preview = () => {
        this.setState({previewLoading: true, groups: [], result: null})
        HttpClient.post('admin/image-repo/remote/preview', this.payload({withMeta: false}))
            .then(rs => {
                this.setState({groups: rs.data || []})
            })
            .finally(() => this.setState({previewLoading: false}))
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

    /** 收集表单值，imageUrls 按行拆成数组，空行丢弃。 */
    payload = (override) => {
        const values = this.formRef.current?.getFieldsValue() || {}
        const imageUrls = (values.imageUrls || '')
            .split('\n')
            .map(v => v.trim())
            .filter(Boolean)
        return {
            url: (values.url || '').trim(),
            namespace: (values.namespace || '').trim(),
            username: (values.username || '').trim(),
            password: values.password || '',
            imageUrls,
            withMeta: values.withMeta !== false,
            ...override,
        }
    }

    /** 同一仓库的连续行合并单元格 */
    rowSpanOf = (data, index, field) => {
        const value = data[index][field]
        let count = 1
        while (index + count < data.length && data[index + count][field] === value) {
            count++
        }
        return count
    }

    render() {
        const {open, loading, catalogLoading, previewLoading, catalogTip, groups, result} = this.state
        const flat = groups.flatMap(g => (g.tags || []).map(t => ({...t, groupError: g.error})))

        return <Modal open={open}
                     width={920}
                     title='从远程注册中心同步'
                     onCancel={this.cancel}
                     destroyOnHidden
                     footer={[
                         <Button key='cancel' onClick={this.cancel}>取消</Button>,
                         <Button key='preview' loading={previewLoading} onClick={this.preview}>预览版本</Button>,
                         <Button key='sync' type='primary' loading={loading} onClick={this.sync}
                                 disabled={!groups.length}>确认同步</Button>,
                     ]}>
            <Form ref={this.formRef}
                  layout='vertical'
                  initialValues={{withMeta: true}}
                  onFinish={() => this.preview()}>
                <Space size={16} style={{display: 'flex'}} align='start'>
                    <Form.Item name='url' label='注册中心地址' required
                               style={{flex: 1, minWidth: 260}}
                               rules={[{required: true, message: '请填写注册中心地址'}]}>
                        <Input placeholder='registry.cn-hangzhou.aliyuncs.com 或 https://harbor.example.com'/>
                    </Form.Item>
                    <Form.Item name='namespace' label='命名空间' style={{flex: 1, minWidth: 160}}
                               extra='阿里云个人版为账号 UID'>
                        <Input placeholder='选填'/>
                    </Form.Item>
                </Space>
                <Space size={16} style={{display: 'flex'}} align='start'>
                    <Form.Item name='username' label='账号' style={{flex: 1, minWidth: 160}}>
                        <Input placeholder='选填，公共仓库可留空'/>
                    </Form.Item>
                    <Form.Item name='password' label='密码' style={{flex: 1, minWidth: 160}}>
                        <Input.Password placeholder='选填'/>
                    </Form.Item>
                </Space>

                <Form.Item label='镜像地址' required
                           extra={<>每行一个，可写相对路径（ztcn/nginx）或完整地址；点「自动列举」可批量填入。
                               凭据只用于本次请求，不会保存。</>}>
                    <Input.TextArea rows={4} placeholder='ztcn/camera-vision-search'/>
                </Form.Item>

                <Space align='center' style={{marginBottom: 8}}>
                    <Button loading={catalogLoading} onClick={this.catalog}>自动列举仓库</Button>
                    <Form.Item name='withMeta' valuePropName='checked' noStyle>
                        <Switch checkedChildren='同时读取镜像元数据' unCheckedChildren='仅同步版本号'/>
                    </Form.Item>
                    <Typography.Text type='secondary' style={{fontSize: 12}}>
                        读取元数据可得到构建时间与大小，每个版本需额外 2 次请求
                    </Typography.Text>
                </Space>
            </Form>

            {catalogTip
                ? <Alert style={{marginBottom: 12}} type={catalogTip.type} message={catalogTip.text} showIcon/>
                : null}

            {result
                ? <Alert style={{marginBottom: 12}} type='success' showIcon
                         message={`同步完成：新增 ${result.repoAdded} 个仓库、${result.tagAdded} 个版本`
                                 + `，跳过已存在 ${result.repoSkipped} 个仓库、${result.tagSkipped} 个版本`
                                 + (result.metaFilled ? `，填充元数据 ${result.metaFilled} 个` : '')}
                         description={result.failures?.length
                             ? `部分失败：${result.failures.join('；')}`
                             : null}
                  />
                : null}

            {flat.length
                ? <Table size='small'
                         rowKey={(r, i) => r.imageUrl + '|' + r.tag}
                         pagination={false}
                         scroll={{y: 320}}
                         dataSource={flat}
                         columns={[
                             {
                                 title: '仓库', dataIndex: 'imageUrl', width: 280,
                                 onCell: (record, index) => ({rowSpan: this.rowSpanOf(flat, index, 'imageUrl')}),
                                 render: (v, r, index) => (index === 0 || flat[index - 1].imageUrl !== v)
                                     ? <Typography.Text strong copyable>{v}</Typography.Text> : null,
                             },
                             {title: '版本', dataIndex: 'tag', width: 160},
                             {
                                 title: '状态', dataIndex: 'exists', width: 90,
                                 render: (exists, r) => r.groupError
                                     ? <Tag color='red'>读取失败</Tag>
                                     : (exists ? <Tag>已存在</Tag> : <Tag color='green'>新增</Tag>),
                             },
                         ]}
                  />
                : null}

            {groups.filter(g => g.error).map(g =>
                <Typography.Paragraph key={g.imageUrl} type='danger' style={{marginTop: 8}}>
                    {g.imageUrl}：{g.error}
                </Typography.Paragraph>
            )}
        </Modal>
    }

}