/**
 * 归一化仓库内的相对路径：统一分隔符、去掉前导 "/" 与 "./"。
 * <p>
 * 与后端 BuildContextUtils.normalize 保持一致，用于输入框失焦时纠偏，
 * 避免用户纠结 dockerfile / 构建上下文要不要以 "/" 开头。
 */
export function normalizeRelativePath(value) {
    if (value == null) {
        return ''
    }
    let path = String(value).trim().replace(/\\/g, '/')
    while (path.startsWith('./')) {
        path = path.slice(2)
    }
    path = path.replace(/^\/+/, '').replace(/\/{2,}/g, '/')
    return path.replace(/\/+$/, '')
}

/**
 * 构建上下文下 Dockerfile 的默认路径（相对仓库根目录）。
 */
export function dockerfileForContext(context) {
    const dir = normalizeRelativePath(context)
    return dir ? dir + '/Dockerfile' : 'Dockerfile'
}

/**
 * Dockerfile 相对构建上下文的路径：位于上下文内时去掉上下文前缀，展示更紧凑。
 */
export function dockerfileInContext(context, dockerfile) {
    const dir = normalizeRelativePath(context)
    const path = normalizeRelativePath(dockerfile) || 'Dockerfile'
    if (!dir || !path.startsWith(dir + '/')) {
        return path
    }
    return path.slice(dir.length + 1)
}
