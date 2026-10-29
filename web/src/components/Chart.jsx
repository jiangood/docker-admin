import React, {useEffect, useRef} from 'react'
import * as echarts from 'echarts/core'
import {LineChart} from 'echarts/charts'
import {GridComponent, LegendComponent, TooltipComponent} from 'echarts/components'
import {CanvasRenderer} from 'echarts/renderers'

/**
 * 极薄的 echarts 封装：不做 React wrapper，只负责「初始化 / 跟随容器尺寸 / 数据变化时重绘」。
 * <p>
 * 按需注册图表与组件（折线 + 网格 + 图例 + 提示框），比全量引入小一个数量级；
 * 与 React 19 没有版本耦合，升级 antd 或 React 不受影响。
 */
echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

export default function Chart({option, height = 220}) {
    const containerRef = useRef(null)
    const chartRef = useRef(null)

    useEffect(() => {
        const el = containerRef.current
        if (!el) {
            return
        }
        const chart = echarts.init(el)
        chartRef.current = chart
        // 侧边栏收起、窗口缩放时容器宽度会变，图表要跟着重算
        const observer = new ResizeObserver(() => chart.resize())
        observer.observe(el)
        return () => {
            observer.disconnect()
            chart.dispose()
            chartRef.current = null
        }
    }, [])

    useEffect(() => {
        if (chartRef.current && option) {
            // notMerge：切换时间范围时系列数量与数据长度都会变，合并会留下上一次的残留
            chartRef.current.setOption(option, true)
        }
    }, [option])

    return <div ref={containerRef} style={{width: '100%', height}}/>
}
