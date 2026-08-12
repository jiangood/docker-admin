import {LazyLog, ScrollFollow} from "@melloware/react-logviewer";
import React from "react";
import {UrlUtils} from "@jiangood/open-admin";
import {Alert} from "antd";

/**
 * https://github.com/melloware/react-logviewer
 */
export default class extends React.Component {

    render() {
        let {url, websocket} = this.props;
        if (!url.startsWith("ws://") && !url.startsWith("wss://") && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = UrlUtils.contextPath(url)
            if (websocket) {
                url = UrlUtils.getWebsocketBaseUrl() + url
            }
        }


        return <div style={{height: 500}}>
            <ScrollFollow
                startFollowing={true}
                render={({follow, onScroll}) => {

                    return (
                        <LazyLog url={url}
                                 follow={follow}
                                 fetchOptions={{credentials: 'include'}}
                                 websocket={websocket}
                                 selectableLines={true}
                                 onScroll={onScroll}/>
                    );
                }}
            />
        </div>

    }
}
