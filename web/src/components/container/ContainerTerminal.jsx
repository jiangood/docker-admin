import React from "react";
import {Terminal} from "@xterm/xterm";
import {FitAddon} from "@xterm/addon-fit";
import Zmodem from "zmodem.js/src/zmodem_browser";
import "@xterm/xterm/css/xterm.css";

/** 自动重连退避：0.5s → 1s → 2s → 4s → 8s → 10s 封顶。 */
const RETRY_DELAYS = [500, 1000, 2000, 4000, 8000, 10000]
/** 失败这么多次后停下来，交给页面右上角的「重新连接」。 */
const MAX_RETRY = 10
/** 服务端明确表示「不要重连」的关闭码（会话被其他窗口接管）。 */
const NO_RETRY_CODES = [4001]
/** 心跳间隔：喂给 nginx / 隧道，避免长时间无输出被当空闲连接掐掉。 */
const HEARTBEAT_MILLIS = 25000

/**
 * xterm 终端封装：对接容器 exec / 主机 Agent CLI WebSocket。
 * 普通输入使用 JSON + Base64；ZMODEM 数据使用原生 WebSocket 二进制帧。
 * <p>
 * 主机 Agent 终端传 {@code autoReconnect}：断线后保留终端内容并自动重连，
 * 服务端会回放断开期间的输出（见 HostCliSessionRegistry）。
 */
export default class extends React.Component {

    containerRef = React.createRef()
    fileInputRef = React.createRef()
    term = null
    fitAddon = null
    ws = null
    resizeObserver = null
    zmodemSentry = null
    zmodemSession = null
    pendingUploadSession = null
    /** 当前连接代号：重连后旧 socket 的迟到回调直接忽略。 */
    socketGen = 0
    reconnectTimer = null
    heartbeatTimer = null
    retryCount = 0
    disposed = false

    componentDidMount() {
        this.init()
    }

    componentWillUnmount() {
        this.dispose()
    }

    toBase64(text) {
        const bytes = new TextEncoder().encode(text)
        let binary = ''
        bytes.forEach(b => {
            binary += String.fromCharCode(b)
        })
        return btoa(binary)
    }

    handleFilesSelected = async event => {
        // 注意：event.target.files 是「活」的 FileList，清空 input.value 后长度会变 0，
        // 所以必须先快照成数组，再清空输入框，否则上传会被误判为「没有选文件」而直接 abort。
        const files = Array.from(event.target.files || [])
        const session = this.pendingUploadSession
        this.pendingUploadSession = null
        event.target.value = ''

        if (!session) {
            return
        }
        if (files.length === 0) {
            session.abort()
            this.zmodemSession = null
            return
        }

        try {
            await Zmodem.Browser.send_files(session, files)
            await session.close()
        } catch (e) {
            console.error('ZMODEM 上传失败', e)
            try {
                session.abort()
            } catch (ignored) {
                // ignore
            }
            this.term && this.term.write('\r\n\x1b[31m[ZMODEM 上传失败]\x1b[0m\r\n')
            this.zmodemSession = null
        }
    }

    handleFilePickerCancel = () => {
        const session = this.pendingUploadSession
        this.pendingUploadSession = null
        if (session) {
            try {
                session.abort()
            } catch (e) {
                // ignore
            }
            this.zmodemSession = null
        }
    }

    onZmodemDetected = detection => {
        const role = detection.get_session_role()
        const action = role === 'send' ? '上传文件到远程主机' : '从远程主机下载文件'
        if (!window.confirm(`检测到 ZMODEM 会话，是否确认${action}？\n如果不是你主动发起的文件传输，请选择取消。`)) {
            detection.deny()
            return
        }

        const session = detection.confirm()
        this.zmodemSession = session
        session.on('session_end', () => {
            if (this.zmodemSession === session) {
                this.zmodemSession = null
            }
            this.pendingUploadSession = null
            this.term && this.term.write('\r\n\x1b[32m[ZMODEM 传输结束]\x1b[0m\r\n')
        })

        if (session.type === 'send') {
            // 远端执行 rz：浏览器选择文件并发送给远端。
            this.pendingUploadSession = session
            if (this.fileInputRef.current) {
                this.fileInputRef.current.value = ''
                this.fileInputRef.current.click()
            } else {
                session.abort()
                this.zmodemSession = null
            }
            return
        }

        // 远端执行 sz：接收远端发来的文件并保存到浏览器下载目录。
        session.on('offer', offer => {
            // zmodem.js 0.1.10 的 Offer 没有 get_payloads()，
            // accept() 完成时直接把收到的数据块（Uint8Array 数组）作为结果返回。
            const details = offer.get_details()
            offer.accept().then(payloads => {
                Zmodem.Browser.save_to_disk(payloads, details.name)
            }).catch(error => {
                console.error('ZMODEM 下载失败', error)
                try {
                    offer.skip()
                } catch (ignored) {
                    // ignore
                }
            })
        })
        session.start()
    }

