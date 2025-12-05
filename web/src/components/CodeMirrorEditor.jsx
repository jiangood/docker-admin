import React from 'react';
import CodeMirror from 'codemirror';
import 'codemirror/lib/codemirror.css'

// 主题
import 'codemirror/theme/darcula.css'

// 支持格式
import 'codemirror/mode/css/css.js'
import 'codemirror/mode/yaml/yaml';
import 'codemirror/mode/shell/shell'
import 'codemirror/mode/dockerfile/dockerfile'

// 数据校验
import 'codemirror/addon/lint/lint.css'
import 'codemirror/addon/lint/yaml-lint.js'


class CodeMirrorEditor extends React.Component {

    editor = null;

    ref = React.createRef();

    componentDidMount() {
        this.init();
    }

    init = () => {
        const value = this.props.value;
        const dom = this.ref.current
        this.editor = CodeMirror.fromTextArea(dom, {
            value: value,
            mode:  'yaml',
            tabSize: 2,
            theme: "darcula",
        });
        this.editor.setSize(null, 300);

        this.editor.on('change', (cm) => {
            this.props.onChange(cm.getValue());
        });
    };


    componentWillUnmount() {
        if (this.editor) {
            this.editor.toTextArea();
            this.editor = null;
        }
    }

    render() {
        return (<textarea ref={this.ref} value={this.props.value}/>);
    }
}

export default CodeMirrorEditor;
