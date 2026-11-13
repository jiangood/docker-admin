import React from 'react'
import {Drawer} from 'antd'
import LogView from './LogView'

/**
 * 通用任务日志抽屉：按 logId 连 {@code /admin/ws/task-log/{logId}} 实时 tail，
 * 任务结束（WS 关闭）后日志留在抽屉里可回看，并触发 {@code onEnd}（父级刷新列表 / 状态）。
 * <p>
 * 任务中心、Agent 部署等任何拿到 logId 的地方都可用：
 * {@code <TaskLogDrawer open={...} logId={...} onEnd={...} onClose={...}/>}
 */
export default class extends React.Component {

    render() {
        const {open, logId, title, onClose, onEnd, width} = this.props
        const bodyHeight = typeof window !== 'undefined' ? window.innerHeight - 55 : 600
        const logHeight = bodyHeight - 24
        return <Drawer title={title || '任务日志'} size={width || 860} open={open}
                       mask={{closable: false}}
                       onClose={onClose}
                       styles={{body: {padding: 12, height: bodyHeight, overflow: 'hidden'}}}>
            {open && logId
                ? <LogView key={logId}
                           url={'/admin/ws/task-log/' + logId}
                           websocket={true}
                           height={logHeight}
                           onClose={onEnd}/>
                : null}
        </Drawer>
    }
}
