import {LazyLog, ScrollFollow} from "@melloware/react-logviewer";
import React from "react";
import {DeviceUtils} from "@jiangood/admin-spring-boot-starter";
import {Alert} from "antd";

/**
 * https://github.com/melloware/react-logviewer
 */
export default class extends React.Component {

    render() {
        let {url, websocket} = this.props;
        if (websocket && !url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = DeviceUtils.getWebsocketBaseUrl() + url
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
