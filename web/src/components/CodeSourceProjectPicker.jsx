import {SearchOutlined} from '@ant-design/icons'
import {Alert, Button, Form, Input, Modal, Select, Space} from 'antd'
import dayjs from 'dayjs'
import React from 'react'

import {HttpClient, ProTable} from '@jiangood/open-admin'

/**
 * 从代码源（目前支持 GitLab）列出仓库，选中后通过 onSelect 回填仓库地址。
 */
export default class CodeSourceProjectPicker extends React.Component {

    state = {
        codeSources: [],
        selectedId: null,
        selectedType: null,
    }

    tableRef = React.createRef()

    componentDidMount() {
        this.loadCodeSources()
    }

    componentDidUpdate(prevProps) {
        // 每次打开时刷新代码源列表
        if (this.props.open && !prevProps.open) {
            this.loadCodeSources()
        }
    }

    loadCodeSources = () => {
        HttpClient.get('admin/code-source/options').then(rs => {
            const list = rs.data || []
            const preferred = list.find(o => o.data?.type === 'GITLAB') || list[0]
            this.setState({
                codeSources: list,
                selectedId: preferred ? preferred.value : null,
                selectedType: preferred?.data?.type || null,
            }, () => this.reload())
        })
    }

    reload = () => {
        if (this.tableRef.current) {
            this.tableRef.current.reload()
        }
    }

    handleSourceChange = value => {
        const source = this.state.codeSources.find(o => o.value === value)
        this.setState({selectedId: value, selectedType: source?.data?.type || null}, () => this.reload())
    }

    handlePick = record => {
        this.props.onSelect(record.gitUrl)
    }

    columns = [
        {
            title: '仓库',
            dataIndex: 'path',
        },
        {
            title: '名称',
            dataIndex: 'name',
        },
        {
            title: '默认分支',
            dataIndex: 'defaultBranch',
            width: 120,
        },
        {
            title: '最近活动',
            dataIndex: 'lastActivityAt',
            width: 120,
            render: v => v ? dayjs(v).format('YYYY-MM-DD') : '',
        },
        {
            title: '操作',
            dataIndex: 'option',
            valueType: 'option',
            width: 80,
            render: (_, record) => <Button size='small' onClick={() => this.handlePick(record)}>选择</Button>,
        },
    ]

    // 保留一份最近一次请求的数据，供「选择」使用
    request = params => {
        const {selectedId, selectedType} = this.state
        if (!selectedId || selectedType !== 'GITLAB') {
            return Promise.resolve({data: {content: [], totalElements: 0, size: params.size || 20}})
        }
        return HttpClient.get('admin/code-source/projects', {...params, codeSourceId: selectedId})
    }

    render() {
        const {open, onCancel} = this.props
        const {codeSources, selectedId, selectedType} = this.state

        return <Modal title='选择代码仓库'
                      open={open}
                      onCancel={onCancel}
                      footer={null}
                      width={800}
                      destroyOnHidden>
            <Space orientation='vertical' style={{width: '100%'}}>
                {selectedType && selectedType !== 'GITLAB' &&
                    <Alert type='info' showIcon title='该类型暂不支持自动列出仓库，请关闭后手动填写仓库地址'/>}

                {/* 搜索栏内联到表格工具栏：代码源在前，关键字在后；800px 弹窗里放宽输入框宽度 */}
                <div style={{'--oa-search-inline-width': '320px', width: '100%'}}>
                    <ProTable
                        actionRef={this.tableRef}
                        request={this.request}
                        columns={this.columns}
                        searchInline
                        searchFormRender={() => (
                            <>
                                <Select style={{width: 220}}
                                        value={selectedId}
                                        onChange={this.handleSourceChange}
                                        options={codeSources.map(o => ({value: o.value, label: o.label}))}
                                        placeholder='请选择代码源'/>
                                <Form.Item name='search' noStyle>
                                    <Input prefix={<SearchOutlined/>} placeholder='仓库名关键字' allowClear/>
                                </Form.Item>
                            </>
                        )}
                    />
                </div>
            </Space>
        </Modal>
    }
}
