import {Button, Card, Descriptions, Drawer, Popconfirm, Space, Spin, Table, Tabs, Typography} from 'antd'
import {CloudSyncOutlined, DownloadOutlined} from '@ant-design/icons'
import React from 'react'
import {HttpClient, Page, PageUtils, PermActions, UrlUtils} from "@jiangood/open-admin"
import LogView from '../../components/LogView'
import RemoteTagSyncModal from '../../components/RemoteTagSyncModal'
import {formatBytes} from "../../components/container/utils"
import {sourceTag} from "./index"


export default class extends React.Component {

    tagSyncModalRef = React.createRef()

    state = {
        repo: null,
        tags: [],
        apps: [],
        // 导出：日志抽屉与下载状态
        exportTag: null,
        logId: null,
        logVisible: false,
        ready: false,
        readySize: 0,
    }

    componentDidMount() {
        this.id = PageUtils.currentParams().id
        this.loadRepo()
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
            }
        })
    }

    exportConfirmTitle = record => {
        const size = (record.size === null || record.size === undefined || record.size === '')
            ? null : formatBytes(record.size)
        return size
            ? `将从注册中心拉取该镜像（约 ${size}）后导出，大镜像较慢，是否继续？`
            : '将从注册中心拉取该镜像后导出，大镜像较慢，是否继续？'
    }

    exportImage = record => {
        HttpClient.postForm('admin/image-repo/export/start', {
            imageUrl: record.imageUrl,
            tag: record.tag,
        }).then(rs => {
            this.setState({
                exportTag: record,
                logId: rs.data,
                logVisible: true,
                ready: false,
                readySize: 0,
            })
        })
    }

    /**
     * 日志推送结束后（任务结束或断开）查询任务是否产出了可下载的文件。
     */
    onExportFinished = () => {
        const {logId} = this.state
        if (!logId) {
            return
        }
        HttpClient.get('admin/image-repo/export/status', {logId}).then(rs => {
            const data = rs.data || {}
            this.setState({ready: !!data.ready, readySize: data.size || 0})
        }).catch(() => {
        })
    }

    /**
     * 下载地址：同源普通链接，浏览器直接流式写盘（不走 axios blob，避免大镜像撑爆内存）。
     */
    downloadUrl = () => {
        const {logId, exportTag} = this.state
        const params = new URLSearchParams({
            logId,
            imageUrl: exportTag?.imageUrl || '',
            tag: exportTag?.tag || '',
        })
        return UrlUtils.contextPath('/admin/image-repo/export/download') + '?' + params.toString()
    }

    render() {
        const {repo, tags, apps} = this.state
        if (!repo) {
            return <Page padding><Spin/></Page>
        }

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
                    <Descriptions.Item label='仓库名'>{repo.name}</Descriptions.Item>
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
                                    title: '操作', width: 90,
                                    render: (_, record) => (
                                        <PermActions>
                                            <Popconfirm perm='image-repo:export'
                                                        title={this.exportConfirmTitle(record)}
                                                        onConfirm={() => this.exportImage(record)}>
                                                <Button size='small'>导出</Button>
                                            </Popconfirm>
                                        </PermActions>
                                    ),
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
                                {title: '应用', dataIndex: 'name'},
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

            <Drawer title='导出日志' size={760}
                    open={this.state.logVisible}
                    mask={{closable: false}}
                    onClose={() => this.setState({logVisible: false})}
                    extra={this.state.ready ? (
                        <Button type='primary' icon={<DownloadOutlined/>} href={this.downloadUrl()}>
                            下载{this.state.readySize ? `（${formatBytes(this.state.readySize)}）` : ''}
                        </Button>
                    ) : null}>
                <Typography.Paragraph type='secondary'>
                    正在从注册中心拉取并打包，进度见下方日志。完成后出现「下载」按钮，
                    导出的 tar 可用 <Typography.Text code>docker load -i</Typography.Text> 导入；下载后临时文件即删除。
                </Typography.Paragraph>
                {this.state.logId
                    ? <LogView key={this.state.logId}
                               url={'/admin/ws/image-export-log/' + this.state.logId}
                               websocket={true}
                               height={460}
                               onClose={this.onExportFinished}/>
                    : null}
            </Drawer>
        </Page>
    }
}
