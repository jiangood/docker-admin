import React from "react";
import {Card, Col, message, Row, Splitter, Table, Tree} from "antd";
import {HttpUtils, Page, SysUtils} from "@jiangood/springboot-admin-starter";
import {TreeUtils} from "@jiangood/springboot-admin-starter";


export default class extends React.Component {

  state = {
    treeData: [],
    treeLoading: false,
    curNode: {}
  }


  constructor(props) {
    super(props);

    this.state.treeData = [
      {
        title: "/ (根目录)",
        key: '_',
        path: '/'
      }
    ]
  }


  onLoadData = (treeNode) => {
    const {key, path, children} = treeNode
    return new Promise((resolve, reject) => {

      let {hostId, containerId} = this.props;
      let {treeData} = this.state

      if (children) {
        resolve()
        return;
      }

      const hide = message.loading("加载文件信息中...", 0)
      this.setState({treeLoading: true})
      HttpUtils.get("admin/container/file", {hostId, containerId, path}).then(rs => {
        hide()
        const {dirs, files} = rs
        const node = TreeUtils.findByKey(key, treeData, "key");
        if (node) {
          node.children = dirs;
          node.fileList = files;
        }
        this.setState({treeData: [...treeData], treeLoading: false})
        resolve()
      }).catch(() => {
        hide()
        resolve()
      })
    })
  };

  onSelect = (keys) => {
    const key = keys[0];
    const node = TreeUtils.findByKey(key, this.state.treeData, "key")
    this.setState({curNode: node})
  }

  render() {
    return <Page>
      <Splitter>
        <Splitter.Panel defaultSize={300}>
          <Card style={{height: '80vh', overflowY: "auto"}}>
            <Tree.DirectoryTree
                loadData={this.onLoadData}
                treeData={this.state.treeData}
                onSelect={this.onSelect}/>
          </Card>
        </Splitter.Panel>
        <Splitter.Panel>
          <Card title={'文件列表：' + (this.state.curNode?.path || '') } extra={this.getDownloadUrl(this.state.curNode?.path)}>

            <Table pagination={false}
                   dataSource={this.state.curNode?.fileList}
                   columns={[
                     {title: '名称', dataIndex: 'title'},
                     {title: '大小', dataIndex: 'sizeFmt'},
                     {title: '修改时间', dataIndex: 'time'},
                     {
                       title: '-', dataIndex: 'option',
                       render: (_, row) => {
                         return  this.getDownloadUrl(row.path)
                       }
                     }
                   ]}>
            </Table>
          </Card>
        </Splitter.Panel>
      </Splitter>

    </Page>
  }

  getDownloadUrl(path){
    if(!path) {
      return
    }
    let {hostId, containerId} = this.props;
    let url =  'container/downloadFile?hostId=' + hostId + "&containerId=" + containerId + "&file=" +path
    return <a href={url} target='_blank'>下载</a>
  }
}
