import {Button, Input, InputNumber, Select, Tooltip} from "antd";
import React from "react";
import {DeleteOutlined, ExclamationCircleOutlined, PlusOutlined} from '@ant-design/icons';
import {getToken} from "@jiangood/open-admin";

/**
 * 可编辑表格（表单内联编辑）：端口 / 卷 / 环境变量等键值行。
 *
 * 原生 table 按 antd Table 的观感自绘：表头底色、分隔线、内边距与主题 token 对齐，
 * 使它在表单里与其它页面组件保持一致，而不是一张无边框的裸表。
 */
export default class extends React.Component {

  state = {
    dataSource: []
  }

  constructor(props) {
    super(props);
    this.state.dataSource = props.value || [];
  }

  /**
   * 外部整体替换 value（如 docker run 解析回填）时同步内部数据。
   * 内部编辑是原地修改并回传同一个数组引用，因此这里不会误触发重置。
   */
  componentDidUpdate(prevProps) {
    if (prevProps.value !== this.props.value) {
      this.setState({dataSource: this.props.value || []})
    }
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

    const token = getToken()
    const thStyle = {
      padding: 'var(--space-2) var(--space-3)',
      textAlign: 'left',
      fontWeight: 500,
      color: token.colorTextHeading,
      background: token.colorFillAlter,
      borderBottom: `1px solid ${token.colorBorderSecondary}`,
    }
    const tdStyle = {
      padding: 'var(--space-1) var(--space-3)',
      borderBottom: `1px solid ${token.colorBorderSecondary}`,
    }
    const actionTdStyle = {...tdStyle, width: 48, textAlign: 'center'}

    return <div>
      <table style={{width: '100%', borderCollapse: 'collapse'}}>
        <thead>
        <tr>
          {columns.map(c => <th key={c.dataIndex} style={thStyle}>{c.title}</th>)}
          {canRemove && <th style={{...thStyle, width: 48}}></th>}
        </tr>
        </thead>
        <tbody>
        {dataSource.length === 0 && <tr>
          <td colSpan={colSpan}
              style={{...tdStyle, padding: 'var(--space-5) var(--space-3)', textAlign: 'center', color: token.colorTextSecondary}}>
            <ExclamationCircleOutlined/> {extra || '暂无数据'}
          </td>
        </tr>}


        {dataSource.map((p, i) => <tr key={i}>
          {columns.map(c => <td key={c.dataIndex} style={tdStyle}>
            {this.renderCell(c, p, i)}
          </td>)}
          {canRemove && <td style={actionTdStyle}>
            <Tooltip title='删除'>
              <Button type='text' size='small' danger aria-label='删除'
                      icon={<DeleteOutlined/>} onClick={() => this.remove(i)}/>
            </Tooltip>
          </td>}
        </tr>)}


        </tbody>
      </table>

      {canAdd && <Button type='link' icon={<PlusOutlined/>} className='mt-4 mb-2' style={{padding: 0}}
                         onClick={this.add}>添加</Button>}
    </div>

  }
}
