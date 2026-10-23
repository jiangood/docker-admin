import {Alert, Button, Descriptions, Modal, Space, Spin, Typography} from 'antd'
import {DownloadOutlined} from '@ant-design/icons'
import React from 'react'
import {HttpClient, UrlUtils} from '@jiangood/open-admin'
import LogView from './LogView'
import {formatBytes} from './container/utils'

/**
 * 主机详情页的镜像导出弹窗。
 * <p>
 * 点「导出」先查该镜像最近一次任务：没有任务就展示说明与「开始导出」，
 * 已有任务则展示实时日志、进度、tar 在主机上的保存路径，以及下载入口。
 * 大镜像直接在主机上打包（SSH 走远端 docker save），下载时再按需读取，避免平台侧重复落盘。
 */
const POLL_INTERVAL = 2000

/** 写入速度：同时给出每秒与每分钟，例如 80 MB/s（4.7 GB/min）。 */
function formatSpeed(speed) {
    if (!speed || speed <= 0) {
        return null
    }
    return `${formatBytes(speed)}/s（${formatBytes(speed * 60)}/min）`
}

export default class HostImageExportModal extends React.Component {

    state = {
        open: false,
        record: null,
        task: null,
        loading: false,
        starting: false,
        error: null,
    }

    pollTimer = null

    componentWillUnmount() {
        this.stopPoll()
    }

    show = record => {
        this.stopPoll()
        this.setState({open: true, record, task: null, loading: true, starting: false, error: null}, () => {
            this.loadTask(false)
        })
    }

    close = () => {
        this.stopPoll()
        this.setState({open: false})
    }

    /**
     * 查询该镜像最近一次的导出状态。
     * 返回 null 表示没有任务；undefined 表示查询失败。
     */
    loadTask = silent => {
        const {record} = this.state
        if (!record) {
            return Promise.resolve(undefined)
        }
        const options = {toastError: false}
        return HttpClient.get('admin/host/exportImage/status', {
            id: this.props.hostId,
            image: record.id,
        }, options).then(rs => {
            const task = rs.data || null
            this.setState({task, loading: false, error: null})
            if (task && task.status === 'RUNNING') {
                this.startPoll()
            } else {
                this.stopPoll()
            }
            return task
        }).catch(e => {
            this.setState({loading: false})
            // 轮询中的偶发失败不打断已有任务，日志 WebSocket 仍在推送
            if (!silent) {
                this.setState({error: e.message || '无法获取导出状态'})
            }
            this.stopPoll()
            return undefined
        })
    }

    startPoll = () => {
        if (!this.pollTimer) {
            this.pollTimer = setInterval(() => this.loadTask(true), POLL_INTERVAL)
        }
    }

    stopPoll = () => {
        if (this.pollTimer) {
            clearInterval(this.pollTimer)
            this.pollTimer = null
        }
    }

    startExport = () => {
        const {record, starting} = this.state
        if (!record || starting) {
            return
        }
        this.setState({starting: true, error: null})
        HttpClient.postForm('admin/host/exportImage/start', {
            id: this.props.hostId,
            image: record.id,
        }).then(() => {
            this.setState({starting: false})
            // 后端已在请求线程登记任务，立即拉一次即可拿到 logId
            this.loadTask(false)
            this.startPoll()
        }).catch(e => {
            this.setState({starting: false, error: e.message || '无法开始导出'})
        })
    }

    retry = () => {
        this.setState({error: null})
        if (this.state.task) {
            this.loadTask(false)
        } else {
            this.startExport()
        }
    }

    onLogClose = () => {
        // 日志流结束即任务结束：立刻确认一次，让「下载」按钮出现，不等下一轮轮询
        this.loadTask(true)
    }

    downloadUrl = () => {
        const task = this.state.task
        return UrlUtils.contextPath('/admin/host/exportImage/download')
            + '?logId=' + encodeURIComponent(task.logId)
    }

    title = () => {
        const {record} = this.state
        if (!record) {
            return '导出镜像'
        }
        const tag = (record.repoTags || []).find(t => t && t !== '<none>:<none>')
        return '导出镜像 - ' + (tag || record.idShort || record.id)
    }

