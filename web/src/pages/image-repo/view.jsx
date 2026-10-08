import {Card, Descriptions, Spin, Table, Tabs, Typography} from 'antd'
import React from 'react'
import {HttpClient, PageUtils} from "@jiangood/open-admin"
import {sourceTag} from "./index"


export default class extends React.Component {

    state = {
        repo: null,
        tags: [],
        apps: [],
    }

    componentDidMount() {
        this.id = PageUtils.currentParams().id
        this.loadRepo()
    }

    loadRepo = () => {
        HttpClient.get('admin/image-repo/get', {id: this.id}).then(rs => {
            const repo = rs.data
            this.setState({repo})
            if (repo) {
                HttpClient.get('admin/image-repo/tagsDetail', {id: this.id}).then(r => {
                    this.setState({tags: r.data || []})
                })
                HttpClient.get('admin/image-repo/apps', {imageUrl: repo.imageUrl}).then(r => {
                    this.setState({apps: r.data || []})
                })
            }
        })
    }

    render() {
        const {repo, tags, apps} = this.state
        if (!repo) {
            return <Spin/>
        }

        return <>
            <Card className='mb-2'>
                <Descriptions title={repo.imageUrl}>
                    <Descriptions.Item label='仓库名'>{repo.name}</Descriptions.Item>
                    <Descriptions.Item label='来源'>{sourceTag(repo.source)}</Descriptions.Item>
                    <Descriptions.Item label='组织机构'>{repo.sysOrg?.name}</Descriptions.Item>
                    <Descriptions.Item label='备注'>{repo.remark}</Descriptions.Item>
                </Descriptions>
            </Card>

            <Card className='mb-2'>
                <Tabs items={[
                    {
                        key: 'tags',
                        label: `镜像标签 (${tags.length})`,
                        children: <Table
                            size='small'
                            rowKey='id'
                            pagination={false}
                            dataSource={tags}
                            columns={[
                                {title: 'tag', dataIndex: 'tag'},
                                {
                                    title: '完整地址', dataIndex: 'fullUrl',
                                    render: (v) => <Typography.Text copyable code>{v}</Typography.Text>
                                },
                                {title: '来源', dataIndex: 'source', render: (s) => sourceTag(s)},
                                {
                                    title: '声明端口', dataIndex: 'exposedPorts',
                                    render: (list) => (list && list.length) ? list.join(', ') : '-'
                                },
                                {
                                    title: '声明卷', dataIndex: 'volumes',
                                    render: (list) => (list && list.length) ? list.join(', ') : '-'
                                },
                            ]}
                        />
                    },
                    {
                        key: 'apps',
                        label: `关联应用 (${apps.length})`,
                        children: <Table
                            size='small'
                            rowKey='id'
                            pagination={false}
                            dataSource={apps}
                            columns={[
                                {title: '应用', dataIndex: 'name'},
                                {title: '主机', dataIndex: ['host', 'name']},
                                {title: '版本', dataIndex: 'imageTag'},
                            ]}
                        />
                    },
                ]}/>
            </Card>
        </>
    }
}
