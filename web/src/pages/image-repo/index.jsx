import {Button, Form, Input, Popconfirm, Tag} from 'antd'
import {CloudSyncOutlined} from '@ant-design/icons'
import React from 'react'

import {
    HttpClient,
    Page,
    PageUtils,
    PermActions,
    ProTable
} from "@jiangood/open-admin"

import LinkButton from '../../components/LinkButton'
import RemoteSyncModal from '../../components/RemoteSyncModal'


const SOURCE_LABELS = {
    BUILD: {text: '构建', color: 'green'},
    SYNC: {text: '同步', color: 'blue'},
    IMPORT: {text: '导入', color: 'orange'},
    REMOTE: {text: '远程', color: 'purple'},
}

export function sourceTag(source) {
    const item = SOURCE_LABELS[source] || {text: source || '-', color: 'default'}
    return <Tag color={item.color}>{item.text}</Tag>
}

export default class extends React.Component {

    tableRef = React.createRef()
    remoteModalRef = React.createRef()

    columns = [
        {
            title: '镜像名',
            dataIndex: 'name',
        },
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
            title: '来源',
            dataIndex: 'source',
            render: (source) => sourceTag(source),
        },
        {
            title: '最新版本',
            dataIndex: 'latestTag',
            render: (tag) => tag || '-',
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
                toolBarRightRender={() => (
                    <PermActions>
                        <Button perm='image-repo:sync' icon={<CloudSyncOutlined/>}
                                onClick={() => this.remoteModalRef.current.show()}>
                            补录镜像仓库
                        </Button>
                    </PermActions>
                )}
                searchFormRender={() => (
                    <Form.Item label='镜像名' name='name'>
                        <Input placeholder='按镜像名查询' allowClear/>
                    </Form.Item>
                )}
                request={(params) => HttpClient.get('admin/image-repo/page', params)}
                columns={this.columns}
            />
            <RemoteSyncModal ref={this.remoteModalRef}
                             onSuccess={() => this.tableRef.current.reload()}/>
        </Page>
    }
}
