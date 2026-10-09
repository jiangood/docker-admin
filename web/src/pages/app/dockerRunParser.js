/**
 * 解析 docker run 命令，转换为应用的镜像与容器配置。
 *
 * 返回结构：
 * {
 *   name: string,                 // --name 容器/应用名，可能为空
 *   imageUrl: string,             // 镜像地址（不含 tag）
 *   imageTag: string,             // 镜像版本，缺省 latest
 *   config: {                     // 对应 App.AppConfig
 *     networkMode, ports, binds, envs, cmd, extraHosts, deviceRequests
 *   },
 *   warnings: string[]            // 未识别/被忽略的参数提示
 * }
 */

/** 需要取值的参数（其后一个 token 为值） */
const VALUE_FLAGS = new Set([
    '--name', '-p', '--publish', '-v', '--volume', '-e', '--env',
    '--network', '--net', '--add-host', '--gpus', '--restart', '--entrypoint',
    '--workdir', '-w', '--user', '-u', '--hostname', '-h', '--label', '-l',
    '--env-file', '--mount', '--device', '--cap-add', '--cap-drop',
    '--memory', '-m', '--cpus', '--expose', '--pull', '--platform',
    '--ulimit', '--sysctl', '--tmpfs', '--stop-signal', '--shm-size',
])

/** 无需取值的布尔参数，直接忽略 */
const BOOL_FLAGS = new Set([
    '-d', '--detach', '-i', '--interactive', '-t', '--tty',
    '--rm', '--privileged', '--init', '--read-only', '-P', '--publish-all',
])

/** 简写参数中可携带附加值（如 -p8080:80）的首字母 */
const SHORT_ATTACHED = {p: '-p', v: '-v', e: '-e', h: '-h', u: '-u', w: '-w', m: '-m', l: '-l'}

function isNumeric(s) {
    return s !== '' && !isNaN(Number(s))
}

/** 分词：处理行尾续行、单/双引号与转义 */
function tokenize(input) {
    const s = String(input).replace(/\\\r?\n/g, ' ')
    const tokens = []
    let cur = ''
    let quote = null
    let has = false
    for (let i = 0; i < s.length; i++) {
        const ch = s[i]
        if (quote) {
            if (ch === '\\' && quote === '"' && i + 1 < s.length) {
                cur += s[++i]
                continue
            }
            if (ch === quote) {
                quote = null
                continue
            }
            cur += ch
        } else {
            if (ch === '"' || ch === "'") {
                quote = ch
                has = true
                continue
            }
            if (ch === '\\' && i + 1 < s.length) {
                cur += s[++i]
                has = true
                continue
            }
            if (/\s/.test(ch)) {
                if (has) {
                    tokens.push(cur)
                    cur = ''
                    has = false
                }
                continue
            }
            cur += ch
            has = true
        }
    }
    if (has) tokens.push(cur)
    return tokens
}

function splitImage(ref) {
    let repo = ref
    const at = repo.indexOf('@')
    if (at >= 0) repo = repo.slice(0, at)
    const lastColon = repo.lastIndexOf(':')
    const lastSlash = repo.lastIndexOf('/')
    if (lastColon > lastSlash) {
        return {imageUrl: repo.slice(0, lastColon), imageTag: repo.slice(lastColon + 1) || 'latest'}
    }
    return {imageUrl: repo, imageTag: 'latest'}
}

function toInt(v) {
    const n = parseInt(v, 10)
    return isNaN(n) ? null : n
}

/**
 * 解析 docker run 命令。
 */
