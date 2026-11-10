import {Button, Empty, Input, Modal, Space, Table, Tooltip, Typography, message} from 'antd'
import {CopyOutlined} from '@ant-design/icons'
import React from 'react'

/** 复制纯文本到剪贴板。 */
function copyText(text) {
    const value = text || ''
    if (!value) {
        return
    }
    if (navigator.clipboard) {
        navigator.clipboard.writeText(value)
            .then(() => message.success('已复制'))
            .catch(() => message.error('复制失败'))
    } else {
        message.warning('当前浏览器不支持剪贴板')
    }
}

/** 把 `KEY=VALUE` 拆成变量名与值；没有等号时整条都算变量名。 */
function splitEnv(env) {
    const text = String(env ?? '')
    const index = text.indexOf('=')
    return index < 0
        ? {name: text, value: ''}
        : {name: text.slice(0, index), value: text.slice(index + 1)}
}

/**
 * 镜像标签声明的环境变量弹窗。
 * <p>
 * 环境变量通常很长，列表里只显示数量，点击后在这里查看完整内容，变量名可逐个复制。
 */
export default class EnvVarsModal extends React.Component {

    state = {
        open: false,
        tag: '',
        envs: [],
        keyword: '',
    }

    /** 打开弹窗：envs 为 `KEY=VALUE` 字符串数组，tag 用于标题展示。 */
    show = (envs, tag) => {
        this.setState({open: true, tag: tag || '', envs: envs || [], keyword: ''})
    }

    close = () => this.setState({open: false})

    render() {
        const {open, tag, envs, keyword} = this.state
        const rows = envs
            .map((env, index) => ({...splitEnv(env), key: `${index}-${env}`}))
            .filter(row => !keyword
                || row.name.toLowerCase().includes(keyword.toLowerCase())
                || row.value.toLowerCase().includes(keyword.toLowerCase()))

        return <Modal open={open}
                      width={720}
                      title={tag ? `声明环境变量 · ${tag}` : '声明环境变量'}
                      onCancel={this.close}
                      destroyOnHidden
                      footer={<Button onClick={this.close}>关闭</Button>}>
            <Space direction='vertical' size='middle' style={{width: '100%'}}>
                <Space style={{width: '100%', justifyContent: 'space-between'}}>
                    <Typography.Text type='secondary'>
                        共 {envs.length} 个变量，点击右侧图标复制变量名
                    </Typography.Text>
                    <Button size='small' icon={<CopyOutlined/>}
                            disabled={envs.length === 0}
                            onClick={() => copyText(envs.join('\n'))}>
                        复制全部
                    </Button>
                </Space>

                <Input allowClear
                       placeholder='搜索变量名或值'
                       value={keyword}
                       onChange={e => this.setState({keyword: e.target.value})}/>

                <Table size='small'
                       rowKey='key'
                       pagination={false}
                       dataSource={rows}
                       scroll={{y: 420}}
                       locale={{emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                                                  description={envs.length ? '没有匹配的环境变量' : '未声明环境变量'}/>}}
                       columns={[
                           {
                               title: '变量名', dataIndex: 'name',
                               render: (name) => <span className='mono'
                                                       style={{wordBreak: 'break-all'}}>{name}</span>
                           },
                           {
                               title: '值', dataIndex: 'value',
                               render: (value) => value
                                   ? <span className='mono' style={{wordBreak: 'break-all'}}>{value}</span>
                                   : <Typography.Text type='secondary'>-</Typography.Text>
                           },
                           {
                               title: '', width: 56, align: 'center',
                               render: (_, row) => (
                                   <Tooltip title='复制变量名'>
                                       <Button size='small' type='text' icon={<CopyOutlined/>}
                                               onClick={() => copyText(row.name)}/>
                                   </Tooltip>
                               )
                           },
                       ]}/>
            </Space>
        </Modal>
    }

}
