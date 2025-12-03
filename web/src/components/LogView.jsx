import {LazyLog, ScrollFollow} from "react-lazylog";
import React from "react";
import {SysUtils,DeviceUtils} from "@jiangood/springboot-admin-starter";
import {Alert} from "antd";

/**
 * https://mozilla-frontend-infra.github.io/react-lazylog/
 */
export default class extends React.Component {

    render() {
        const headers = SysUtils.getHeaders();
        let url = this.props.url;
        if(!url){
            return <Alert message='未定义组件属性url' type="error"></Alert>
        }
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            const baseUrl = DeviceUtils.getWebsocketBaseUrl();
            url = baseUrl +  url
            console.log('调整后的 ws url', url)
        }


        return <ScrollFollow
            startFollowing={true}
            render={({follow, onScroll}) => (
                <LazyLog url={url}
                         height={500}
                         follow={follow}
                         fetchOptions={{credentials: 'include', ...headers}}
                         websocket={true}
                         selectableLines={true}
                         onScroll={onScroll}/>
            )}
        />

    }
}
