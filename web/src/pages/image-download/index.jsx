import {Button, Card, Drawer, Form, Input, Select, Space, Tag, Tooltip, Typography, message} from 'antd'
import {DeleteOutlined, DownloadOutlined} from '@ant-design/icons'
import React from 'react'
import {HttpClient, Page, Perm, PermActions, ProTable, UrlUtils} from '@jiangood/open-admin'
import LogView from '../../components/LogView'
import {formatBytes, formatTime} from '../../components/container/utils'

/**
 * 镜像下载 tar：在指定主机上拉取镜像，再用 docker save 打成 tar 包。
 * 每个任务独立落库、独立存档；下载完成后文件保留，用户在表格中勾选后批量清理。
 */
const POLL_INTERVAL = 10000

const STATUS_META = {
    RUNNING: {label: '运行中', color: 'processing'},
    SUCCESS: {label: '已完成', color: 'success'},
    FAILED: {label: '失败', color: 'error'},
    CLEANING: {label: '清理中', color: 'orange'},
    CLEANED: {label: '已清理', color: 'default'},
}

export const pageMeta = {
    title: '镜像下载',
}

export default class extends React.Component {

    tableRef = React.createRef()

    state = {
        hostOptions: [],
        logId: null,
        logVisible: false,
        activeTask: null,
        submitting: false,
        cleaning: false,
    }

    componentDidMount() {
        this.loadHosts()
        this.timer = setInterval(this.poll, POLL_INTERVAL)
    }

    componentWillUnmount() {
        this.unmounted = true
        clearInterval(this.timer)
    }

    loadHosts = () => {
        HttpClient.get('admin/host/options')
            .then(rs => {
                if (!this.unmounted) this.setState({hostOptions: rs.data || []})
            })
            .catch(() => {
            })
    }

    /** 页面可见时定期刷新，多人提交的任务状态也会及时出现。 */
    poll = () => {
        if (!document.hidden) this.reload()
    }

    reload = () => {
        this.tableRef.current?.reload()
    }

    request = params => {
        return HttpClient.get('admin/image-download/page', params)
    }

    submit = values => {
        this.setState({submitting: true})
        HttpClient.post('admin/image-download/download', values).then(rs => {
            const logId = rs.data
            this.setState({submitting: false, logId, logVisible: true, activeTask: null})
            this.reload()
            this.loadTask(logId)
        }).catch(() => {
            // 参数校验、无权限等请求未成功，不会产生记录，直接恢复按钮。
            this.setState({submitting: false})
        })
    }

    openLog = (logId, record = null) => {
        this.setState({logId, logVisible: true, activeTask: record})
        this.loadTask(logId)
    }

    loadTask = logId => {
        if (!logId) return
        HttpClient.get('admin/image-download/task', {logId}, {toastError: false}).then(rs => {
            if (!this.unmounted && this.state.logId === logId) {
                this.setState({activeTask: rs.data || null})
            }
        }).catch(() => {
        })
    }

    /** 日志流关闭表示后台任务结束：立即刷新表格与抽屉里的下载入口。 */
    onLogClose = () => {
        this.setState({submitting: false})
        this.reload()
        this.loadTask(this.state.logId)
    }

    /** 同源普通链接，浏览器直接流式写盘（不走 axios blob，避免大镜像撑爆内存）。 */
    downloadUrl = logId => {
        return UrlUtils.contextPath('/admin/image-download/tar')
            + '?logId=' + encodeURIComponent(logId)
    }

    onDownload = () => {
        message.success('下载已开始，归档会保留到手动清理')
    }

    clean = selectedRowKeys => {
        this.setState({cleaning: true})
        HttpClient.post('admin/image-download/clean', {logIds: selectedRowKeys}).then(rs => {
            message.success(rs.message || '清理完成')
            this.tableRef.current?.clearSelection()
            this.reload()
            this.loadTask(this.state.logId)
        }).finally(() => this.setState({cleaning: false}))
    }

    renderStatus = (_, record) => {
        const meta = STATUS_META[record.status] || {label: record.status || '-', color: 'default'}
        const missing = record.status === 'SUCCESS' && !record.ready
        const tag = <Tag color={missing ? 'orange' : meta.color}>{missing ? '文件缺失' : meta.label}</Tag>
        return record.error ? <Tooltip title={record.error}>{tag}</Tooltip> : tag
    }