    init() {
        const term = new Terminal({
            convertEol: false,
            cursorBlink: true,
            fontSize: 13,
            fontFamily: 'Consolas, Menlo, monospace',
            scrollback: 5000,
            theme: {
                background: '#1e1e1e',
                foreground: '#d4d4d4',
            },
        })
        const fitAddon = new FitAddon()
        term.loadAddon(fitAddon)
        term.open(this.containerRef.current)
        this.term = term
        this.fitAddon = fitAddon

        // 输入与尺寸监听只注册一次：重连只换 WebSocket，不再叠加监听器。
        term.onData(data => {
            // ZMODEM 传输期间不把终端按键混入二进制协议流。
            if (this.zmodemSession) {
                return
            }
            const ws = this.ws
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({type: 'input', data: this.toBase64(data)}))
            }
        })
        term.onResize(({cols, rows}) => {
            const ws = this.ws
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({type: 'resize', cols, rows}))
            }
        })

        this.resizeObserver = new ResizeObserver(() => {
            try {
                fitAddon.fit()
            } catch (e) {
                // ignore
            }
        })
        this.resizeObserver.observe(this.containerRef.current)

        this.openSocket()
    }

    openSocket() {
        const {term} = this
        if (this.disposed || !term) {
            return
        }
        this.clearReconnectTimer()

        const gen = ++this.socketGen
        let ws
        try {
            ws = new WebSocket(this.props.url)
        } catch (e) {
            // URL 非法（例如主机被删除、地址拼错）时不要抛到渲染栈，按断线处理
            this.scheduleReconnect()
            return
        }
        ws.binaryType = 'arraybuffer'
        this.ws = ws

        this.zmodemSentry = new Zmodem.Sentry({
            to_terminal: octets => term.write(new Uint8Array(octets)),
            sender: octets => {
                const socket = this.ws
                if (socket && socket.readyState === WebSocket.OPEN) {
                    socket.send(new Uint8Array(octets))
                }
            },
            on_detect: detection => this.onZmodemDetected(detection),
            on_retract: () => {
                term.write('\r\n\x1b[33m[ZMODEM 握手已取消]\x1b[0m\r\n')
            },
        })

        ws.onopen = () => {
            if (gen !== this.socketGen) {
                return
            }
            this.retryCount = 0
            this.startHeartbeat()
            try {
                this.fitAddon.fit()
            } catch (e) {
                // 容器尺寸尚未就绪时忽略
            }
            this.sendResize()
            term.focus()
            this.props.onStatus && this.props.onStatus('open')
            this.props.onOpen && this.props.onOpen()
        }

        ws.onmessage = ev => {
            if (gen !== this.socketGen) {
                return
            }
            if (typeof ev.data === 'string') {
                term.write(ev.data)
            } else {
                try {
                    this.zmodemSentry.consume(new Uint8Array(ev.data))
                } catch (e) {
                    // 会话已经结束时，多出来的字节通常是 shell 提示符之类的普通输出
                    //（例如 sz 发完 ZFIN 后命令提示符紧跟着到达），直接回显到终端，
                    // 不要再报协议错误，否则每条命令的提示符都会被吞掉。
                    if (!this.zmodemSentry.get_confirmed_session()) {
                        const rest = new Uint8Array(ev.data)
                        const printable = Array.prototype.every.call(rest,
                            b => b >= 32 || b === 9 || b === 10 || b === 13 || b === 27)
                        if (printable) {
                            term.write(rest)
                        }
                        return
                    }
                    console.error('ZMODEM 数据处理失败', e)
                    // 协议异常时结束会话，避免 zmodemSession 一直非空把终端输入屏蔽掉。
                    this.abortZmodem()
                    term.write('\r\n\x1b[31m[ZMODEM 协议错误]\x1b[0m\r\n')
                }
            }
        }

        ws.onclose = ev => {
            if (gen !== this.socketGen) {
                return
            }
            this.stopHeartbeat()
            this.abortZmodem()
            if (this.disposed) {
                return
            }

            if (ev && NO_RETRY_CODES.includes(ev.code)) {
                term.write('\r\n\x1b[33m[会话已被其他窗口接管，本窗口不再自动重连]\x1b[0m\r\n')
                this.props.onStatus && this.props.onStatus('closed')
                this.props.onClose && this.props.onClose()
                return
            }

            if (!this.props.autoReconnect) {
                term.write('\r\n\x1b[33m[连接已关闭]\x1b[0m\r\n')
                this.props.onStatus && this.props.onStatus('closed')
                this.props.onClose && this.props.onClose()
                return
            }

            this.scheduleReconnect()
        }

        ws.onerror = () => {
            // 错误细节由 onclose 统一提示
        }
    }

    scheduleReconnect() {
        const {term} = this
        if (!term) {
            return
        }
        if (this.retryCount >= MAX_RETRY) {
            term.write('\r\n\x1b[31m[重连失败，请点击右上角「重新连接」]\x1b[0m\r\n')
            this.props.onStatus && this.props.onStatus('closed')
            this.props.onClose && this.props.onClose()
            return
        }
        const delay = RETRY_DELAYS[Math.min(this.retryCount, RETRY_DELAYS.length - 1)]
        if (this.retryCount === 0) {
            term.write('\r\n\x1b[33m[连接已断开，正在重连…]\x1b[0m\r\n')
        }
        this.retryCount += 1
        this.props.onStatus && this.props.onStatus('reconnecting')
        this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null
            this.openSocket()
        }, delay)
    }

    /** 通知服务端结束当前 shell（切到「新建会话」时用），随后由父组件重挂终端。 */
    closeSession() {
        const ws = this.ws
        if (ws && ws.readyState === WebSocket.OPEN) {
            try {
                ws.send(JSON.stringify({type: 'close'}))
            } catch (e) {
                // ignore
            }
        }
    }

    /** 供父组件调用：立刻重建连接（保留终端内容），用于「重新连接」按钮。 */
    reconnect() {
        if (this.disposed) {
            return
        }
        this.retryCount = 0
        this.clearReconnectTimer()
        this.stopHeartbeat()
        this.abortZmodem()
        // 让旧 socket 的迟到回调全部失效，再主动关掉它
        this.socketGen += 1
        const old = this.ws
        this.ws = null
        if (old) {
            try {
                old.close()
            } catch (e) {
                // ignore
            }
        }
        this.openSocket()
    }

    startHeartbeat() {
        this.stopHeartbeat()
        this.heartbeatTimer = setInterval(() => {
            const ws = this.ws
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({type: 'ping'}))
            }
        }, HEARTBEAT_MILLIS)
    }

    stopHeartbeat() {
        if (this.heartbeatTimer) {
            clearInterval(this.heartbeatTimer)
            this.heartbeatTimer = null
        }
    }

    clearReconnectTimer() {
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer)
            this.reconnectTimer = null
        }
    }

    abortZmodem() {
        if (this.zmodemSession) {
            try {
                this.zmodemSession.abort()
            } catch (e) {
                // ignore
            }
            this.zmodemSession = null
        }
        this.pendingUploadSession = null
    }

    sendResize() {
        const {term, ws} = this
        if (term && ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({type: 'resize', cols: term.cols, rows: term.rows}))
        }
    }

    dispose() {
        this.disposed = true
        this.socketGen += 1
        this.clearReconnectTimer()
        this.stopHeartbeat()
        if (this.resizeObserver) {
            this.resizeObserver.disconnect()
            this.resizeObserver = null
        }
        this.abortZmodem()
        this.zmodemSentry = null
        try {
            if (this.ws) {
                this.ws.onclose = null
                this.ws.close()
            }
        } catch (e) {
            // ignore
        }
        this.ws = null
        if (this.term) {
            try {
                this.term.dispose()
            } catch (e) {
                // ignore
            }
        }
        this.term = null
    }

    render() {
        const height = this.props.height || 420
        return <>
            <div ref={this.containerRef}
                 style={{height, width: '100%', background: '#1e1e1e', padding: 4, boxSizing: 'border-box'}}/>
            <input
                ref={this.fileInputRef}
                type="file"
                multiple
                style={{display: 'none'}}
                onChange={this.handleFilesSelected}
                onCancel={this.handleFilePickerCancel}
            />
        </>
    }
}
