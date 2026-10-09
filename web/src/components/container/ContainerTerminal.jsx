import React from "react";
import {Terminal} from "@xterm/xterm";
import {FitAddon} from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";

/**
 * xterm 终端封装：对接容器 exec WebSocket。
 * 发送：{type:'input', data:<base64>} / {type:'resize', cols, rows}
 * 接收：二进制帧直接写入终端。
 */
export default class extends React.Component {

    containerRef = React.createRef()
    term = null
    fitAddon = null
    ws = null
    resizeObserver = null

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

        ws.onopen = () => {
            try {
                fitAddon.fit()
            } catch (e) {
                // 容器尺寸尚未就绪时忽略
            }
            term.onData(data => {
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
        }

        ws.onmessage = ev => {
            if (typeof ev.data === 'string') {
                term.write(ev.data)
            } else {
                term.write(new Uint8Array(ev.data))
            }
        }

        ws.onclose = () => {
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
        return <div ref={this.containerRef}
                    style={{height, width: '100%', background: '#1e1e1e', padding: 4, boxSizing: 'border-box'}}/>
    }
}
