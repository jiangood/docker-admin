import {LazyLog, ScrollFollow} from "@melloware/react-logviewer";
import React from "react";
import {UrlUtils} from "@jiangood/open-admin";
import {Button} from "antd";
import {DownloadOutlined, PauseCircleOutlined, PlayCircleOutlined} from "@ant-design/icons";

/** 工具条（按钮 + 底部间距）占用的高度，用于扣减日志区高度 */
const TOOLBAR_HEIGHT = 32

/**
 * https://github.com/melloware/react-logviewer
 * <p>
 * toolbar 打开时在日志上方显示「停止 / 继续滚动」；再传 downloadUrl 时增加「下载日志」。
 * 不传 toolbar 且不传 downloadUrl 时保持原来的纯日志视图。
 */
export default class extends React.Component {

    render() {
        let {url, websocket, onClose, toolbar, downloadUrl} = this.props;
        if (!url.startsWith("ws://") && !url.startsWith("wss://") && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = UrlUtils.contextPath(url)
            if (websocket) {
                url = UrlUtils.getWebsocketBaseUrl() + url
            }
        }


        // 必须显式给出数字高度：默认 height="auto" 时 LazyLog 在首次渲染（此时内部
        // 列表 ref 还未就绪、且日志一条未到不会触发重渲染）算出的高度为 0，
        // 界面会是一片空白，直到第一条日志到达才显示出来。
        const height = this.props.height || 500
        const showToolbar = toolbar || !!downloadUrl
        const logHeight = showToolbar ? Math.max(height - TOOLBAR_HEIGHT, 0) : height
        const href = downloadUrl ? UrlUtils.contextPath(downloadUrl) : null

        return <div style={{height}}>
            <ScrollFollow
                startFollowing={true}
                render={({follow, onScroll, startFollowing, stopFollowing}) => {

                    return (
                        <>
                            {showToolbar && <div style={{
                                display: 'flex',
                                justifyContent: 'flex-end',
                                alignItems: 'center',
                                gap: 8,
                                height: TOOLBAR_HEIGHT - 8,
                                marginBottom: 8,
                            }}>
                                <Button size='small'
                                        icon={follow ? <PauseCircleOutlined/> : <PlayCircleOutlined/>}
                                        onClick={follow ? stopFollowing : startFollowing}>
                                    {follow ? '停止滚动' : '继续滚动'}
                                </Button>
                                {href && <Button size='small' icon={<DownloadOutlined/>}
                                                 href={href}>下载日志</Button>}
                            </div>}
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
