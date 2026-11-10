import {HistoryOutlined, PlusOutlined, SearchOutlined, WarningOutlined} from '@ant-design/icons'
import {Drawer, Form, Input, InputNumber, Modal, Select, Space, Switch, Tag, Tooltip, Typography, message} from 'antd'
import React from 'react'
import {HttpClient, Page, PermActions, ProTable} from '@jiangood/open-admin'

/**
 * 网站监控：定时探测一批网站是否可达，双向关键词判定（期望出现 / 出现即异常），
 * 连续失败可发邮件告警。列表展示最近一次检测结果，行操作可看检测历史或立即检测。
 * <p>
 * 背景：很多站点挂在 nginx 等反向代理后面，代理层可能用 200 状态码返回错误占位页
 * （「502 Bad Gateway」、维护文案等），所以除了状态码还要能按内容判定。
 */

const INTERVAL_OPTIONS = [1, 5, 10, 30, 60].map(v => ({
    label: `${v} 分钟`,
    value: v,
}))

export default class extends React.Component {

    state = {
        formOpen: false,
        formValues: {},
        // 当前查看历史记录的监控项：null 关闭抽屉
        logRecord: null,
        checkingId: null,
    }

    formRef = React.createRef()
    tableRef = React.createRef()

    columns = [
        {
            title: '名称',
            dataIndex: 'name',
        },
        {
            title: '地址',
            dataIndex: 'url',
            width: 240,
            render: (v, record) => <Typography.Text ellipsis={{tooltip: v}} style={{maxWidth: 240}}>{v}</Typography.Text>,
        },
        {
            title: '方式',
            dataIndex: 'method',
            width: 70,
            render: v => <Tag color='blue'>{v || 'GET'}</Tag>,
        },
        {
            title: '关键词',
            width: 160,
            render: (_, record) => {
                const tags = []
                if (record.keyword) {
                    tags.push(<Tooltip key='k' title={`响应必须包含「${record.keyword}」才算正常`}>
                        <Tag color='green'>期望: {record.keyword}</Tag>
                    </Tooltip>)
                }
                if (record.errorKeyword) {
                    tags.push(<Tooltip key='e' title={`响应出现「${record.errorKeyword}」即判定不可达`}>
                        <Tag color='red'>异常: {record.errorKeyword}</Tag>
                    </Tooltip>)
                }
                return tags.length
                    ? <Space size={4}>{tags}</Space>
                    : <Typography.Text type='secondary'>按状态码</Typography.Text>
            },
        },
        {
            title: '间隔',
            dataIndex: 'intervalMinutes',
            width: 85,
            render: v => `${v || 5} 分钟`,
        },
        {
            title: '超时',
            dataIndex: 'timeoutSeconds',
            width: 75,
            render: v => `${v || 10} 秒`,
        },
        {
            title: '最近状态',
            width: 180,
            render: (_, record) => {
                if (record.lastSuccess == null) {
                    return <Typography.Text type='secondary'>未检测</Typography.Text>
                }
                const status = record.lastSuccess
                    ? <Tag color='green'>正常</Tag>
                    : <Tooltip title={record.lastError}><Tag color='red'>异常</Tag></Tooltip>
                return <Space size={4}>
                    {status}
                    <Typography.Text type='secondary' style={{fontSize: 12}}>
                        {record.lastStatusCode ?? '-'} · {record.lastLatencyMs ?? '-'}ms
                    </Typography.Text>
                </Space>
            },
        },
        {
            title: '最近检测',
            dataIndex: 'lastCheckTime',
            width: 165,
            render: v => v || '-',
        },
        {
            title: '邮件告警',
            dataIndex: 'notifyEmail',
            width: 220,
            render: (v, record) => {
                if (!v) {
                    return <Typography.Text type='secondary'>-</Typography.Text>
                }
                if (record.notifyEmailTo) {
                    return <Tooltip title={`告警与恢复通知发送至创建人邮箱 ${record.notifyEmailTo}`}>
                        <Space size={4}>
                            <Tag color='purple'>开</Tag>
                            <Typography.Text ellipsis style={{maxWidth: 160}}>{record.notifyEmailTo}</Typography.Text>
                        </Space>
                    </Tooltip>
                }
                return <Tooltip title='创建人未配置邮箱，告警邮件实际发不出去；请到「系统管理-用户管理」为该用户补充邮箱'>
                    <Space size={4}>
                        <Tag color='orange'>开</Tag>
                        <Typography.Text type='danger' style={{fontSize: 12}}>
                            <WarningOutlined/> 无收件人
                        </Typography.Text>
                    </Space>
                </Tooltip>
            },
        },
        {
            title: '操作',
            width: 200,
            render: (_, record) => (
                <PermActions size='small' actions={[
                    {
                        label: '立即检测', perm: 'site-monitor:save',
                        loading: this.state.checkingId === record.id,
                        onClick: () => this.handleCheck(record),
                    },
                    {label: '历史', perm: 'site-monitor:view', icon: <HistoryOutlined/>, onClick: () => this.setState({logRecord: record})},
                    {label: '修改', perm: 'site-monitor:save', onClick: () => this.handleEdit(record)},
                    {label: '删除', perm: 'site-monitor:delete', confirm: `确定删除监控项「${record.name}」？其检测历史会一并删除`, onClick: () => this.handleDelete(record)},
                ]}/>
            ),
        },
    ]

