import React from "react";
import {Layouts} from "@jiangood/open-admin";
import {APP_THEME} from "../theme";

export default class extends React.Component {


  render() {
    return <Layouts {...this.props} colors={APP_THEME}></Layouts>
  }


}
