import {ContainerOutlined} from '@ant-design/icons'
import {Button, Input, Space, Tooltip} from 'antd'
import React from 'react'

import ImagePicker from './ImagePicker'

/**
 * 镜像地址：既可直接输入任意镜像地址，也可从「镜像」中选择。
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

    handleSelect = imageUrl => {
        this.setState({pickerOpen: false})
        this.props.onChange?.(imageUrl)
        // 供调用方在「从镜像选择」时做联动（如自动填应用名），区别于手工输入
        this.props.onSelectImage?.(imageUrl)
    }

    render() {
        const {value, disabled, placeholder = '选择或输入镜像地址，如 ghcr.io/jiangood/http-tunnel'} = this.props

        return <>
            <Space.Compact style={{width: '100%'}}>
                <Input value={value}
                       disabled={disabled}
                       placeholder={placeholder}
                       onChange={this.handleInputChange}/>
                <Tooltip title='从镜像选择'>
                    <Button disabled={disabled}
                            icon={<ContainerOutlined/>}
                            onClick={() => this.setState({pickerOpen: true})}/>
                </Tooltip>
            </Space.Compact>

            <ImagePicker open={this.state.pickerOpen}
                         onCancel={() => this.setState({pickerOpen: false})}
                         onSelect={this.handleSelect}/>
        </>
    }
}
