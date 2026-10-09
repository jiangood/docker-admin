import React from "react";
import {Card} from "antd";
import {Page} from "@jiangood/open-admin";

export default class extends React.Component {

  render() {
    return <Page padding>
      <Card className='page-card'>
        自定义关于
      </Card>
    </Page>
  }
}
