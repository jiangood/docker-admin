import {Drawer, Form, Input, Tag, Tooltip} from 'antd'
import React from 'react'
import {
    CloudUploadOutlined,
    DownloadOutlined,
    SearchOutlined,
    SwapOutlined,
} from '@ant-design/icons'
import {DateUtils, HttpClient, Page, PermActions, ProTable} from '@jiangood/open-admin'
import ImageSyncModal, {MODE_HOST, MODE_REGISTRY, MODE_RELAY} from '../../components/ImageSyncModal'
import LogView from '../../components/LogView'

/**
 * 同步记录的状态：success 为空表示仍在进行中。
 */
const statusTagOf = row => {
    if (row.success === true) {
        return <Tag color='success'>成功</Tag>
    }
    if (row.success === false) {
        return <Tag color='error'>失败</Tag>
    }
    return <Tag color='processing'>同步中</Tag>
}

/**
 * 同步类型标签：注册中心 / 主机（直连）/ 主机（中转）。
 */
const typeTagOf = syncType => {
    if (syncType === MODE_HOST) {
        return <Tag color='blue'>主机</Tag>
    }
    if (syncType === MODE_RELAY) {
        return <Tag color='cyan'>主机中转</Tag>
    }
    return <Tag>注册中心</Tag>
}

/**
 * 镜像同步：右上角三个按钮对应三种同步方式，各开一个独立表单弹窗；
 * 表格是同步记录（每次同步一条），行内可回看日志。
 * <ul>
 *   <li>同步到注册中心：执行主机拉取后 push 到平台注册中心，并登记镜像；</li>
 *   <li>同步到主机：目标主机直接 pull 源镜像，不经过注册中心；</li>
 *   <li>经执行主机中转：执行主机 pull 后 save/load 到目标主机，不经过注册中心。</li>
 * </ul>
 */
export default class extends React.Component {

    tableRef = React.createRef()

    syncModalRef = React.createRef()

    state = {
        // 当前日志抽屉展示的同步记录
        logId: null,
        logVisible: false,
    }

    syncColumns = [
        {title: '开始时间', dataIndex: 'createTime', width: 170},
        {title: '类型', dataIndex: 'syncType', width: 90, render: typeTagOf},
        {title: '源镜像', dataIndex: 'sourceImage', ellipsis: true},
        {title: '目标镜像', dataIndex: 'targetImage', ellipsis: true, render: v => v || '-'},
        {title: '执行主机', dataIndex: 'hostName', width: 140, render: v => v || '-'},
        {
            title: '目标主机', dataIndex: 'targetHostName', ellipsis: true,
            render: v => v || '-',
        },
        {
            title: '状态', dataIndex: 'success', width: 100,
            render: (_, row) => row.errorMessage
                ? <Tooltip title={row.errorMessage}>{statusTagOf(row)}</Tooltip>
                : statusTagOf(row),
        },
        {
            title: '耗时', dataIndex: 'timeSpend', width: 100,
            render: v => v != null ? DateUtils.friendlyTotalTime(v) : '-',
        },
        {
            title: '操作', dataIndex: 'option', valueType: 'option', width: 100,
            render: (_, row) => row.logId
                ? <PermActions size='small' actions={[
                    {label: '查看日志', perm: 'image-sync:view', onClick: () => this.openLog(row.logId)},
                ]}/>
                : '-',
        },
    ]

    reload = () => {
        this.tableRef.current?.reload()
    }

    openLog = logId => {
        if (logId) {
            this.setState({logId, logVisible: true})
        }
    }

    /**
     * 关闭日志抽屉时刷新一次记录：同步可能刚好结束，状态与耗时需要更新。
     */
    closeLog = () => {
        this.setState({logVisible: false})
        this.reload()
    }

    render() {
        const {logId, logVisible} = this.state
        return <Page padding>
            <ProTable actionRef={this.tableRef}
                      searchInline
                      toolBarRightRender={() => (
                          <PermActions actions={[
                              {
                                  label: '同步到注册中心', perm: 'image-sync:sync', type: 'primary',
                                  icon: <CloudUploadOutlined/>,
                                  onClick: () => this.syncModalRef.current.show(MODE_REGISTRY),
                              },
                              {
                                  label: '同步到主机', perm: 'image-sync:sync', icon: <DownloadOutlined/>,
                                  onClick: () => this.syncModalRef.current.show(MODE_HOST),
                              },
                              {
                                  label: '经执行主机中转', perm: 'image-sync:sync', icon: <SwapOutlined/>,
                                  onClick: () => this.syncModalRef.current.show(MODE_RELAY),
                              },
                          ]}/>
                      )}
                      searchFormRender={() => (
                          <Form.Item name='searchText' noStyle>
                              <Input prefix={<SearchOutlined/>} placeholder='搜索镜像或主机' allowClear/>
                          </Form.Item>
                      )}
                      request={params => HttpClient.get('admin/image-sync/page', params)}
                      columns={this.syncColumns}/>

            <ImageSyncModal ref={this.syncModalRef} onSuccess={this.reload}/>

            <Drawer title='同步日志' size={860} open={logVisible}
                    mask={{closable: false}}
                    onClose={this.closeLog}>
                {logVisible && logId
                    ? <LogView key={logId} url={'/admin/ws/sync-log/' + logId} websocket={true}
                               onClose={this.reload}/>
                    : null}
            </Drawer>
        </Page>
    }
}
