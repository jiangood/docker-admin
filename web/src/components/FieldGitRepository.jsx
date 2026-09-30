import {Button, Input, Space} from 'antd'
import React from 'react'

import CodeSourceProjectPicker from './CodeSourceProjectPicker'

/**
 * Git 仓库地址：既可直接输入，也可从「设置-代码源」的仓库列表中选择。
 * <p>
 * 可直接放在 Form.Item 下使用，接收框架注入的 value / onChange。
 */
export default class extends React.Component {

    state = {
        pickerOpen: false,
    }

    handleInputChange = e => {
        this.props.onChange?.(e.target.value)
    }

    handleSelect = url => {
        this.setState({pickerOpen: false})
        this.props.onChange?.(url)
    }

    render() {
        const {value, disabled, placeholder = 'https://github.com/user/repo.git'} = this.props

        return <>
            <Space.Compact style={{width: '100%'}}>
                <Input value={value}
                       disabled={disabled}
                       placeholder={placeholder}
                       onChange={this.handleInputChange}/>
                <Button disabled={disabled} onClick={() => this.setState({pickerOpen: true})}>从代码源选择</Button>
            </Space.Compact>

            <CodeSourceProjectPicker open={this.state.pickerOpen}
                                     onCancel={() => this.setState({pickerOpen: false})}
                                     onSelect={this.handleSelect}/>
        </>
    }
}
