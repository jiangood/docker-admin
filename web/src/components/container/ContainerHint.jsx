import React, {useState} from "react";
import {theme, Tooltip} from "antd";
import {CheckCircleFilled, CloseOutlined, ExclamationCircleFilled, InfoCircleFilled} from "@ant-design/icons";

/**
 * 容器组件里的轻量行内提示：单行 12px 次要灰字 + 小图标，右侧一个关闭按钮。
 * <p>
 * 与 Alert 的区别是不铺背景、不占整块版面，只在不影响操作的位置提一句；
 * 关闭状态按 id 记在 sessionStorage 里，切页签 / 重新进入同一容器时不会再打扰
 * （关掉浏览器标签页后恢复），避免同一条提示反复出现。
 * <p>
 * 出现错误、缺少权限这类需要用户处理的信息请继续用 Alert，不要用本组件。
 */
export default function ContainerHint({id, type = 'info', text, className, style, closable = true}) {
    const {token} = theme.useToken()
    const [closed, setClosed] = useState(() => !!id && loadDismissed().includes(id))
    const [lastId, setLastId] = useState(id)

    // 同一个组件实例被换到别的容器（主机详情里切换容器）时，按新容器重新读取关闭状态
    if (id !== lastId) {
        setLastId(id)
        setClosed(!!id && loadDismissed().includes(id))
    }

    if (closed || !text) {
        return null
    }

    const color = {success: token.colorSuccess, warning: token.colorWarning, error: token.colorError}[type] || token.colorInfo
    const Icon = {success: CheckCircleFilled, warning: ExclamationCircleFilled, error: ExclamationCircleFilled}[type] || InfoCircleFilled

    const close = () => {
        setClosed(true)
        if (id) {
            saveDismissed(id)
        }
    }

    return <div className={['container-hint', className].filter(Boolean).join(' ')}
                style={{color: token.colorTextSecondary, ...style}}>
        <Icon className='container-hint-icon' style={{color}}/>
        <Tooltip title={text}>
            <span className='container-hint-text'>{text}</span>
        </Tooltip>
        {closable &&
            <CloseOutlined className='container-hint-close' role='button' aria-label='关闭提示' title='关闭提示'
                           style={{color: token.colorTextTertiary}}
                           onClick={close}/>}
    </div>
}

const STORAGE_KEY = 'container-hint-dismissed'

/** 已关闭的提示 id 列表（只保留最近 50 条，避免 sessionStorage 无限增长）。 */
function loadDismissed() {
    try {
        const list = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || '[]')
        return Array.isArray(list) ? list : []
    } catch (e) {
        return []
    }
}

function saveDismissed(id) {
    try {
        const list = loadDismissed().filter(v => v !== id)
        list.push(id)
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(list.slice(-50)))
    } catch (e) {
        // 隐私模式等场景写入失败时忽略，本次关闭仍然生效
    }
}
