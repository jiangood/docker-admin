import {LazyLog, ScrollFollow} from "@melloware/react-logviewer";
import React from "react";
import {UrlUtils} from "@jiangood/open-admin";
import {Button, Space, Spin} from "antd";
import {DownloadOutlined, PauseCircleOutlined, PlayCircleOutlined} from "@ant-design/icons";

/** 工具条（按钮 + 底部间距）占用的高度，用于扣减日志区高度 */
const TOOLBAR_HEIGHT = 32

/**
 * https://github.com/melloware/react-logviewer
 * <p>
 * 传 text 时展示静态日志（按时间段查询结果），不自动滚动；否则按 url 实时 tail。
 * toolbar 打开时显示「停止 / 继续滚动」（仅实时模式）；再传 downloadUrl 时增加「下载日志」。
 * toolbarExtra 用于在工具条左侧放额外控件（如时间范围选择器）。
 */
export default class extends React.Component {

    renderToolbar = ({follow, startFollowing, stopFollowing} = {}) => {
        const {downloadUrl, onDownload, downloadLoading, toolbarExtra} = this.props
        const href = downloadUrl ? UrlUtils.contextPath(downloadUrl) : null
        const showFollow = follow !== undefined
        const showDownload = !!(onDownload || href)

        return <div style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 8,
            height: TOOLBAR_HEIGHT - 8,
            marginBottom: 8,
        }}>
            <div style={{minWidth: 0, flex: 1, display: 'flex', alignItems: 'center', gap: 8}}>
                {toolbarExtra}
            </div>
            <Space size={8}>
                {showFollow && <Button size='small'
                                       icon={follow ? <PauseCircleOutlined/> : <PlayCircleOutlined/>}
                                       onClick={follow ? stopFollowing : startFollowing}>
                    {follow ? '停止滚动' : '继续滚动'}
                </Button>}
                {showDownload && (onDownload
                    ? <Button size='small' icon={<DownloadOutlined/>} loading={downloadLoading}
                              onClick={onDownload}>下载日志</Button>
                    : <Button size='small' icon={<DownloadOutlined/>} href={href}>下载日志</Button>)}
            </Space>
        </div>
    }

    render() {
        let {url, websocket, onClose, toolbar, downloadUrl, onDownload, toolbarExtra, text, loading} = this.props;
        const isStatic = text != null
        if (url && !url.startsWith("ws://") && !url.startsWith("wss://") && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = UrlUtils.contextPath(url)
            if (websocket) {
                url = UrlUtils.getWebsocketBaseUrl() + url
            }
        }


        // 必须显式给出数字高度：默认 height="auto" 时 LazyLog 在首次渲染（此时内部
        // 列表 ref 还未就绪、且日志一条未到不会触发重渲染）算出的高度为 0，
        // 界面会是一片空白，直到第一条日志到达才显示出来。
        const height = this.props.height || 500
        const showToolbar = toolbar || !!downloadUrl || !!onDownload || !!toolbarExtra
        const logHeight = showToolbar ? Math.max(height - TOOLBAR_HEIGHT, 0) : height

        if (isStatic) {
            return <div style={{height}}>
                {showToolbar && this.renderToolbar()}
                {loading
                    ? <div style={{height: logHeight, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
                        <Spin/>
                    </div>
                    : <LazyLog text={text}
                               height={logHeight}
                               follow={false}
                               selectableLines={true}/>}
            </div>
        }

        return <div style={{height}}>
            <ScrollFollow
                startFollowing={true}
                render={({follow, onScroll, startFollowing, stopFollowing}) => {

                    return (
                        <>
                            {showToolbar && this.renderToolbar({follow, startFollowing, stopFollowing})}
                            <LazyLog url={url}
                                     height={logHeight}
                                     follow={follow}
                                     fetchOptions={{credentials: 'include'}}
                                     websocket={websocket}
                                     websocketOptions={{onClose}}
                                     selectableLines={true}
                                     onScroll={onScroll}/>
                        </>
                    );
                }}
            />
        </div>

    }
}
