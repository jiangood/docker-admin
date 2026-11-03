import React from "react";
import LogView from "../LogView";
import ContainerHint from "./ContainerHint";

/**
 * 容器日志：复用 LogView，走容器日志 WebSocket。
 * url 由上层根据上下文给出（主机维度通用 WS / 应用维度 app WS）。
 * 容器已停止时只提一句「仅历史日志」，用轻量提示而不是 Alert，不挤占日志区域。
 */
export default function ContainerLogs({url, running, height, containerKey}) {
    return <>
        <ContainerHint type='info' text='容器未运行，仅显示历史日志'
                       id={containerKey ? `logs-stopped:${containerKey}` : undefined} className='mb-2'/>
        <LogView url={url} websocket height={height || 500}/>
    </>
}
