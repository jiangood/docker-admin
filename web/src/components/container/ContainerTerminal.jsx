import React from "react";
import {Terminal} from "@xterm/xterm";
import {FitAddon} from "@xterm/addon-fit";
import Zmodem from "zmodem.js/src/zmodem_browser";
import "@xterm/xterm/css/xterm.css";

/**
 * xterm 终端封装：对接容器 exec / 主机 Agent CLI WebSocket。
 * 普通输入使用 JSON + Base64；ZMODEM 数据使用原生 WebSocket 二进制帧。
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

        const ws = new WebSocket(this.props.url)
        ws.binaryType = 'arraybuffer'
        this.ws = ws

        this.zmodemSentry = new Zmodem.Sentry({
            to_terminal: octets => term.write(new Uint8Array(octets)),
            sender: octets => {
                if (ws.readyState === WebSocket.OPEN) {
                    ws.send(new Uint8Array(octets))
                }
            },
            on_detect: detection => this.onZmodemDetected(detection),
            on_retract: () => {
                term.write('\r\n\x1b[33m[ZMODEM 握手已取消]\x1b[0m\r\n')
            },
        })

        ws.onopen = () => {
            try {
                fitAddon.fit()
            } catch (e) {
                // 容器尺寸尚未就绪时忽略
            }
            term.onData(data => {
                // ZMODEM 传输期间不把终端按键混入二进制协议流。
                if (this.zmodemSession) {
                    return
                }
                if (ws.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({type: 'input', data: this.toBase64(data)}))
                }
            })
            term.onResize(({cols, rows}) => {
                if (ws.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({type: 'resize', cols, rows}))
                }
            })
            this.sendResize()
            term.focus()
            this.props.onOpen && this.props.onOpen()
        }

        ws.onmessage = ev => {
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
                    try {
                        this.zmodemSession && this.zmodemSession.abort()
                    } catch (ignored) {
                        // ignore
                    }
                    this.zmodemSession = null
                    this.pendingUploadSession = null
                    term.write('\r\n\x1b[31m[ZMODEM 协议错误]\x1b[0m\r\n')
                }
            }
        }

        ws.onclose = () => {
            if (this.zmodemSession) {
                try {
                    this.zmodemSession.abort()
                } catch (e) {
                    // ignore
                }
                this.zmodemSession = null
                this.pendingUploadSession = null
            }
            term.write('\r\n\x1b[33m[连接已关闭]\x1b[0m\r\n')
            this.props.onClose && this.props.onClose()
        }

        ws.onerror = () => {
            // 错误细节由 onclose 统一提示
        }

        this.resizeObserver = new ResizeObserver(() => {
            try {
                fitAddon.fit()
            } catch (e) {
                // ignore
            }
        })
        this.resizeObserver.observe(this.containerRef.current)
    }

    sendResize() {
        const {term, ws} = this
        if (term && ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({type: 'resize', cols: term.cols, rows: term.rows}))
        }
    }

    dispose() {
        if (this.resizeObserver) {
            this.resizeObserver.disconnect()
            this.resizeObserver = null
        }
        if (this.zmodemSession) {
            try {
                this.zmodemSession.abort()
            } catch (e) {
                // ignore
            }
            this.zmodemSession = null
        }
        this.zmodemSentry = null
        this.pendingUploadSession = null
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
