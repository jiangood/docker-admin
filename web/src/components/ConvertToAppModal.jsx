import {Alert, AutoComplete, Checkbox, Col, Divider, Form, Input, Modal, Row, Space, Spin, Typography, message} from 'antd'
import {FieldOrgTreeSelect, HttpClient, PermUtils} from '@jiangood/open-admin'
import React, {useEffect, useState} from 'react'
import ContainerConfigForm from '../pages/app/ContainerConfigForm'

/**
 * 「容器转为应用」弹窗（通用组件）。
 * <p>
 * 打开时按容器 inspect 结果生成应用草稿：名称 / 镜像 / 端口 / 目录挂载 / 环境变量 /
 * 标签 / 启动命令 / 设备请求都尽量还原。用户可修改后保存。
 * <p>
 * 默认只登记应用并关联当前容器，不重启它；勾选「重建容器」后由后端按应用配置
 * 重新部署（会短暂中断服务），并额外要求 app:deploy 权限。
 *
 * @param open        是否显示
 * @param hostId      容器所在主机
 * @param containerId 容器 id
 * @param onCancel    关闭回调
 * @param onSuccess   转换成功回调，参数为新建的应用
 */
export default function ConvertToAppModal({open, hostId, containerId, onCancel, onSuccess}) {
    const [form] = Form.useForm()
    const [loading, setLoading] = useState(false)
    const [submitting, setSubmitting] = useState(false)
    const [draft, setDraft] = useState(null)
    const [recreate, setRecreate] = useState(false)
    const [tagOptions, setTagOptions] = useState([])

    const canDeploy = PermUtils.hasPermission('app:deploy')

    useEffect(() => {
        if (!open || !hostId || !containerId) {
            return
        }
        setLoading(true)
        setDraft(null)
        setRecreate(false)
        form.resetFields()
        HttpClient.get('admin/container/appDraft', {hostId, containerId}, {toastError: false})
            .then(rs => {
                const data = rs.data || {}
                const app = data.app || {}
                setDraft(data)
                form.setFieldsValue({
                    name: app.name,
                    imageUrl: app.imageUrl,
                    imageTag: app.imageTag,
                    remark: app.remark,
                    sysOrg: app.sysOrg || undefined,
                    config: app.config,
                })
            })
            .catch(e => message.error(e.message || '读取容器配置失败'))
            .finally(() => setLoading(false))
        // form 实例稳定，不放进依赖避免重复请求
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open, hostId, containerId])

    const warnings = draft?.warnings || []
    const blocked = !!draft?.managedAppName
    const appName = Form.useWatch('name', form)
    const imageUrl = Form.useWatch('imageUrl', form)
    const imageTag = Form.useWatch('imageTag', form)

    // 版本下拉：已登记的镜像列出已有 tag，未登记时保持自由输入
    useEffect(() => {
        if (!open || !imageUrl) {
            setTagOptions([])
            return
        }
        const timer = setTimeout(() => {
            HttpClient.get('admin/image/tags', {imageUrl}, {toastError: false})
                .then(rs => setTagOptions(rs.data || []))
                .catch(() => setTagOptions([]))
        }, 300)
        return () => clearTimeout(timer)
    }, [open, imageUrl])

    const handleOk = () => {
        form.validateFields().then(values => {
            setSubmitting(true)
            HttpClient.post('admin/container/convertToApp', {
                hostId,
                containerId,
                recreate,
                app: values,
            }).then(rs => {
                message.success(rs.message || '已转为应用')
                if (onSuccess) {
                    onSuccess(rs.data)
                }
                if (onCancel) {
                    onCancel()
                }
            }).finally(() => setSubmitting(false))
        }).catch(() => {
            // 校验失败由表单自己提示
        })
    }

    const title = draft?.containerName
        ? `容器转为应用 · ${draft.containerName}`
        : '容器转为应用'

    return <Modal title={title} open={open} width={960}
                  okText={recreate ? '转换为应用并重建容器' : '转换为应用'}
                  cancelText='取消'
                  confirmLoading={submitting}
                  okButtonProps={{disabled: loading || blocked}}
                  onOk={handleOk}
                  onCancel={onCancel}
                  destroyOnHidden>
        {loading
            ? <div className='center-box' style={{padding: '48px 0'}}><Spin/></div>
            : !draft
                ? <Alert type='error' showIcon title='无法读取容器信息'/>
                : <>
                    {blocked &&
                        <Alert className='mb-4' type='error' showIcon
                               title={`该容器已由应用「${draft.managedAppName}」管理`}
                               description='无需重复转换，请到「应用」列表查看。'/>}

                    <Alert className='mb-4' type='info' showIcon
                           title='转换方式'
                           description={<>
                               <div>默认只把容器登记为应用并建立关联，不重启当前容器；
                                   平台后续可以查看状态、启停、看日志与删除。</div>
                               <div>勾选下方「重建容器」后，平台会按这里的配置重新创建容器
                                   （会短暂中断服务，用于让旧的容器完全纳入平台管理）。</div>
                           </>}/>

                    {warnings.length > 0 &&
                        <Alert className='mb-4' type='warning' showIcon
                               title='转换前请确认以下事项'
                               description={<ul style={{margin: 0, paddingLeft: 20}}>
                                   {warnings.map((w, i) => <li key={i}>{w}</li>)}
                               </ul>}/>}

                    <Form form={form} layout='vertical'>
                        <Row gutter={16}>
                            <Col span={12}>
                                <Form.Item name='name' label='应用名称'
                                           rules={[
                                               {required: true, message: '请输入应用名称'},
                                               {pattern: /^[A-Za-z]/, message: '应用名称必须以字母开头'},
                                           ]}
                                           tooltip='平台要求应用名以字母开头；保留当前容器时名称可以与容器名不同，平台按容器 ID 关联'>
                                    <Input placeholder='如 my-nginx'/>
                                </Form.Item>
                            </Col>
                            <Col span={12}>
                                <Form.Item label='容器所在主机'>
                                    <Input disabled value={draft.app?.host?.name || ''}/>
                                </Form.Item>
                            </Col>
                            <Col span={12}>
                                <Form.Item name='imageUrl' label='镜像'
                                           rules={[{required: true, message: '请输入镜像地址'}]}>
                                    <Input placeholder='如 nginx 或 registry.example.com/ns/app'/>
                                </Form.Item>
                            </Col>
                            <Col span={12}>
                                <Form.Item name='imageTag' label='版本'
                                           rules={[{required: true, message: '请输入版本'}]}>
                                    <AutoComplete options={tagOptions} placeholder='如 latest'/>
                                </Form.Item>
                            </Col>
                            <Col span={12}>
                                <Form.Item name={['sysOrg', 'id']} label='所属组织'>
                                    <FieldOrgTreeSelect/>
                                </Form.Item>
                            </Col>
                            <Col span={12}>
                                <Form.Item name='remark' label='备注'>
                                    <Input/>
                                </Form.Item>
                            </Col>
                        </Row>

                        <Divider titlePlacement='left' plain>容器配置</Divider>
                        <ContainerConfigForm namePrefix={['config']} twoColumn
                                             imageUrl={imageUrl} imageTag={imageTag}/>

                        <Space orientation='vertical' size={4} style={{marginTop: 8}}>
                            <Checkbox checked={recreate} disabled={blocked || !canDeploy}
                                      onChange={e => setRecreate(e.target.checked)}>
                                转换后立即按配置重建容器（会中断当前容器）
                            </Checkbox>
                            {!canDeploy &&
                                <Typography.Text type='secondary' style={{fontSize: 12}}>
                                    当前账号缺少应用部署权限（app:deploy），只能保留当前容器。
                                </Typography.Text>}
                        </Space>

                        <div style={{marginTop: 8}}>
                            <Typography.Text type='secondary' style={{fontSize: 12}}>
                                容器 {draft.containerId?.slice(0, 12)} · 状态 {draft.containerState || '-'}
                                {appName ? ` · 应用名 ${appName}` : ''}
                            </Typography.Text>
                        </div>
                    </Form>
                </>}
    </Modal>
}
