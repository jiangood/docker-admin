import {Button} from 'antd'
import React from 'react'

/**
 * 行内链接按钮：用于表格/面包屑里作为链接使用的场景，去掉默认内边距，
 * 让文字与相邻内容对齐。统一此写法，避免各页面重复 style={{padding: 0}}。
 * <p>
 * 仅用于不需要权限码（perm）的普通链接；需要权限控制的按钮请直接用 open-admin 的 Button。
 */
export default function LinkButton({children, ...rest}) {
    return <Button type='link' style={{padding: 0}} {...rest}>{children}</Button>
}