    handleAdd = () => {
        this.setState({formOpen: true, formValues: {method: 'GET', intervalMinutes: 5, timeoutSeconds: 10, enabled: true, notifyEmail: false}})
    }

    handleEdit = record => {
        this.setState({formOpen: true, formValues: {...record}})
    }

    onFinish = values => {
        if (values.method === 'HEAD' && (values.keyword || values.errorKeyword)) {
            message.error('HEAD 请求没有响应体，无法做关键词判定，请改用 GET 或清空关键词')
            return
        }
        HttpClient.post('admin/site-monitor/save', values).then(() => {
            this.setState({formOpen: false})
            this.tableRef.current.reload()
        })
    }

    handleDelete = record => {
        HttpClient.postForm('admin/site-monitor/delete', {id: record.id}).then(() => {
            this.tableRef.current.reload()
        })
    }

    /** 立即检测：同步等结果，完成后就地提示本次判定并刷新列表。 */
    handleCheck = record => {
        this.setState({checkingId: record.id})
        HttpClient.postForm('admin/site-monitor/check', {id: record.id}).then(rs => {
            const log = rs.data || {}
            if (log.success) {
                message.success(`「${record.name}」正常，状态码 ${log.statusCode}，耗时 ${log.latencyMs}ms`)
            } else {
                message.error(`「${record.name}」不可达：${log.error}`)
            }
        }).finally(() => {
            this.setState({checkingId: null})
            this.tableRef.current.reload()
        })
    }