    footer = () => {
        const {task, loading, starting, error} = this.state
        const close = <Button key='close' onClick={this.close}>关闭</Button>

        if (task && task.ready) {
            return [
                close,
                <Button key='download' type='primary' icon={<DownloadOutlined/>} href={this.downloadUrl()}>
                    下载 tar{task.size ? `（${formatBytes(task.size)}）` : ''}
                </Button>,
            ]
        }
        if (task && task.status === 'FAILED') {
            return [close, <Button key='retry' type='primary' onClick={this.startExport}>重新导出</Button>]
        }
        if (task && task.status === 'SUCCESS' && !task.ready) {
            return [close, <Button key='retry' type='primary' onClick={this.startExport}>重新导出</Button>]
        }
        if (error) {
            return [close, <Button key='retry' type='primary' loading={loading || starting}
                                   onClick={this.retry}>重试</Button>]
        }
        if (!task && !loading) {
            return [
                close,
                <Button key='start' type='primary' loading={starting}
                        onClick={this.startExport}>开始导出</Button>,
            ]
        }
        return [close]
    }

    renderIdle = () => {
        const {loading, starting, error, record} = this.state
        if (loading) {
            return <Space direction='vertical' align='center' style={{width: '100%', padding: 24}}>
                <Spin/>
                <Typography.Text type='secondary'>正在查询导出状态…</Typography.Text>
            </Space>
        }
        if (starting) {
            return <Space direction='vertical' align='center' style={{width: '100%', padding: 24}}>
                <Spin/>
                <Typography.Text type='secondary'>正在派发导出任务…</Typography.Text>
            </Space>
        }
        if (error) {
            return <Space direction='vertical' size='middle' style={{width: '100%'}}>
                <Alert type='error' showIcon message='导出失败' description={error}/>
                {record ? <Typography.Text type='secondary'>
                    镜像：{record.repoTags?.[0] || record.id}
                </Typography.Text> : null}
            </Space>
        }
        return <Space direction='vertical' size='middle' style={{width: '100%'}}>
            <Alert type='info' showIcon message='准备导出'
                   description={<>
                       将在该主机上执行 <Typography.Text code>docker save</Typography.Text>，
                       生成 tar 后可在本弹窗下载，也可按显示的路径直接取用。
                       {record?.size ? ` 该镜像约 ${formatBytes(record.size)}。` : ''}
                   </>}/>
        </Space>
    }

    renderTask = () => {
        const {task} = this.state
        const running = task.status === 'RUNNING'
        const failed = task.status === 'FAILED'
        const success = task.status === 'SUCCESS' && task.ready

        const message = running
            ? '正在主机上执行 docker save，已写入大小与速度会实时更新，详见下方日志'
            : failed
                ? '导出失败，原因见下方日志，可重新导出'
                : success
                    ? '导出完成，可点击右下角「下载 tar」保存到本机'
                    : '导出已完成，但 tar 已不存在，请重新导出'

        const pathLabel = task.remote ? `主机路径（${task.hostName || ''}）` : '保存位置'
        const pathHint = task.remote
            ? 'tar 保存在执行主机上；下载时经 SFTP 读取，不在平台侧重复落盘。tar 不会自动删除，请按需清理。'
            : 'tar 保存在平台服务器；若该主机为 TCP 连接，docker API 无法直接写远端文件。tar 不会自动删除，请按需清理。'
        const speedText = formatSpeed(task.speed)

        return <Space direction='vertical' size='middle' style={{width: '100%'}}>
            <Alert type={failed ? 'error' : success ? 'success' : 'info'} showIcon message={message}/>

            <Space size={8} align='center'>
                {running ? <Spin size='small'/> : null}
                <Typography.Text type={failed ? 'danger' : success ? 'success' : undefined}>
                    {task.size ? `已写入 ${formatBytes(task.size)}` : '准备中…'}
                    {speedText ? ` · ${success ? '平均速度' : '速度'} ${speedText}` : ''}
                </Typography.Text>
            </Space>

            <Descriptions size='small' column={1}
                          items={[
                              {
                                  key: 'path', label: pathLabel,
                                  children: task.path
                                      ? <Typography.Text code copyable
                                                         style={{wordBreak: 'break-all'}}>{task.path}</Typography.Text>
                                      : '生成中…',
                              },
                              {
                                  key: 'hint', label: '说明',
                                  children: <Typography.Text type='secondary'>{pathHint}</Typography.Text>,
                              },
                          ]}/>

            <LogView key={task.logId}
                     url={'/admin/ws/host-image-export-log/' + task.logId}
                     websocket={true}
                     height={320}
                     onClose={this.onLogClose}/>
        </Space>
    }

    render() {
        const {open, task} = this.state
        return <Modal open={open}
                      width={760}
                      title={this.title()}
                      onCancel={this.close}
                      mask={{closable: false}}
                      destroyOnHidden
                      footer={this.footer()}>
            {task ? this.renderTask() : this.renderIdle()}
        </Modal>
    }

}
