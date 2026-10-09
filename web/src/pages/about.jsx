import React from "react";
import {Card, Typography} from "antd";
import {Page} from "@jiangood/open-admin";

export default class extends React.Component {

  render() {
    return <Page padding>
      <Card className='page-card'>
        <Typography.Title level={4} style={{margin: 0}}>自定义关于</Typography.Title>
      </Card>
    </Page>
  }
}