    render() {
        const {formValues} = this.state
        const isCreate = !formValues.id

        return <Page padding>
            <ProTable
                actionRef={this.tableRef}
                searchInline
                searchFormRender={() => (
                    <Form.Item name='searchText' noStyle>
                        <Input prefix={<SearchOutlined/>} placeholder='搜索名称 / 地址' allowClear/>
                    </Form.Item>
                )}
                toolBarRightRender={() => (
                    <PermActions actions={[
                        {label: '新增', perm: 'site-monitor:save', type: 'primary', icon: <PlusOutlined/>, onClick: this.handleAdd},
                    ]}/>
                )}
                request={(params) => HttpClient.get('admin/site-monitor/page', params)}
                columns={this.columns}
            />

            <Modal title={isCreate ? '新增监控' : '修改监控'}
                   width={620}
                   open={this.state.formOpen}
                   onOk={() => this.formRef.current.submit()}
                   onCancel={() => this.setState({formOpen: false})}
                   destroyOnHidden>
                <Form ref={this.formRef} initialValues={formValues} labelCol={{flex: '110px'}} onFinish={this.onFinish}>
                    <Form.Item name='id' noStyle/>

                    <Form.Item label='名称' name='name' rules={[{required: true, message: '请输入名称'}]}>
                        <Input placeholder='如 公司官网'/>
                    </Form.Item>

                    <Form.Item label='地址' name='url' rules={[{required: true, message: '请输入监控地址'}]}
                               tooltip='对外公开的地址，nginx 等反向代理后面的站点填代理地址'>
                        <Input placeholder='https://example.com'/>
                    </Form.Item>

                    <Form.Item label='请求方法' name='method'
                               tooltip='GET 可以做关键词判定；HEAD 只看状态码，无响应体'>
                        <Select options={[{label: 'GET', value: 'GET'}, {label: 'HEAD', value: 'HEAD'}]}/>
                    </Form.Item>

                    <Form.Item label='期望关键词' name='keyword'
                               tooltip='非空时响应体必须包含它才算正常，适用于识别 200 状态码下的跳转/拦截页；留空仅按状态码判定'>
                        <Input placeholder='可留空，如「登录成功」'/>
                    </Form.Item>

                    <Form.Item label='异常关键词' name='errorKeyword'
                               tooltip='响应体出现它即判定不可达，即使状态码是 200。适用于 nginx 等代理返回 200 状态码但内容是「502 Bad Gateway」等错误占位页的场景；留空则不启用'>
                        <Input placeholder='可留空，如「Service Temporarily Unavailable」'/>
                    </Form.Item>

                    <Form.Item label='检查间隔' name='intervalMinutes'>
                        <Select options={INTERVAL_OPTIONS}/>
                    </Form.Item>

                    <Form.Item label='请求超时' name='timeoutSeconds'
                               rules={[{required: true, message: '请输入超时秒数'}]}>
                        <InputNumber min={2} max={60} addonAfter='秒' style={{width: 180}}/>
                    </Form.Item>

                    <Form.Item label='启用' name='enabled' valuePropName='checked'
                               tooltip='停用后定时任务不再探测该站点'>
                        <Switch/>
                    </Form.Item>

                    <Form.Item label='邮件告警' name='notifyEmail' valuePropName='checked'
                               tooltip='连续失败满 3 次发告警邮件，恢复后发恢复通知；收件人为创建人邮箱，发件账号在「系统管理-系统参数-邮件发送」维护'>
                        <Switch/>
                    </Form.Item>
                </Form>
            </Modal>

            <Drawer title={`检测历史 - ${this.state.logRecord?.name ?? ''}`}
                    width={860}
                    open={!!this.state.logRecord}
                    onClose={() => this.setState({logRecord: null})}
                    destroyOnHidden>
                {this.state.logRecord && <LogList record={this.state.logRecord}/>}
            </Drawer>
        </Page>
    }

}

/**
 * 单个监控项的检测历史：最近在前，只读不需要搜索；抽屉关闭即销毁。
 * 固定按 monitorId 过滤，不再提供任何输入。
 */
function LogList({record}) {

    const columns = [
        {title: '时间', dataIndex: 'createTime', width: 165},
        {title: '结果', dataIndex: 'success', width: 80,
            render: v => v ? <Tag color='green'>正常</Tag> : <Tag color='red'>异常</Tag>},
        {title: '状态码', dataIndex: 'statusCode', width: 80, render: v => v ?? '-'},
        {title: '耗时', dataIndex: 'latencyMs', width: 90, render: v => v == null ? '-' : `${v}ms`},
        {title: '失败原因', dataIndex: 'error', render: (v, row) => v
            ? <Tooltip title={v}><Typography.Text ellipsis style={{maxWidth: 440}}>{v}</Typography.Text></Tooltip>
            : (row.success ? <Typography.Text type='secondary'>-</Typography.Text> : '-')},
    ]

    return <ProTable
        search={false}
        request={(params) => HttpClient.get('admin/site-monitor/logs', {monitorId: record.id, ...params})}
        columns={columns}
        pagination={{showSizeChanger: false}}
        scroll={{y: 560}}
    />
}
