import {Button, Drawer, Form, Input, Space, Table, Tag} from 'antd'
import React from 'react'
import {HttpClient, Page, ProTable} from "@jiangood/open-admin"

/**
 * 镜像视图：基于镜像版本表，可查看每个镜像的版本与关联应用。
 */
export default class extends React.Component {

    state = {
        detailVisible: false,
        current: {},
        tags: [],
        apps: []
    }

    tableRef = React.createRef()

    columns = [
        {
            title: '镜像',
            dataIndex: 'imageUrl',
            render: (value, row) => <a onClick={() => this.openDetail(row)}>{value}</a>
        },
        {
            title: '最新版本',
            dataIndex: 'latestTag',
            width: 120,
            render: (value) => value ? <Tag color='blue'>{value}</Tag> : '-'
        },
        {
            title: '版本数',
            dataIndex: 'tagCount',
            width: 90
        },
        {
            title: '关联应用',
            dataIndex: 'appCount',
            width: 100,
            render: (value, row) => <a onClick={() => this.openDetail(row)}>{value}</a>
        },
        {
            title: '最近构建',
            dataIndex: 'lastBuildTime',
            width: 180
        },
        {
            title: '操作',
            valueType: 'option',
            render: (_, row) => <Button size='small' onClick={() => this.openDetail(row)}>查看</Button>
        }
    ]

    openDetail = row => {
        this.setState({detailVisible: true, current: row, tags: [], apps: []})
        HttpClient.get('admin/image/tags', {imageUrl: row.imageUrl})
            .then(rs => this.setState({tags: rs.data || []}))
        HttpClient.get('admin/image/apps', {imageUrl: row.imageUrl})
            .then(rs => this.setState({apps: rs.data || []}))
    }

    render() {
        const {detailVisible, current, tags, apps} = this.state
        return <Page padding>
            <ProTable
                rowKey='imageUrl'
                actionRef={this.tableRef}
                request={(params) => HttpClient.get('admin/image/page', params)}
                columns={this.columns}
                searchFormRender={() => (
                    <Form.Item name='searchText' label='镜像'>
                        <Input allowClear placeholder='搜索镜像地址'/>
                    </Form.Item>
                )}
            />

            <Drawer title={current.imageUrl} width={620}
                    open={detailVisible}
                    onClose={() => this.setState({detailVisible: false})}>
                <h4>版本</h4>
                <Space wrap>
                    {tags.length === 0 ? <span>-</span> : tags.map(t => <Tag key={t}>{t}</Tag>)}
                </Space>

                <h4 style={{marginTop: 16}}>关联应用</h4>
                <Table
                    size='small'
                    rowKey='id'
                    pagination={false}
                    dataSource={apps}
                    columns={[
                        {title: '应用', dataIndex: 'name'},
                        {title: '中文名称', dataIndex: 'cnName'},
                        {title: '主机', dataIndex: ['host', 'name']},
                        {title: '版本', dataIndex: 'imageTag'}
                    ]}
                />
            </Drawer>
        </Page>
    }
}
