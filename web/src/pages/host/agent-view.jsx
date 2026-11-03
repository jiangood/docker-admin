import React from 'react'
import {Result, Typography} from 'antd'
import {HttpClient, Page, PageUtils} from "@jiangood/open-admin"
import HostAgentTab from "../../components/host/HostAgentTab"

export const pageMeta = {
    title: '主机 Agent',
}

/**
 * 主机 Agent 独立页面：通过 ?id=<hostId> 打开，内容就是原来的 Agent Tab。
 * <p>
 * 入口有两个：主机列表「操作」列的「Agent」按钮，以及主机详情页「Agent」Tab 里的链接。
 * 终端与上传 / 下载都在子组件 {@link HostAgentTab} 里，本页只负责取主机 id 和显示主机名。
 */
export default class extends React.Component {

    state = {
        id: null,
        host: null,
    }

    componentDidMount() {
        const {id} = PageUtils.currentParams()
        this.setState({id})
        if (!id) {
            return
        }
        // 仅用于标题显示主机名，失败不影响终端连接（连接错误由 Agent 面板自己提示）
        HttpClient.get('admin/host/get', {id}, {toastError: false})
            .then(rs => this.setState({host: rs.data}))
            .catch(() => {
            })
    }

    render() {
        const {id, host} = this.state

        if (!id) {
            return <Page padding><Result status='warning' title='缺少主机 id'/></Page>
        }

        return <Page padding>
            <Typography.Title level={5} style={{margin: '0 0 12px'}}>
                {host ? host.name : '主机'} · Agent
            </Typography.Title>
            <HostAgentTab hostId={id} height='calc(100vh - 200px)'/>
        </Page>
    }
}
