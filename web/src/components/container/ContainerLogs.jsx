import React from "react";
import {Alert} from "antd";
import LogView from "../LogView";

/**
 * 容器日志：复用 LogView，走容器日志 WebSocket。
 * url 由上层根据上下文给出（主机维度通用 WS / 应用维度 app WS）。
 */
export default function ContainerLogs({url, running, height}) {
    return <>
        {!running &&
            <Alert type='info' showIcon title='容器未运行，仅显示历史日志' style={{marginBottom: 8}}/>}
        <LogView url={url} websocket height={height || 500}/>
    </>
}
