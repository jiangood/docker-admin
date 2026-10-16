import {Alert, Button, Card, Descriptions, Drawer, Popconfirm, Space, Spin, Table, Tabs, Typography} from 'antd'
import {CloudSyncOutlined, DownloadOutlined} from '@ant-design/icons'
import React from 'react'
import {HttpClient, Page, PageUtils, PermActions, UrlUtils} from "@jiangood/open-admin"
import LogView from '../../components/LogView'
import LinkButton from '../../components/LinkButton'
import RemoteTagSyncModal from '../../components/RemoteTagSyncModal'
import {formatBytes} from "../../components/container/utils"
import {sourceTag} from "./index"


/**
 * 导出状态轮询间隔（毫秒）：只在有任务进行中（或刚点了确定）时才轮询。
 */
const POLL_INTERVAL = 3000

export default class extends React.Component {

    tagSyncModalRef = React.createRef()
    pollTimer = null

    state = {
        repo: null,
        tags: [],
        apps: [],
        // 后端登记的导出任务，列表行内据此展示下载状态
        exportTasks: [],
        // 当前打开的导出抽屉：{imageUrl, tag}
        drawer: null,
        // 已点「确定」、等待后端登记任务
        starting: false,
    }

    componentDidMount() {
        this.id = PageUtils.currentParams().id
        this.loadRepo()
    }

    componentWillUnmount() {
        this.stopPoll()
    }

    loadRepo = () => {
        HttpClient.get('admin/image-repo/get', {id: this.id}).then(rs => {
            const repo = rs.data
            this.setState({repo})
            if (repo) {
                HttpClient.get('admin/image-repo/tagsDetail', {id: this.id}).then(r => {
                    this.setState({tags: r.data || []})
                })
                HttpClient.get('admin/image-repo/apps', {imageUrl: repo.imageUrl}).then(r => {
                    this.setState({apps: r.data || []})
                })
                this.pollTasks()
            }
        })
    }

    // ------------------------------------------------------------------ 导出状态

    taskOf = (imageUrl, tag) => {
        return this.state.exportTasks.find(t => t.imageUrl === imageUrl && t.tag === tag) || null
    }

    /** 当前抽屉对应的导出任务。 */
    get drawerTask() {
        const {drawer, exportTasks} = this.state
        if (!drawer) {
            return null
        }
        return exportTasks.find(t => t.imageUrl === drawer.imageUrl && t.tag === drawer.tag) || null
    }

    /**
     * 拉取整个仓库的导出状态。有任务在跑（或正在等待登记）时持续轮询，否则停下，
     * 避免无意义的请求。关闭抽屉、刷新页面回来后依然能拿到状态。
     */
    pollTasks = () => {
        const {repo, drawer} = this.state
        if (!repo) {
            return
        }
        HttpClient.get('admin/image-repo/export/tasks', {imageUrl: repo.imageUrl}).then(rs => {
            const exportTasks = rs.data || []
            const mine = drawer
                ? exportTasks.find(t => t.imageUrl === drawer.imageUrl && t.tag === drawer.tag)
                : null
            // 自己的任务已登记上，就不再用 starting 占位
            const starting = mine ? false : this.state.starting
            this.setState({exportTasks, starting})

            const running = exportTasks.some(t => t.status === 'RUNNING')
            if (running || starting) {
                this.startPoll()
            } else {
                this.stopPoll()
            }
        }).catch(() => this.stopPoll())
    }

    startPoll = () => {
        if (this.pollTimer) {
            return
        }
        this.pollTimer = setInterval(this.pollTasks, POLL_INTERVAL)
    }

    stopPoll = () => {
        if (this.pollTimer) {
            clearInterval(this.pollTimer)
            this.pollTimer = null
        }
    }

    /** 打开导出抽屉：有任务就看进度日志，没有就给「确定」。 */
    openExport = record => {
        this.setState({drawer: {imageUrl: record.imageUrl, tag: record.tag}, starting: false})
        this.pollTasks()
    }

    startExport = () => {
        const {drawer, starting} = this.state
        // 已经在派发中就不再重复发起，避免同一 tag 被点出多个导出任务
        if (starting) {
            return
        }
        this.setState({starting: true})
        HttpClient.postForm('admin/image-repo/export/start', {
            imageUrl: drawer.imageUrl,
            tag: drawer.tag,
        }).then(() => {
            // 后端已在同步线程登记任务，立刻拉一次，日志随即出现
            this.pollTasks()
        }).catch(() => this.setState({starting: false}))

        // 兜底：迟迟看不到任务登记就恢复按钮，避免一直转圈
        setTimeout(() => this.setState({starting: false}), 15000)
    }

