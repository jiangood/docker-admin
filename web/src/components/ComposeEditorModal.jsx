import {Alert, Button, Descriptions, Modal, Select, Space, Spin, Typography} from 'antd'
import React from 'react'
import {HttpClient} from '@jiangood/open-admin'
import CodeMirrorEditor from './CodeMirrorEditor'

/**
 * compose 文件编辑弹窗。
 * <p>
 * 保存走后端：先做 mtime 冲突检测，再用 {@code docker compose config -q} 校验，
 * 不通过不会落盘；通过后旧内容会备份成 {@code xxx.bak.时间戳}。
 */
export default class ComposeEditorModal extends React.Component {

    state = {
        open: false,
        project: null,
        files: [],
        file: null,
        content: '',
        mtime: 0,
        loading: false,
        saving: false,
        error: null,
        dirty: false,
        savedTip: null,
    }

    show = project => {
        this.setState({
            open: true,
            project,
            files: project.files || [],
            file: null,
            content: '',
            mtime: 0,
            error: null,
            dirty: false,
            savedTip: null,
        })
        this.loadFiles(project)
    }

    close = () => {
        this.setState({open: false, project: null, files: [], file: null, content: ''})
    }

    loadFiles = project => {
        this.setState({loading: true, error: null})
        HttpClient.get('admin/host/compose/files', {id: this.props.hostId, dir: project.dir}, {toastError: false})
            .then(rs => {
                const files = rs.data || []
                this.setState({files})
                const target = files.find(f => f.name === project.composeFile) || files[0]
                if (target) {
                    this.loadFile(target)
                } else {
                    this.setState({loading: false, error: '目录下没有可编辑的 compose 文件'})
                }
            })
            .catch(e => this.setState({loading: false, error: e.message || '读取文件列表失败'}))
    }

    loadFile = file => {
        this.setState({loading: true, error: null, file})
        HttpClient.get('admin/host/compose/file', {id: this.props.hostId, path: file.path}, {toastError: false})
            .then(rs => this.setState({
                content: rs.data?.content || '',
                mtime: rs.data?.mtime || 0,
                loading: false,
                dirty: false,
            }))
            .catch(e => this.setState({loading: false, error: e.message || '读取文件失败'}))
    }

    selectFile = file => {
        if (!file || (this.state.file && file.path === this.state.file.path)) {
            return
        }
        if (this.state.dirty) {
            Modal.confirm({
                title: '当前文件还没保存',
                content: '切换文件会丢失未保存的修改，确定继续？',
                okText: '丢弃并切换',
                cancelText: '留在这里',
                onOk: () => this.setState({savedTip: null}, () => this.loadFile(file)),
            })
            return
        }
        this.setState({savedTip: null}, () => this.loadFile(file))
    }

    save = () => {
        const {file, content, mtime, saving} = this.state
        if (!file || saving) {
            return
        }
        this.setState({saving: true, error: null})
        HttpClient.post('admin/host/compose/save', {
            hostId: this.props.hostId,
            path: file.path,
            content,
            mtime,
        }, null, {toastError: false}).then(rs => {
            this.setState({saving: false, dirty: false, savedTip: rs.message || '已保存'})
            // 重新读取，拿到新的 mtime，避免连续保存被当成冲突
            this.loadFile(file)
            this.props.onSaved?.()
        }).catch(e => this.setState({saving: false, error: e.message || '保存失败'}))
    }

    render() {
        const {open, project, files, file, content, loading, saving, error, dirty, savedTip} = this.state

        const footer = [
            <Button key='close' onClick={this.close}>关闭</Button>,
            <Button key='save' type='primary' loading={saving}
                    disabled={loading || !file || !dirty}
                    onClick={this.save}>
                保存{dirty ? '（有未保存修改）' : ''}
            </Button>,
        ]

        return <Modal open={open}
                      width={1000}
                      title={project ? `编辑 compose - ${project.name || project.dir}` : '编辑 compose'}
                      onCancel={this.close}
                      destroyOnHidden
                      footer={footer}>
            <Space direction='vertical' size='middle' style={{width: '100%'}}>
                <Space wrap>
                    <Select style={{width: 360}}
                            value={file ? file.path : undefined}
                            placeholder='选择文件'
                            options={files.map(f => ({value: f.path, label: f.name}))}
                            onChange={path => this.selectFile(files.find(f => f.path === path))}/>
                    <Typography.Text type='secondary' style={{fontSize: 12, wordBreak: 'break-all'}}>
                        {file ? file.path : ''}
                    </Typography.Text>
                </Space>

                {project && !file && <Descriptions size='small' column={1}
                                                   items={[{key: 'dir', label: '项目目录', children: project.dir}]}/>}

                {error && <Alert type='error' showIcon message='操作失败'
                                 description={<pre style={{whiteSpace: 'pre-wrap', margin: 0, maxHeight: 160, overflow: 'auto'}}>{error}</pre>}/>}
                {savedTip && <Alert type='success' showIcon message={savedTip}/>}

                {loading
                    ? <div style={{padding: 40, textAlign: 'center'}}><Spin/></div>
                    : file
                        ? <CodeMirrorEditor key={file.path}
                                            value={content}
                                            mode='yaml'
                                            height={480}
                                            onChange={v => this.setState({content: v, dirty: true})}/>
                        : null}

                <Typography.Text type='secondary' style={{fontSize: 12}}>
                    保存前会用 docker compose config 校验，不通过不会写入；保存成功后原文件备份为同名 .bak.时间戳。
                </Typography.Text>
            </Space>
        </Modal>
    }
}
