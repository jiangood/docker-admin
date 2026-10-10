import {Alert, AutoComplete, Button, Col, Divider, Form, Input, Modal, Row, Typography} from 'antd';
import React from 'react';
import ContainerStatus from "../../components/ContainerStatus";
import FieldImageUrl from "../../components/FieldImageUrl";
import ContainerConfigForm from "./ContainerConfigForm";
import {parseDockerRun} from "./dockerRunParser";
import {
    PermActions,
    FieldOrgTreeSelect,
    FieldRemoteSelect,
    HttpClient,
    Page,
    PageUtils,
    ProTable
} from "@jiangood/open-admin";
import LinkButton from "../../components/LinkButton";


/**
 * 由镜像地址推导应用名：取地址最后一段（镜像名），去掉 tag / digest。
 * 应用名首字符必须是字母（后端 @ValidateStartWithLetter），因此丢掉开头
 * 的非字母字符；整段不含字母时返回空串（不自动填，交给用户输入）。
 */
function appNameFromImage(imageUrl) {
    if (!imageUrl) return ''
    const last = String(imageUrl).trim().split('@')[0].split('/').pop() || ''
    const cleaned = last.split(':')[0].replace(/[^a-zA-Z0-9_.-]/g, '')
    const idx = cleaned.search(/[a-zA-Z]/)
    if (idx < 0) return ''
    return idx === 0 ? cleaned : cleaned.slice(idx)
}


export default class extends React.Component {


