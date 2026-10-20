import {Form, Input, Tag} from 'antd'
import {ImportOutlined, PlusOutlined, SearchOutlined} from '@ant-design/icons'
import React from 'react'

import {
    HttpClient,
    Page,
    PageUtils,
    PermActions,
    ProTable
} from "@jiangood/open-admin"

import ImageImportModal from '../../components/ImageImportModal'
import LinkButton from '../../components/LinkButton'
import ImageManualModal from '../../components/ImageManualModal'


const SOURCE_LABELS = {
    BUILD: {text: '构建', color: 'green'},
    SYNC: {text: '同步', color: 'blue'},
    IMPORT: {text: '导入', color: 'orange'},
    MANUAL: {text: '手动', color: 'cyan'},
    REMOTE: {text: '远程', color: 'purple'},
}

export function sourceTag(source) {
    const item = SOURCE_LABELS[source] || {text: source || '-', color: 'default'}
    return <Tag color={item.color}>{item.text}</Tag>
}

export default class extends React.Component {

    tableRef = React.createRef()
    manualModalRef = React.createRef()
    importModalRef = React.createRef()

    columns = [
        {
            title: '镜像名',
            dataIndex: 'name',
            render: (name, row) => (
                <LinkButton
                    onClick={() => PageUtils.open('/image/view?id=' + row.id, "镜像-" + name)}>
                    {name}
                </LinkButton>
            ),
        },
        {
            title: '镜像地址',
            dataIndex: 'imageUrl',
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
                <PermActions size='small' actions={[
                    {
                        label: '删除', perm: 'image:delete',
                        confirm: '是否确定删除该镜像及其标签', onClick: () => this.handleDelete(record)
                    },
                ]}/>
            ),
        },
    ]

    handleDelete = record => {
        HttpClient.postForm('admin/image/delete', {id: record.id}).then(() => {
            this.tableRef.current.reload()
        })
    }

    render() {
        return <Page padding>
            <ProTable
                actionRef={this.tableRef}
                searchInline
                toolBarRightRender={() => (
                    <PermActions actions={[
                        {
                            label: '导入 tar', perm: 'image:import', icon: <ImportOutlined/>,
                            onClick: () => this.importModalRef.current.show()
                        },
                        {
                            label: '补录镜像', perm: 'image:sync', icon: <PlusOutlined/>,
                            onClick: () => this.manualModalRef.current.show()
                        },
                    ]}/>
                )}
                searchFormRender={() => (
                    <Form.Item name='name' noStyle>
                        <Input prefix={<SearchOutlined/>} placeholder='搜索镜像名' allowClear/>
                    </Form.Item>
                )}
                request={(params) => HttpClient.get('admin/image/page', params)}
                columns={this.columns}
            />
            <ImageManualModal ref={this.manualModalRef}
                              onSuccess={() => this.tableRef.current.reload()}/>
            <ImageImportModal ref={this.importModalRef}
                              onSuccess={() => this.tableRef.current.reload()}/>
        </Page>
    }
}
