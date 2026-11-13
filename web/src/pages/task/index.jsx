import {Button, Form, Input, Modal, Select, Tag, Tooltip, message} from 'antd'
import React from 'react'
import {DateUtils, HttpClient, Page, PermActions, PermUtils, ProTable} from '@jiangood/open-admin'
import TaskLogDrawer from '../../components/TaskLogDrawer'

/**
 * 任务类型的展示名与配色。与后端 TaskLog.type / LongTaskRunner#start 的取值对应；
 * 未登记的类型回退为原样展示。
 */
const TYPE_LABELS = {
    'agent-deploy': {label: 'Agent 部署', color: 'purple'},
    deploy: {label: '应用部署', color: 'blue'},
    build: {label: '项目构建', color: 'geekblue'},
    'build-test': {label: '构建测试', color: 'cyan'},
    'image-sync': {label: '镜像同步', color: 'green'},
    'image-export': {label: '镜像导出', color: 'orange'},
    'image-import': {label: '镜像导入', color: 'gold'},
    'image-download': {label: '镜像下载', color: 'volcano'},
    'host-image-export': {label: '主机镜像导出', color: 'magenta'},
}

const STATUS_LABELS = {
    RUNNING: {label: '运行中', color: 'processing'},
    SUCCESS: {label: '成功', color: 'success'},
    FAILED: {label: '失败', color: 'error'},
}

const typeTagOf = type => {
    const meta = TYPE_LABELS[type]
    return meta ? <Tag color={meta.color}>{meta.label}</Tag> : <Tag>{type}</Tag>
}

const statusTagOf = status => {
    const meta = STATUS_LABELS[status]
    return meta ? <Tag color={meta.color}>{meta.label}</Tag> : <Tag>{status}</Tag>
}

/**
 * 任务中心：统一查看后台长任务的实时日志与历史结果。
 * <p>
 * 日志正文按 logId 连 {@code /admin/ws/task-log/{logId}}（鉴权按任务记录的 permission 字段）；
 * 运行中的任务可请求取消（关掉任务登记的可中断资源，如 docker 拉取流）。
 * <p>
 * 运行中的任务在页面可见时每 10 秒刷新一次列表，任务结束状态会自动出现。
 */
export const pageMeta = {
    title: '任务中心',
}

export default class extends React.Component {

    tableRef = React.createRef()

    state = {
        logId: null,
        logVisible: false,
    }

    componentDidMount() {
        this.timer = setInterval(this.poll, 10000)
    }

    componentWillUnmount() {
        clearInterval(this.timer)
    }

    poll = () => {
        if (!document.hidden) {
            this.tableRef.current?.reload()
        }
    }

    columns = [
        {title: '开始时间', dataIndex: 'createTime', width: 170},
        {title: '类型', dataIndex: 'type', width: 110, render: typeTagOf},
        {
            title: '任务', dataIndex: 'title', ellipsis: true,
            render: (v, row) => v || row.logId,
        },
        {title: '触发人', dataIndex: 'triggerUser', width: 110, render: v => v || '-'},
        {
            title: '状态', dataIndex: 'status', width: 90,
            render: (_, row) => row.error
                ? <Tooltip title={row.error}>{statusTagOf(row.status)}</Tooltip>
                : statusTagOf(row.status),
        },
        {title: '结束时间', dataIndex: 'endTime', width: 170, render: v => v || '-'},
        {
            title: '耗时', dataIndex: 'timeSpend', width: 100,
            render: (v, row) => row.status === 'RUNNING'
                ? '-'
                : v != null ? DateUtils.friendlyTotalTime(v) : '-',
        },
        {
            title: '操作', dataIndex: 'option', valueType: 'option', width: 120, fixed: 'right',
            render: (_, row) => <>
                <PermActions size='small' actions={[
                    {label: '日志', perm: 'task:view', onClick: () => this.openLog(row.logId)},
                ]}/>
                {row.status === 'RUNNING' && PermUtils.hasPermission('task:cancel') &&
                    <Button type='link' size='small' danger onClick={() => this.confirmCancel(row)}>取消</Button>}
            </>,
        },
    ]

    confirmCancel = row => {
        Modal.confirm({
            title: '确定取消该任务？',
            okText: '确定',
            cancelText: '取消',
            onOk: () => this.cancel(row),
        })
    }

    cancel = row => {
        return HttpClient.post('admin/sys/task/cancel', null, {logId: row.logId})
            .then(rs => {
                message.success((rs && rs.message) || '已请求取消')
            })
            .finally(() => this.reload())
    }

    reload = () => {
        this.tableRef.current?.reload()
    }

    openLog = logId => {
        if (logId) {
            this.setState({logId, logVisible: true})
        }
    }

    /**
     * 关闭抽屉后任务可能刚好结束，刷新一次列表拿到终态。
     */
    closeLog = () => {
        this.setState({logVisible: false})
        this.reload()
    }

    render() {
        const {logId, logVisible} = this.state
        return <Page padding>
            <ProTable actionRef={this.tableRef}
                      searchInline
                      searchFormRender={() => <>
                          <Form.Item name='searchText' noStyle>
                              <Input placeholder='搜索任务' allowClear/>
                          </Form.Item>
                          <Form.Item name='status' noStyle>
                              <Select placeholder='状态' allowClear style={{width: 120}} options={[
                                  {value: 'RUNNING', label: '运行中'},
                                  {value: 'SUCCESS', label: '成功'},
                                  {value: 'FAILED', label: '失败'},
                              ]}/>
                          </Form.Item>
                      </>}
                      request={params => HttpClient.get('admin/sys/task/page', params)}
                      columns={this.columns}/>

            <TaskLogDrawer open={logVisible} logId={logId}
                           title='任务日志'
                           onClose={this.closeLog}
                           onEnd={this.reload}/>
        </Page>
    }
}