    columns = [
        {
            title: '应用名称',
            dataIndex: 'name',
            sorter: true,
            render: (name, row) => {
                return <LinkButton
                    onClick={() => PageUtils.open('/app/view?id=' + row.id, '应用-' + name)}>{name}</LinkButton>
            }
        },
        {
            title: '镜像仓库',
            dataIndex: 'imageUrl',
        },
        {
            title: '版本',
            dataIndex: 'imageTag',
        },
        {
            title: '运行主机',
            dataIndex: ['host', 'name'],
            sorter: true,
        },
        {
            title: '状态',
            dataIndex: 'containerStatus',
            hideInForm: true,
            render: (_, row) => {
                return <ContainerStatus appId={row.id}></ContainerStatus>
            }
        },
        {
            title: '标签',
            dataIndex: 'tag',
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
                    <Button size='small' perm='app:save' onClick={() => this.handleEdit(record)}>修改</Button>
                </PermActions>
            ),
        },


    ];
    state = {
        deployVisible: false,
        editVisible: false,
        editValues: {},
        tagOptions: [],

        // 新增弹窗内联动容器配置组件所需的镜像信息
        imageUrl: '',
        imageTag: '',

        // docker run 命令解析弹窗
        runVisible: false,
        runCommand: '',
        runWarnings: [],
    }



    reload = () => {
        this.tableRef.current.reload()
    }

    /**
     * 新增应用：保存后立即部署。
     */
    handleSave = value => {
        HttpClient.post('admin/app/saveAndDeploy', value).then(() => {
            this.reload()
            this.setState({deployVisible: false})
        })
    }

    formRef = React.createRef()
    editFormRef = React.createRef()
    tableRef = React.createRef()
    tagTimer = null

    handleAdd = () => {
        this.setState({
            deployVisible: true, tagOptions: [],
            imageUrl: '', imageTag: '',
            runVisible: false, runCommand: '', runWarnings: [],
        })
    }

    handleEdit = record => {
        this.setState({editVisible: true, editValues: record})
        this.loadTags(record.imageUrl)
    }

    handleEditFinish = values => {
        HttpClient.post('admin/app/updateBaseInfo', values).then(() => {
            this.setState({editVisible: false})
            this.reload()
        })
    }

    /**
     * 加载某个镜像已有的版本号。未登记的镜像地址返回空，因此自定义地址不会出现下拉选项。
     */
    loadTags = imageUrl => {
        if (!imageUrl) {
            this.setState({tagOptions: []})
            return
        }
        HttpClient.get('admin/image-repo/tags', {imageUrl}).then(rs => {
            this.setState({tagOptions: rs.data || []})
        }).catch(() => {
            this.setState({tagOptions: []})
        })
    }

    /**
     * 镜像地址变化时防抖联动版本下拉，避免输入过程中频繁请求。
     */
    onImageChange = imageUrl => {
        clearTimeout(this.tagTimer)
        this.tagTimer = setTimeout(() => this.loadTags(imageUrl), 300)
    }

    /**
     * 从镜像仓库选中镜像后：应用名称为空时，用镜像名自动填充。
     */
    handleImageSelected = imageUrl => {
        if (this.formRef.current.getFieldValue('name')) {
            return
        }
        const derived = appNameFromImage(imageUrl)
        if (derived) {
            this.formRef.current.setFieldsValue({name: derived})
        }
    }

    handleImageValuesChange = changedValues => {
        if ('imageUrl' in changedValues) {
            this.onImageChange(changedValues.imageUrl)
            this.setState({imageUrl: changedValues.imageUrl})
        }
        if ('imageTag' in changedValues) {
            this.setState({imageTag: changedValues.imageTag})
        }
    }

    /**
     * 解析 docker run 命令，回填镜像与容器配置。
     */
    parseRun = () => {
        const result = parseDockerRun(this.state.runCommand)
        if (!result.imageUrl) {
            this.setState({runWarnings: result.warnings && result.warnings.length ? result.warnings : ['未找到镜像']})
            return
        }
        const values = {
            imageUrl: result.imageUrl,
            imageTag: result.imageTag,
            config: result.config,
        }
        if (result.name) {
            values.name = result.name
        }
        this.formRef.current.setFieldsValue(values)
        this.loadTags(result.imageUrl)
        this.setState({
            imageUrl: result.imageUrl,
            imageTag: result.imageTag,
            runWarnings: result.warnings || [],
            runVisible: false,
        })
    }


    render() {
        return (
            <Page padding>
                <ProTable
                    actionRef={this.tableRef}
                    toolBarRender={() => [
                        <PermActions key="add">
                            <Button type="primary" perm='app:deploy' onClick={this.handleAdd}>
                                新增应用
                            </Button>
                        </PermActions>
                    ]}
                    searchFormRender={() => (
                        <>
                            <Form.Item label='组织机构' name='orgId'>
                                <FieldOrgTreeSelect placeholder='全部组织机构'/>
                            </Form.Item>
                            <Form.Item label='运行主机' name='hostId'>
                                <FieldRemoteSelect url="admin/host/options" placeholder='全部主机'/>
                            </Form.Item>
                        </>
                    )}
                    request={(params) => HttpClient.get('admin/app/list', params)}
                    columns={this.columns}

                />
                <Modal title='新增应用' open={this.state.deployVisible} destroyOnHidden={true}
                       onOk={() => this.formRef.current.submit()}
                       onCancel={() => this.setState({deployVisible: false})}
                       width={960}
                >
                    <Form
                        layout='horizontal'
                        labelCol={{flex: '100px'}}
                        ref={this.formRef}
                        onValuesChange={this.handleImageValuesChange}
                        onFinish={this.handleSave}
                    >
                        <Form.Item label=' '>
                            <Button onClick={() => this.setState({runVisible: true, runCommand: '', runWarnings: []})}>
                                docker run
                            </Button>
                            <Typography.Text type='secondary' style={{marginLeft: 12}}>
                                粘贴 docker run 命令，自动解析镜像与容器配置
                            </Typography.Text>
                        </Form.Item>

                        <Row gutter={16}>
                            <Col span={16}>
                                <Form.Item name='imageUrl' label='镜像' required rules={[{required: true}]}
                                           tooltip='可直接输入任意镜像地址，如 ghcr.io/jiangood/http-tunnel；也可点击右侧按钮从镜像仓库选择'>
                                    <FieldImageUrl onSelectImage={this.handleImageSelected}/>
                                </Form.Item>
                            </Col>

                            <Col span={8}>
                                <Form.Item name='imageTag' label='版本' required rules={[{required: true}]}
                                           tooltip='已登记的镜像会列出其版本，也可直接输入版本号'>
                                    <AutoComplete options={this.state.tagOptions}
                                                  placeholder='选择或输入版本，如 latest'/>
                                </Form.Item>
                            </Col>

                            <Col span={12}>
                                <Form.Item name='name' label='应用名称' required rules={[{required: true}]}>
                                    <Input/>
                                </Form.Item>
                            </Col>

                            <Col span={12}>
                                <Form.Item name={['host', 'id']} label='部署主机' required rules={[{required: true}]}>
                                    <FieldRemoteSelect showSearch url="admin/host/options"/>
                                </Form.Item>
                            </Col>

                            <Col span={12}>
                                <Form.Item label='所属组织' name={['sysOrg', 'id']}>
                                    <FieldOrgTreeSelect/>
                                </Form.Item>
                            </Col>

                            <Col span={12}>
                                <Form.Item name='remark' label='备注'>
                                    <Input/>
                                </Form.Item>
                            </Col>
                        </Row>

                        <Divider titlePlacement='left' plain>容器配置</Divider>

                        <ContainerConfigForm namePrefix={['config']} twoColumn
                                             imageUrl={this.state.imageUrl} imageTag={this.state.imageTag}/>

                    </Form>
                </Modal>

                <Modal title='docker run' open={this.state.runVisible} destroyOnHidden
                       okText='解析' onOk={this.parseRun}
                       onCancel={() => this.setState({runVisible: false})}
                       width={720}>
                    <Input.TextArea rows={6} value={this.state.runCommand}
                                    onChange={e => this.setState({runCommand: e.target.value})}
                                    placeholder='docker run -d --name my-nginx -p 8080:80 -v /data:/data -e TZ=Asia/Shanghai nginx:latest'/>
                    {this.state.runWarnings.length > 0 && (
                        <Alert className='mt-4' type='warning' showIcon
                               title={this.state.runWarnings.join('；')}/>
                    )}
                </Modal>

                <Modal title='应用基本信息'
                       open={this.state.editVisible}
                       onOk={() => this.editFormRef.current.submit()}
                       onCancel={() => this.setState({editVisible: false})}
                       destroyOnHidden
                       width={600}
                >
                    <Form ref={this.editFormRef} labelCol={{flex: '100px'}}
                          initialValues={this.state.editValues}
                          onValuesChange={this.handleImageValuesChange}
                          onFinish={this.handleEditFinish}>
                        <Form.Item name='id' noStyle></Form.Item>

                        <Form.Item name='imageUrl' label='镜像' required rules={[{required: true}]}
                                   tooltip='可直接输入任意镜像地址，如 ghcr.io/jiangood/http-tunnel；也可点击右侧按钮从镜像仓库选择'>
                            <FieldImageUrl/>
                        </Form.Item>

                        <Form.Item name='imageTag' label='版本' required rules={[{required: true}]}
                                   tooltip='已登记的镜像会列出其版本，也可直接输入版本号'>
                            <AutoComplete options={this.state.tagOptions}
                                           placeholder='选择或输入版本，如 latest'/>
                        </Form.Item>

                        <Form.Item label='所属组织' name={['sysOrg', 'id']}>
                            <FieldOrgTreeSelect/>
                        </Form.Item>

                        <Form.Item name='remark' label='备注'>
                            <Input/>
                        </Form.Item>
                    </Form>
                </Modal>

            </Page>

        )
    }

}
