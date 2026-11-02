import React from 'react'
import {Alert} from 'antd'
import {PermUtils} from '@jiangood/open-admin'
import HostAgentAlert from './HostAgentAlert'
import HostFiles from './HostFiles'
import HostCli from './HostCli'

/**
 * 主机详情的「Agent」Tab：左侧终端，右侧文件树。
 * <p>
 * 文件树只提供查看、下载、上传、编辑、删除；重命名、新建目录等操作走左侧命令行。
 * 顶层只渲染一次 HostAgentAlert，避免重复请求 agent 状态。
 * 只有一个权限时，对应面板占满整行。
 */
export default class extends React.Component {

    state = {
        status: null,
    }

    canFile = () => PermUtils.hasPermission('host:file')

    canCli = () => PermUtils.hasPermission('host:cli')

    onAgentChange = status => {
        this.setState({status})
        if (this.props.onStatus) {
            this.props.onStatus(status)
        }
    }

    render() {
        const {hostId} = this.props
        const {status} = this.state
        const canFile = this.canFile()
        const canCli = this.canCli()

        if (!canFile && !canCli) {
            return <Alert type='warning' showIcon
                          title='缺少 host:file / host:cli 权限，无法使用主机 Agent'/>
        }

        const height = 680
        let body
        if (canFile && canCli) {
            body = <div style={{display: 'flex', gap: 16, alignItems: 'stretch', height, minHeight: 480}}>
                <div style={{flex: '0 0 48%', minWidth: 0}}>
                    <HostCli hostId={hostId} status={status} height='100%'/>
                </div>
                <div style={{flex: 1, minWidth: 0}}>
                    <HostFiles hostId={hostId} status={status} height='100%'/>
                </div>
            </div>
        } else if (canFile) {
            body = <HostFiles hostId={hostId} status={status} height={height}/>
        } else {
            body = <HostCli hostId={hostId} status={status} height={height}/>
        }

        return <>
            <HostAgentAlert hostId={hostId} onChange={this.onAgentChange}/>
            {body}
        </>
    }
}
