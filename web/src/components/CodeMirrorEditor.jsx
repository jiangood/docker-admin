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

// 内容为空时的占位提示
import 'codemirror/addon/display/placeholder'

// 数据校验
import 'codemirror/addon/lint/lint.css'
import 'codemirror/addon/lint/yaml-lint.js'


/**
 * CodeMirror 5 编辑器封装。
 * <p>
 * 可直接放在 Form.Item 下使用，接收框架注入的 value / onChange。
 * value 被外部修改（如表单回填、重置）时会同步到编辑器，无需靠 key 重挂载。
 *
 * @param mode        语法高亮，默认 yaml，可传 dockerfile / shell 等
 * @param height      编辑器高度（像素），默认 300
 * @param placeholder 内容为空时的占位提示
 */
class CodeMirrorEditor extends React.Component {

    editor = null;

    ref = React.createRef();

    componentDidMount() {
        this.init();
    }

    componentDidUpdate(prevProps) {
        const {value} = this.props;
        if (!this.editor || value === prevProps.value) {
            return;
        }
        const next = value ?? '';
        if (next !== this.editor.getValue()) {
            this.editor.setValue(next);
        }
    }

    init = () => {
        this.editor = CodeMirror.fromTextArea(this.ref.current, {
            mode: this.props.mode || 'yaml',
            tabSize: 2,
            theme: 'darcula',
            lineNumbers: true,
            placeholder: this.props.placeholder,
            readOnly: this.props.readOnly,
        });
        this.editor.setSize(null, this.props.height || 300);

        this.editor.on('change', (cm) => {
            this.props.onChange?.(cm.getValue());
        });
    };


    componentWillUnmount() {
        if (this.editor) {
            this.editor.toTextArea();
            this.editor = null;
        }
    }

    render() {
        return (<textarea ref={this.ref} defaultValue={this.props.value}/>);
    }
}

export default CodeMirrorEditor;
