/**
 * 容器 / 主机文件管理的公共展示逻辑：可编辑判断、编辑器语法模式、路径拼接。
 */

/** 与后端 ContainerService.PREVIEW_MAX_BYTES 一致：超过该体积不提供在线编辑，避免截断后保存丢数据。 */
export const EDIT_MAX_BYTES = 262144

/** 常见二进制后缀，命中则不提供在线编辑。 */
export const BINARY_EXTENSIONS = new Set([
    'zip', 'tar', 'gz', 'tgz', 'bz2', 'xz', '7z', 'rar', 'jar', 'war', 'ear',
    'class', 'exe', 'dll', 'so', 'dylib', 'bin', 'img', 'iso', 'o', 'a', 'lib', 'pyc', 'wasm',
    'png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp', 'ico', 'tif', 'tiff',
    'mp3', 'mp4', 'avi', 'mov', 'mkv', 'wav', 'flac', 'ogg', 'webm',
    'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx',
    'sqlite', 'db', 'mdb', 'woff', 'woff2', 'ttf', 'otf', 'eot',
])

/** 取小写后缀，无后缀返回空串。 */
export function extensionOf(name) {
    const base = (name || '').toLowerCase()
    const dot = base.lastIndexOf('.')
    return dot > 0 ? base.slice(dot + 1) : ''
}

/** 是否可作为文本在线编辑：非二进制后缀且体积在限制内。 */
export function isEditable(record) {
    if (record.type !== 'file') {
        return false
    }
    if (record.size != null && record.size > EDIT_MAX_BYTES) {
        return false
    }
    const ext = extensionOf(record.name)
    return !(ext && BINARY_EXTENSIONS.has(ext))
}

/** 按文件名推断 CodeMirror 语法模式；无匹配时用纯文本。 */
export function editorMode(name) {
    const base = (name || '').toLowerCase()
    const ext = extensionOf(base)
    if (base === 'dockerfile' || ext === 'dockerfile' || /^dockerfile\./.test(base)) {
        return 'dockerfile'
    }
    const map = {
        yml: 'yaml', yaml: 'yaml',
        toml: 'toml',
        properties: 'properties', ini: 'properties', conf: 'properties', cfg: 'properties',
        env: 'properties', service: 'properties', unit: 'properties', editorconfig: 'properties',
        js: 'javascript', mjs: 'javascript', cjs: 'javascript', jsx: 'javascript',
        json: {name: 'javascript', json: true},
        ts: 'javascript', tsx: 'javascript',
        xml: 'xml', html: 'xml', htm: 'xml', svg: 'xml', plist: 'xml',
        sh: 'shell', bash: 'shell', zsh: 'shell',
        py: 'python',
        sql: 'sql',
        md: 'markdown', markdown: 'markdown',
        nginx: 'nginx',
    }
    return map[ext] || 'text/plain'
}

/** 拼接容器内路径，避免出现重复斜杠。 */
export function joinPath(dir, name) {
    const d = dir || '/'
    const n = (name || '').trim()
    return (d.endsWith('/') ? d : d + '/') + n
}
