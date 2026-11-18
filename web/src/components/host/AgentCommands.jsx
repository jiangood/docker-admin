import React from 'react'
import {Button, Dropdown, Empty, Form, Input, List, message, Modal, Popconfirm, Tooltip, Typography} from 'antd'
import {CodeOutlined, PlusOutlined, SettingOutlined} from '@ant-design/icons'

/** 下拉菜单里单条命令标签的最长展示长度，超出截断（完整命令看悬浮提示）。 */
const MENU_LABEL_MAX = 40

/** 常用命令条数上限，与后端 HostAgentConfigService 保持一致。 */
const MAX_COMMANDS = 10

/**
 * Agent 终端页「常用命令」下拉：按钮直接弹出菜单，点击某条命令经 {@code onInput}
 * 把命令敲进终端——不自动回车，由用户补参数 / 人工回车后才执行。
 * <p>
 * 命令按主机各自维护（存在 Agent 配置的 commands 字段里，非全局配置，不单独建表），
 * 菜单底部「管理命令」打开弹窗做新增 / 编辑 / 删除，整体保存交给 {@code onChange}
 * （后端整体覆盖 commands 字段并返回清洗结果），列表数据始终以父组件传入的
 * {@code commands} 为准。最多 {@value MAX_COMMANDS} 条。
 */
export default class extends React.Component {

    state = {
        manageOpen: false,
        modalOpen: false,
        /** 正在编辑的命令下标，-1 表示新增。 */
        editingIndex: -1,
        formValues: {},
        saving: false,
    }

    formRef = React.createRef()

    /** 下拉菜单项：每条命令一个菜单项，底部固定「管理命令」。 */
    menuItems = () => {
        const list = this.props.commands || []
        const items = list.map((item, index) => ({
            key: String(index),
            label: <span>{this.menuLabel(item)}</span>,
        }))
        if (items.length === 0) {
            items.push({key: 'empty', disabled: true, label: '还没有常用命令'})
        }
        items.push({type: 'divider'})
        items.push({key: 'manage', icon: <SettingOutlined/>, label: '管理命令…'})
        return items
    }

    menuLabel = item => {
        const text = (item && (item.name || item.command)) || ''
        return text.length > MENU_LABEL_MAX ? text.slice(0, MENU_LABEL_MAX) + '…' : text
    }

    onMenuClick = ({key}) => {
        if (key === 'manage') {
            this.setState({manageOpen: true})
            return
        }
        const item = (this.props.commands || [])[Number(key)]
        if (item && item.command && this.props.onInput) {
            this.props.onInput(item.command)
        }
    }

    showAdd = () => {
        if ((this.props.commands || []).length >= MAX_COMMANDS) {
            message.warning(`常用命令最多 ${MAX_COMMANDS} 条`)
            return
        }
        this.setState({modalOpen: true, editingIndex: -1, formValues: {}})
    }

    showEdit = index => {
        const item = (this.props.commands || [])[index] || {}
        this.setState({
            modalOpen: true,
            editingIndex: index,
            formValues: {name: item.name, command: item.command},
        })
    }

    closeForm = () => this.setState({modalOpen: false})

    /** 表单提交：整体保存，成功后关闭弹窗（管理弹窗保持打开）；失败保持输入不变，方便改完重试。 */
    onFinish = values => {
        if (this.state.saving) {
            return
        }
        const command = (values.command || '').trim()
        if (/\r|\n/.test(command)) {
            message.warning('命令请写在同一行，不要包含换行')
            return
        }
        const next = [...(this.props.commands || [])]
        const item = {name: (values.name || '').trim(), command}
        if (this.state.editingIndex >= 0) {
            next[this.state.editingIndex] = item
        } else {
            next.push(item)
        }
        this.setState({saving: true})
        Promise.resolve(this.props.onChange(next))
            .then(() => {
                this.setState({saving: false, modalOpen: false})
                message.success('已保存')
            })
            .catch(() => this.setState({saving: false}))
    }

    remove = index => {
        const next = (this.props.commands || []).filter((_, i) => i !== index)
        Promise.resolve(this.props.onChange(next))
            .then(() => message.success('已保存'))
            .catch(() => {
            })
    }

    renderManageItem = (item, index) => <List.Item
        actions={[
            <Typography.Link key='edit' onClick={() => this.showEdit(index)}>编辑</Typography.Link>,
            <Popconfirm key='del' title='删除这条常用命令？' onConfirm={() => this.remove(index)}>
                <Typography.Link>删除</Typography.Link>
            </Popconfirm>,
        ]}>
        <List.Item.Meta
            title={item.name ? <Typography.Text>{item.name}</Typography.Text> : null}
            description={
                <Typography.Text code style={{wordBreak: 'break-all'}}>
                    {item.command}
                </Typography.Text>
            }/>
    </List.Item>

    render() {
        const {commands} = this.props
        const {manageOpen, modalOpen, editingIndex, formValues, saving} = this.state
        const list = commands || []
        const full = list.length >= MAX_COMMANDS

        return <>
            <Dropdown menu={{
                items: this.menuItems(),
                onClick: this.onMenuClick,
                style: {maxHeight: '60vh', overflowY: 'auto'},
            }} trigger={['click']} placement='bottomRight'>
                <Button type='text' icon={<CodeOutlined/>}>常用命令</Button>
            </Dropdown>

            <Modal title='管理常用命令'
                   width={560}
                   open={manageOpen}
                   footer={null}
                   onCancel={() => this.setState({manageOpen: false})}
                   destroyOnHidden>
                <Typography.Text type='secondary' style={{display: 'block', marginBottom: 8}}>
                    点击下拉菜单里的命令会自动敲进终端（不回车），补好参数后人工执行；命令按主机分别保存。
                </Typography.Text>
                <div style={{marginBottom: 8, display: 'flex', justifyContent: 'flex-end'}}>
                    <Tooltip title={full ? `最多 ${MAX_COMMANDS} 条` : undefined}>
                        <Button size='small' type='primary' icon={<PlusOutlined/>}
                                disabled={full} onClick={this.showAdd}>新增</Button>
                    </Tooltip>
                </div>
                <List dataSource={list} renderItem={this.renderManageItem}
                      locale={{emptyText: <Empty description='还没有常用命令'/>}}/>
            </Modal>

            <Modal title={editingIndex >= 0 ? '编辑命令' : '新增命令'}
                   width={520}
                   open={modalOpen}
                   confirmLoading={saving}
                   onOk={() => this.formRef.current && this.formRef.current.submit()}
                   onCancel={this.closeForm}
                   destroyOnHidden>
                <Form ref={this.formRef} initialValues={formValues} layout='vertical' onFinish={this.onFinish}>
                    <Form.Item label='名称' name='name'
                               extra='可选，便于在菜单里识别；留空时菜单直接显示命令'>
                        <Input maxLength={50} placeholder='如 清理悬空镜像'/>
                    </Form.Item>
                    <Form.Item label='命令' name='command'
                               rules={[{required: true, message: '请输入命令'}]}
                               extra='点击菜单里的命令时原样敲进终端，不会自动回车'>
                        <Input.TextArea rows={2} autoSize maxLength={500}
                                        placeholder='docker system prune -f'/>
                    </Form.Item>
                </Form>
            </Modal>
        </>
    }
}