    columns = [
        {title: '开始时间', dataIndex: 'createTime', width: 170, render: formatTime},
        {
            title: '镜像', dataIndex: 'image', width: 260, ellipsis: true,
            render: value => value ? <Tooltip title={value}>{value}</Tooltip> : '-',
        },
        {title: '执行主机', dataIndex: 'hostName', width: 140, render: value => value || '-'},
        {title: '触发人', dataIndex: 'triggerUser', width: 110, render: value => value || '-'},
        {title: '状态', dataIndex: 'status', width: 110, render: this.renderStatus},
        {
            title: '归档', dataIndex: 'fileName', width: 280,
            render: (_, record) => record.fileName ? (
                <div style={{minWidth: 0}}>
                    <div style={{overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'}}
                         title={record.fileName}>
                        {record.fileName}
                    </div>
                    <Typography.Text type='secondary'
                                      style={{fontSize: 12, display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'}}>
                        {formatBytes(record.size)}{record.path ? ` · ${record.path}` : ''}
                    </Typography.Text>
                </div>
            ) : '-',
        },
        {title: '结束时间', dataIndex: 'endTime', width: 170, render: value => value ? formatTime(value) : '-'},
        {
            title: '操作', dataIndex: 'option', valueType: 'option', width: 150, fixed: 'right',
            render: (_, record) => (
                <Space size={4}>
                    <PermActions size='small' actions={[
                        {label: '日志', perm: 'image-download:download', onClick: () => this.openLog(record.logId, record)},
                    ]}/>
                    <Perm code='image-download:download'>
                        <Tooltip title={record.ready ? '' : '归档尚未生成或已被清理'}>
                            <Button type='link' size='small' icon={<DownloadOutlined/>}
                                    disabled={!record.ready} href={record.ready ? this.downloadUrl(record.logId) : undefined}
                                    onClick={this.onDownload}>
                                下载
                            </Button>
                        </Tooltip>
                    </Perm>
                </Space>
            ),
        },
    ]

    render() {
        const {hostOptions, logId, logVisible, activeTask, submitting, cleaning} = this.state
        return <Page padding>
            <Card title='新建下载' className='page-card' style={{marginBottom: 16}}>
                <Form style={{maxWidth: 560}} labelCol={{flex: '80px'}}
                      preserve={false} onFinish={this.submit}>
                    <Form.Item label='镜像地址' name='image'
                               rules={[{required: true, message: '请输入镜像地址'}]}
                               extra='支持公共镜像与已配置凭据的私有仓库，例如 mysql:8、nginx:1.27-alpine'>
                        <Input placeholder='mysql:8'/>
                    </Form.Item>

                    <Form.Item label='执行主机' name='hostId'
                               rules={[{required: true, message: '请选择执行主机'}]}
                               extra='拉取与打包在所选主机上执行，tar 统一保存在平台服务器，可在本页直接下载。'>
                        <Select options={hostOptions} showSearch optionFilterProp='label'
                                placeholder='请选择执行主机'/>
                    </Form.Item>

                    <Form.Item>
                        <Perm code='image-download:download'>
                            <Button type='primary' htmlType='submit' loading={submitting}>
                                开始下载
                            </Button>
                        </Perm>
                        <Typography.Text type='secondary' style={{marginLeft: 12}}>
                            任务与归档独立保存，下载完成后不会自动删除；请在下方勾选记录批量清理。
                        </Typography.Text>
                    </Form.Item>
                </Form>
            </Card>

            <ProTable
                actionRef={this.tableRef}
                rowKey='logId'
                rowSelection={{}}
                defaultPageSize={20}
                toolBarRightRender={(params, {selectedRowKeys}) => (
                    <PermActions
                        actions={[{
                            label: '批量清理' + (selectedRowKeys.length ? `(${selectedRowKeys.length})` : ''),
                            perm: 'image-download:download',
                            danger: true,
                            icon: <DeleteOutlined/>,
                            disabled: cleaning || !selectedRowKeys.length,
                            confirm: selectedRowKeys.length
                                ? `确定清理选中的 ${selectedRowKeys.length} 个归档？运行中的任务会自动跳过，记录会保留并标记为已清理。`
                                : '',
                            onClick: () => this.clean(selectedRowKeys),
                        }]}
                    />
                )}
                request={this.request}
                columns={this.columns}
                searchFormRender={() => (
                    <>
                        <Form.Item label='关键词' name='searchText'>
                            <Input placeholder='镜像 / 主机 / 文件名 / 任务 ID' allowClear/>
                        </Form.Item>
                        <Form.Item label='状态' name='status'>
                            <Select style={{width: 140}} allowClear placeholder='全部状态' options={[
                                {value: 'RUNNING', label: '运行中'},
                                {value: 'SUCCESS', label: '已完成'},
                                {value: 'FAILED', label: '失败'},
                                {value: 'CLEANING', label: '清理中'},
                                {value: 'CLEANED', label: '已清理'},
                            ]}/>
                        </Form.Item>
                    </>
                )}
            />

            <Drawer title='下载日志' size={860} open={logVisible}
                    mask={{closable: false}}
                    extra={activeTask?.ready ? (
                        <Perm code='image-download:download'>
                            <Button type='primary' size='small' icon={<DownloadOutlined/>}
                                    href={this.downloadUrl(logId)} onClick={this.onDownload}>
                                下载 tar
                            </Button>
                        </Perm>
                    ) : null}
                    onClose={() => this.setState({logVisible: false})}>
                {/* 抽屉关闭也保持日志连接，任务结束时才能刷新出下载入口 */}
                {logId
                    ? <LogView key={logId} url={'/admin/ws/task-log/' + logId}
                               websocket={true} onClose={this.onLogClose}/>
                    : null}
            </Drawer>
        </Page>
    }

}
