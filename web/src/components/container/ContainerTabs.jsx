import React from 'react';
import {Alert, Card, Tabs, Typography} from "antd";
import ContainerLog from "../../components/container/ContainerLog";
import ContainerFile from "../../components/container/ContainerFile";
import {HttpUtils, Page, PageUtils} from "@jiangood/springboot-admin-starter";
import ContainerUpDownButton from "./ContainerUpDownButton";


export default class extends React.Component {

  state = {
    status: null
  }


  componentDidMount() {
    const {containerId, hostId} = this.props;
    HttpUtils.get("admin/container/status", {hostId, containerId}).then(rs => {
      this.setState({status: rs})
    }).catch(() => {
      this.setState({status: '未知'})
    })
  }

  render() {
    const {containerId, hostId} = this.props;
    if(this.state.status == null || this.state.status === '未知'){
      return <div style={{display:'flex',alignItems:'center',justifyContent:'center', height:'100%'}}>

        <div>
          <Typography.Title level={3}>获取容器状态失败</Typography.Title>
          <ul>
            <li>网络连接问题</li>
            <li>容器未创建</li>
          </ul>


        </div>
      </div>
    }

    return <Card >

      <Tabs   tabBarExtraContent={<>{containerId} &nbsp;<ContainerUpDownButton  hostId={hostId} containerId={containerId}/> </>}>
        <Tabs.TabPane tab="日志" key="container-log">
          <ContainerLog hostId={hostId} containerId={containerId}/>
        </Tabs.TabPane>

        <Tabs.TabPane tab="文件" key="container-file">
          <ContainerFile hostId={hostId} containerId={containerId}/>
        </Tabs.TabPane>


      </Tabs>
    </Card>
  }


}