export function parseDockerRun(command) {
    const warnings = []
    const config = {
        networkMode: 'bridge',
        ports: [],
        binds: [],
        envs: [],
        cmd: '',
        extraHosts: '',
        deviceRequests: [],
    }
    let name = ''
    let imageUrl = ''
    let imageTag = 'latest'

    const tokens = tokenize(command)
    if (tokens.length === 0) {
        return {name, imageUrl, imageTag, config, warnings: ['命令为空']}
    }

    let i = 0
    if (tokens[0] === 'docker') {
        i++
        if (tokens[i] === 'container') i++
        if (tokens[i] === 'run') i++
        else warnings.push('未识别为 docker run 命令，按参数解析')
    }

    const extraHosts = []
    const cmdParts = []
    let imageFound = false

    const parsePort = (spec) => {
        let proto = 'TCP'
        let s = spec
        const slash = s.lastIndexOf('/')
        if (slash >= 0) {
            proto = (s.slice(slash + 1) || 'tcp').toUpperCase()
            s = s.slice(0, slash)
        }
        const parts = s.split(':')
        let publicPort = null
        let privatePort = null
        if (parts.length === 1) {
            privatePort = toInt(parts[0])
        } else if (parts.length === 2) {
            publicPort = toInt(parts[0])
            privatePort = toInt(parts[1])
        } else {
            // ip:host:container
            publicPort = toInt(parts[parts.length - 2])
            privatePort = toInt(parts[parts.length - 1])
        }
        if (privatePort == null) {
            warnings.push(`无法解析端口映射：${spec}`)
            return
        }
        config.ports.push({publicPort, privatePort, protocol: proto})
    }

    const parseVolume = (spec) => {
        const parts = spec.split(':')
        let readOnly = false
        let rest = parts
        const last = rest[rest.length - 1]
        if (rest.length > 2 && (last === 'ro' || last === 'rw')) {
            readOnly = last === 'ro'
            rest = rest.slice(0, -1)
        }
        if (rest.length === 1) {
            config.binds.push({publicVolume: '', privateVolume: rest[0], readOnly})
            return
        }
        config.binds.push({
            publicVolume: rest.slice(0, -1).join(':'),
            privateVolume: rest[rest.length - 1],
            readOnly,
        })
    }

    const parseEnv = (spec) => {
        const idx = spec.indexOf('=')
        if (idx >= 0) {
            config.envs.push({name: spec.slice(0, idx), value: spec.slice(idx + 1)})
        } else {
            config.envs.push({name: spec, value: ''})
        }
    }

    const parseGpus = (spec) => {
        let count = -1
        const s = String(spec).replace(/["']/g, '')
        if (isNumeric(s)) {
            count = toInt(s)
        } else if (s !== 'all' && s !== '') {
            const m = s.match(/device=([\d,]+)/)
            count = m ? m[1].split(',').length : -1
        }
        config.deviceRequests.push({driver: 'nvidia', count, capabilities: [['gpu']]})
    }

    while (i < tokens.length) {
        const t = tokens[i]

        if (imageFound) {
            cmdParts.push(t)
            i++
            continue
        }

        if (t === '--') {
            i++
            continue
        }

        let flag = t
        let inlineVal

        if (t.startsWith('--')) {
            const eq = t.indexOf('=')
            if (eq >= 0) {
                flag = t.slice(0, eq)
                inlineVal = t.slice(eq + 1)
            }
        } else if (t.startsWith('-') && t.length > 2) {
            const c = t[1]
            if (SHORT_ATTACHED[c]) {
                flag = SHORT_ATTACHED[c]
                inlineVal = t.slice(2)
            } else if (t.slice(1).split('').every(x => BOOL_FLAGS.has('-' + x))) {
                // 组合布尔短参数，如 -it / -dit
                i++
                continue
            }
        }

        const isFlag = flag.startsWith('-') && !isNumeric(flag)
        if (isFlag) {
            const needVal = VALUE_FLAGS.has(flag)
            let val = inlineVal
            if (needVal && val === undefined) {
                val = tokens[i + 1]
                i++
            }
            switch (flag) {
                case '--name':
                    name = val || ''
                    break
                case '-p':
                case '--publish':
                    parsePort(val)
                    break
                case '-v':
                case '--volume':
                    parseVolume(val)
                    break
                case '-e':
                case '--env':
                    parseEnv(val)
                    break
                case '--network':
                case '--net':
                    config.networkMode = val || 'bridge'
                    if (!['bridge', 'host', 'none'].includes(config.networkMode)) {
                        warnings.push(`自定义网络「${config.networkMode}」，端口映射可能不生效`)
                    }
                    break
                case '--add-host':
                    if (val) extraHosts.push(val)
                    break
                case '--gpus':
                    parseGpus(val)
                    break
                default:
                    if (BOOL_FLAGS.has(flag)) {
                        break
                    }
                    warnings.push(`暂不支持参数 ${flag}，已忽略`)
                    break
            }
            i++
            continue
        }

        // 第一个非参数 token 视为镜像
        const img = splitImage(t)
        imageUrl = img.imageUrl
        imageTag = img.imageTag
        imageFound = true
        i++
    }

    if (!imageUrl) {
        warnings.push('未找到镜像')
    }
    config.extraHosts = extraHosts.join(' ')
    config.cmd = cmdParts.join(' ')

    return {name, imageUrl, imageTag, config, warnings}
}

export default parseDockerRun
