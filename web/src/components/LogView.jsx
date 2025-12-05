import {LazyLog, ScrollFollow} from "@melloware/react-logviewer";
import React from "react";
import {DeviceUtils} from "@jiangood/springboot-admin-starter";
import {Alert} from "antd";

/**
 * https://github.com/melloware/react-logviewer
 */
export default class extends React.Component {

    render() {
        let url = this.props.url;
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = DeviceUtils.getWebsocketBaseUrl() + url
        }


        return <div style={{height: 600}}>
            <ScrollFollow

                startFollowing={true}
                render={({follow, onScroll}) => (
                    <LazyLog url={url}
                             follow={follow}
                             fetchOptions={{credentials: 'include'}}
                             websocket={true}
                             selectableLines={true}
                             onScroll={onScroll}/>
                )}
            />
        </div>

    }
}
