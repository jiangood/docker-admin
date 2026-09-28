import {Input, InputNumber, Select} from "antd";
import React from "react";
import {DeleteOutlined, ExclamationCircleOutlined, PlusCircleFilled} from '@ant-design/icons';

export default class extends React.Component {

  state = {
    dataSource: []
  }

  constructor(props) {
    super(props);
    this.state.dataSource = props.value || [];
  }

  add = () => {
    let {dataSource} = this.state;
    // 深拷贝默认行，避免多行共享同一引用
    const defaultRow = this.props.defaultRow ? JSON.parse(JSON.stringify(this.props.defaultRow)) : {};
    dataSource.push(defaultRow)
    this.setState({dataSource})
    this.props.onChange(dataSource)
  }
  remove = (i) => {
    let {dataSource} = this.state;

    dataSource.splice(i, 1)

    this.setState({dataSource})
    this.props.onChange(dataSource)
  }

  edit = (key, value, i) => {
    let {dataSource} = this.state;
    dataSource[i][key] = value;
    this.setState({dataSource})
    this.props.onChange(dataSource)
  }

  renderCell = (c, p, i) => {
    if (c.readonly) {
      const v = p[c.dataIndex];
      return v === null || v === undefined || v === '' ? '-' : v;
    }
    if (c.dataType === 'Input') {
      return <Input value={p[c.dataIndex]} onChange={e => this.edit(c.dataIndex, e.target.value, i)}/>
    }
    if (c.dataType === 'InputNumber') {
      return <InputNumber value={p[c.dataIndex]} onChange={v => this.edit(c.dataIndex, v, i)}/>
    }
    if (c.dataType === 'Select') {
      const options = c.options
          ? c.options.map(o => <Select.Option key={o.value} value={o.value}>{o.label}</Select.Option>)
          : Object.keys(c.valueEnum || {}).map(k => <Select.Option key={k} value={k}>{c.valueEnum[k]}</Select.Option>);
      const raw = p[c.dataIndex];
      const value = c.format ? c.format(raw) : raw;
      return <Select value={value}
                     mode={c.mode}
                     onChange={v => this.edit(c.dataIndex, c.parse ? c.parse(v) : v, i)}
                     style={{minWidth: 100}}>
        {options}
      </Select>
    }
    return null;
  }

  render() {
    const {columns, extra} = this.props;
    const {dataSource} = this.state
    const canAdd = this.props.canAdd !== false;
    const canRemove = this.props.canRemove !== false;
    const colSpan = columns.length + (canRemove ? 1 : 0);
    return <div>
      <table>
        <thead>
        <tr>
          {columns.map(c => <th key={c.dataIndex}>{c.title}</th>)}
          {canRemove && <th></th>}
        </tr>
        </thead>
        <tbody>
        {dataSource.length === 0 && <tr>
          <td height={50} colSpan={colSpan}>
            <ExclamationCircleOutlined/> {extra || '暂无数据'}
          </td>
        </tr>}


        {dataSource.map((p, i) => <tr key={i}>
          {columns.map(c => <td key={c.dataIndex} align='center'>
            {this.renderCell(c, p, i)}
          </td>)}
          {canRemove && <td>
            <DeleteOutlined onClick={() => this.remove(i)}></DeleteOutlined>
          </td>}
        </tr>)}


        </tbody>
      </table>

      {canAdd && <div style={{marginTop: 16, marginBottom: 16}}>
        <PlusCircleFilled style={{color: '#1890ff'}}/><a onClick={this.add}>添加</a>
      </div>}
    </div>

  }
}
