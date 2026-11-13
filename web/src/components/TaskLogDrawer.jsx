import React from 'react'
import {Drawer} from 'antd'
import LogView from './LogView'

/**
 * 统一任务日志抽屉：支持 ws/http 传输。
 * - ws：连接 wsUrl（默认 /admin/ws/task-log/{logId}）实时 tail
 * - http：当前实现回退为 ws（docker-admin 以 WS 为主）；如需 HTTP 可传入自定义查看方式
 */
export default class extends React.Component {

    render() {
        const {open, logId, title, onClose, onEnd, width, transport = 'ws', wsUrl} = this.props
        const bodyHeight = typeof window !== 'undefined' ? window.innerHeight - 55 : 600
        const logHeight = bodyHeight - 24
        const url = wsUrl || (logId ? '/admin/ws/task-log/' + logId : null)
        return <Drawer title={title || '任务日志'} size={width || 860} open={open}
                       mask={{closable: false}}
                       onClose={onClose}
                       styles={{body: {padding: 12, height: bodyHeight, overflow: 'hidden'}}}>
            {open && logId && url
                ? <LogView key={logId}
                           url={url}
                           websocket={true}
                           height={logHeight}
                           onClose={onEnd}/>
                : null}
        </Drawer>
    }
}