    /**
     * 日志推送结束（任务结束或连接断开）：确认一次状态，让「可下载」立刻出现，不等轮询。
     * 断连时任务可能还没结束，之后由轮询兜底。
     */
    onExportFinished = () => {
        const {drawer, exportTasks} = this.state
        if (!drawer) {
            return
        }
        HttpClient.get('admin/image-repo/export/status', {imageUrl: drawer.imageUrl, tag: drawer.tag}).then(rs => {
            const data = rs.data || {}
            if (!data.ready) {
                return
            }
            this.setState({
                exportTasks: exportTasks.map(t =>
                    (t.imageUrl === drawer.imageUrl && t.tag === drawer.tag)
                        ? {...t, ready: true, size: data.size || 0}
                        : t),
            })
        }).catch(() => {
        })
    }

    /**
     * 手动删除已导出的 tar：不等 120 分钟后的清扫，直接把文件和登记条目清掉。
     * 删除后关抽屉（条目没了，抽屉会退回「确定，开始导出」的空态）。
     */
    deleteExport = () => {
        const task = this.drawerTask
        if (!task) {
            return
        }
        HttpClient.postForm('admin/image-repo/export/delete', {logId: task.logId}).then(() => {
            this.setState({drawer: null})
            this.pollTasks()
        })
    }

    /**
     * 下载地址：同源普通链接，浏览器直接流式写盘（不走 axios blob，避免大镜像撑爆内存）。
     */
    downloadUrl = () => {
        const task = this.drawerTask
        const params = new URLSearchParams({
            logId: task.logId,
            imageUrl: task.imageUrl,
            tag: task.tag,
        })
        return UrlUtils.contextPath('/admin/image-repo/export/download') + '?' + params.toString()
    }

    /** 还没有导出任务：说明 + 「确定」开始导出。 */
    renderExportIdle = (tag, starting) => (
        <Space direction='vertical' size='middle' style={{width: '100%'}}>
            <Typography.Paragraph type='secondary'>
                将从注册中心拉取 <Typography.Text code>{tag?.fullUrl || ''}</Typography.Text> 并打包成 tar。
                大镜像较慢，开始后下方实时显示日志，打包完成右上角出现「下载」，
                导出的 tar 可用 <Typography.Text code>docker load -i</Typography.Text> 导入。
            </Typography.Paragraph>
            {tag && tag.size
                ? <Alert type='warning' showIcon
                         message={`该镜像约 ${formatBytes(tag.size)}，拉取与打包需要一些时间`}/>
                : null}
            <Button type='primary' loading={starting} onClick={this.startExport}>确定，开始导出</Button>
        </Space>
    )

    /** 已有导出任务：进度 + 日志，成功后右上角下载，失败可重试。 */
    renderExportRunning = task => (
        <Space direction='vertical' size='middle' style={{width: '100%'}}>
            {task.status === 'RUNNING'
                ? <Alert type='info' showIcon message='正在从注册中心拉取并打包，进度见下方日志'/>
                : task.status === 'FAILED'
                    ? <Alert type='error' showIcon message='导出失败，原因见下方日志，可重新导出'/>
                    : <Alert type='success' showIcon
                             message='导出完成，点击右上角「下载」保存 tar；下载后临时文件即删除'/>}
            <LogView key={task.logId}
                     url={'/admin/ws/image-export-log/' + task.logId}
                     websocket={true}
                     height={460}
                     onClose={this.onExportFinished}/>
            {task.status === 'FAILED'
                ? <Button type='primary' loading={this.state.starting} onClick={this.startExport}>重新导出</Button>
                : null}
        </Space>
    )

