import {Button, Form, Input, Modal} from 'antd'
import React from 'react'

import {HttpClient, ProTable} from '@jiangood/open-admin'

/**
 * 从已登记的镜像中列出镜像，选中后通过 onSelect 回填镜像地址。
 */
export default class ImagePicker extends React.Component {

    tableRef = React.createRef()

    componentDidUpdate(prevProps) {
        // 每次打开时刷新镜像列表
        if (this.props.open && !prevProps.open) {
            this.reload()
        }
    }

    reload = () => {
        if (this.tableRef.current) {
            this.tableRef.current.reload()
        }
    }

    columns = [
        {
            title: '镜像名',
            dataIndex: 'name',
        },
        {
            title: '镜像地址',
            dataIndex: 'imageUrl',
        },
        {
            title: '操作',
            dataIndex: 'option',
            valueType: 'option',
            width: 80,
            render: (_, record) => (
                <Button size='small' type='primary' onClick={() => this.props.onSelect(record.imageUrl)}>选择</Button>
            ),
        },
    ]

    render() {
        const {open, onCancel} = this.props

        return <Modal title='选择镜像'
                      open={open}
                      onCancel={onCancel}
                      footer={null}
                      width={800}
                      destroyOnHidden>
            <ProTable
                actionRef={this.tableRef}
                request={params => HttpClient.get('admin/image/page', params)}
                columns={this.columns}
                searchFormRender={() => (
                    <Form.Item label='关键字' name='searchText'>
                        <Input placeholder='镜像地址或名称'/>
                    </Form.Item>
                )}
            />
        </Modal>
    }
}
