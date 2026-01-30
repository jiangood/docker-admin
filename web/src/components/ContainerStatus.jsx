import {Tag} from "antd";
import React from "react";
import {HttpUtils, StringUtils} from "@jiangood/open-admin";


/**
 * 容器状态
 */
export default class extends React.Component {

  state = {
    status: '-'
  }

  componentDidMount() {
    const {hostId, appName,containerId} = this.props
    HttpUtils.get("admin/container/status", {hostId, appName,containerId}).then(rs => {
      this.setState({status: rs})
    }).catch(()=>{
      this.setState({status:'未知'})
    })
  }

  render() {
    const s = this.state.status;
    if (s && (StringUtils.contains(s,'Up') || StringUtils.contains(s, "running"))) {
      return <Tag color={"green"}>{s} </Tag>
    }
    return <Tag color={"red"}>{s}</Tag>
  }
}
