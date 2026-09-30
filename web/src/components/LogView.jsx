import {LazyLog, ScrollFollow} from "@melloware/react-logviewer";
import React from "react";
import {UrlUtils} from "@jiangood/open-admin";
import {Alert} from "antd";

/**
 * https://github.com/melloware/react-logviewer
 */
export default class extends React.Component {

    render() {
        let {url, websocket, onClose} = this.props;
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

        return <div style={{height}}>
            <ScrollFollow
                startFollowing={true}
                render={({follow, onScroll}) => {

                    return (
                        <LazyLog url={url}
                                 height={height}
                                 follow={follow}
                                 fetchOptions={{credentials: 'include'}}
                                 websocket={websocket}
                                 websocketOptions={{onClose}}
                                 selectableLines={true}
                                 onScroll={onScroll}/>
                    );
                }}
            />
        </div>

    }
}
