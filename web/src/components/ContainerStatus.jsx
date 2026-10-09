import {Tag} from "antd";
import React from "react";
import {HttpClient} from "@jiangood/open-admin";
import {stateColor, stateLabel} from "./container/utils";


/**
 * 应用容器状态
 */
export default class extends React.Component {

  state = {
    state: null,
    status: '-'
  }

  componentDidMount() {
    const {appId} = this.props
    if (!appId) {
      return
    }
    HttpClient.get("admin/app/container", {id: appId}).then(rs => {
      const container = rs.data || {}
      this.setState({
        state: container.state,
        status: container.status || stateLabel(container.state),
      })
    }).catch(() => {
      this.setState({status: '未知'})
    })
  }

  render() {
    const {state, status} = this.state
    return <Tag color={stateColor(state)}>{status}</Tag>
  }
}
