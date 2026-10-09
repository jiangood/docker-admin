import {Button, Form, Popconfirm, Tag} from 'antd'
import React from 'react'

import {
    FieldOrgTreeSelect,
    HttpClient,
    Page,
    PageUtils,
    PermActions,
    ProTable
} from "@jiangood/open-admin"

import LinkButton from '../../components/LinkButton'


const SOURCE_LABELS = {
    BUILD: {text: '构建', color: 'green'},
    SYNC: {text: '同步', color: 'blue'},
    IMPORT: {text: '导入', color: 'orange'},
}

export function sourceTag(source) {
    const item = SOURCE_LABELS[source] || {text: source || '-', color: 'default'}
    return <Tag color={item.color}>{item.text}</Tag>
}

export default class extends React.Component {

    tableRef = React.createRef()

    columns = [
        {
            title: '镜像地址',
            dataIndex: 'imageUrl',
            render: (imageUrl, row) => (
                <LinkButton
                    onClick={() => PageUtils.open('/image-repo/view?id=' + row.id, "镜像仓库-" + imageUrl)}>
                    {imageUrl}
                </LinkButton>
            ),
        },
        {
            title: '仓库名',
            dataIndex: 'name',
        },
        {
            title: '来源',
            dataIndex: 'source',
            render: (source) => sourceTag(source),
        },
        {
            title: '组织机构',
            dataIndex: ['sysOrg', 'name'],
        },
        {
            title: '最近更新',
            dataIndex: 'updateTime',
        },
        {
            title: '备注',
            dataIndex: 'remark',
        },
        {
            title: '操作',
            dataIndex: 'option',
            valueType: 'option',
            render: (_, record) => (
                <PermActions>
                    <Popconfirm perm='image-repo:delete' title='是否确定删除该镜像仓库及其标签'
                                onConfirm={() => this.handleDelete(record)}>
                        <Button size='small'>删除</Button>
                    </Popconfirm>
                </PermActions>
            ),
        },
    ]

    handleDelete = record => {
        HttpClient.postForm('admin/image-repo/delete', {id: record.id}).then(() => {
            this.tableRef.current.reload()
        })
    }

    render() {
        return <Page padding>
            <ProTable
                actionRef={this.tableRef}
                searchFormRender={() => (
                    <Form.Item label='组织机构' name='orgId'>
                        <FieldOrgTreeSelect placeholder='全部组织机构'/>
                    </Form.Item>
                )}
                request={(params) => HttpClient.get('admin/image-repo/page', params)}
                columns={this.columns}
            />
        </Page>
    }
}