    render() {
        const {repo, tags, apps, drawer, starting} = this.state
        if (!repo) {
            return <Page padding><Spin/></Page>
        }

        const drawerTag = drawer
            ? tags.find(t => t.imageUrl === drawer.imageUrl && t.tag === drawer.tag)
            : null
        const task = this.drawerTask

        return <Page padding>
            <Card className='mb-4' title={repo.imageUrl}
                   extra={
                       <PermActions>
                           <Button perm='image-repo:sync' icon={<CloudSyncOutlined/>}
                                   onClick={() => this.tagSyncModalRef.current.show(repo, tags.length)}>
                               同步标签
                           </Button>
                       </PermActions>
                   }>
                <Descriptions>
                    <Descriptions.Item label='镜像名'>{repo.name}</Descriptions.Item>
                    <Descriptions.Item label='来源'>{sourceTag(repo.source)}</Descriptions.Item>
                    <Descriptions.Item label='组织机构'>{repo.sysOrg?.name}</Descriptions.Item>
                    <Descriptions.Item label='备注'>{repo.remark}</Descriptions.Item>
                </Descriptions>
            </Card>

            <Card className='mb-4'>
                <Tabs items={[
                    {
                        key: 'tags',
                        label: `镜像标签 (${tags.length})`,
                        children: <Table
                            size='small'
                            rowKey='id'
                            pagination={false}
                            dataSource={tags}
                            columns={[
                                {title: 'tag', dataIndex: 'tag'},
                                {
                                    title: '完整地址', dataIndex: 'fullUrl',
                                    render: (v) => <Typography.Text copyable code>{v}</Typography.Text>
                                },
                                {title: '来源', dataIndex: 'source', render: (s) => sourceTag(s)},
                                {
                                    title: '构建时间', dataIndex: 'buildTime', width: 160,
                                    render: (v) => v || '-'
                                },
                                {
                                    title: '大小', dataIndex: 'size', width: 100,
                                    render: (v) => v ? formatBytes(v) : '-'
                                },
                                {
                                    title: '声明端口', dataIndex: 'exposedPorts',
                                    render: (list) => (list && list.length) ? list.join(', ') : '-'
                                },
                                {
                                    title: '声明卷', dataIndex: 'volumes',
                                    render: (list) => (list && list.length) ? list.join(', ') : '-'
                                },
                                {
                                    title: '操作', width: 180,
                                    render: (_, record) => {
                                        const t = this.taskOf(record.imageUrl, record.tag)
                                        const running = !!t && t.status === 'RUNNING'
                                        return <PermActions>
                                            <Space size={4}>
                                                <Button perm='image-repo:export' size='small'
                                                        loading={running}
                                                        onClick={() => this.openExport(record)}>
                                                    {running ? '导出中' : '导出'}
                                                </Button>
                                                {t && t.ready
                                                    ? <Typography.Text type='success' style={{fontSize: 12}}>
                                                        可下载 {formatBytes(t.size)}
                                                      </Typography.Text>
                                                    : t && t.status === 'FAILED'
                                                        ? <Typography.Text type='danger' style={{fontSize: 12}}>
                                                            失败
                                                          </Typography.Text>
                                                        : null}
                                            </Space>
                                        </PermActions>
                                    },
                                },
                            ]}
                        />
                    },
                    {
                        key: 'apps',
                        label: `关联应用 (${apps.length})`,
                        children: <Table
                            size='small'
                            rowKey='id'
                            pagination={false}
                            dataSource={apps}
                            columns={[
                                {
                                    title: '应用', dataIndex: 'name',
                                    render: (name, row) => (
                                        <LinkButton
                                            onClick={() => PageUtils.open('/app/view?id=' + row.id, '应用-' + name)}>{name}</LinkButton>
                                    )
                                },
                                {title: '主机', dataIndex: ['host', 'name']},
                                {title: '版本', dataIndex: 'imageTag'},
                            ]}
                        />
                    },
                ]}/>
                <RemoteTagSyncModal ref={this.tagSyncModalRef}
                                    onClose={this.loadRepo}
                                    onSuccess={this.loadRepo}/>
            </Card>

            <Drawer title={drawer ? `导出 ${drawer.tag}` : '导出'}
                    size={760}
                    open={!!drawer}
                    mask={{closable: false}}
                    onClose={() => this.setState({drawer: null})}
                    extra={task && task.ready ? (
                        <Space>
                            <Button type='primary' icon={<DownloadOutlined/>} href={this.downloadUrl()}
                                    onClick={() => this.setState({drawer: null})}>
                                下载{task.size ? `（${formatBytes(task.size)}）` : ''}
                            </Button>
                            <Popconfirm title='确定删除这个已导出的 tar？删除后需要重新导出才能下载'
                                        onConfirm={this.deleteExport}>
                                <Button danger>删除</Button>
                            </Popconfirm>
                        </Space>
                    ) : null}>
                {!drawer ? null : task && (task.status === 'RUNNING' || task.ready || task.status === 'FAILED')
                    ? this.renderExportRunning(task)
                    : this.renderExportIdle(drawerTag, starting)}
            </Drawer>
        </Page>
    }

}
