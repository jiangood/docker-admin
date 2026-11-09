/**
 * 应用定时更新的间隔选项与展示文案。
 * 与后端 App.DEFAULT_SCHEDULED_UPDATE_INTERVAL_MINUTES 保持一致。
 */
export const DEFAULT_UPDATE_INTERVAL = 1440

export const UPDATE_INTERVAL_OPTIONS = [
    {label: '10 分钟', value: 10},
    {label: '30 分钟', value: 30},
    {label: '1 小时', value: 60},
    {label: '6 小时', value: 360},
    {label: '12 小时', value: 720},
    {label: '每天', value: 1440},
]

/**
 * 把分钟数格式化成页面上的中文文案。
 * 非预设值按整小时 / 整天换算，避免展示成「1441 分钟」这种难读的数字。
 */
export function updateIntervalLabel(minutes) {
    const value = Number(minutes) || DEFAULT_UPDATE_INTERVAL
    const preset = UPDATE_INTERVAL_OPTIONS.find(option => option.value === value)
    if (preset) {
        return preset.label
    }
    if (value % 1440 === 0) {
        return `${value / 1440} 天`
    }
    if (value % 60 === 0) {
        return `${value / 60} 小时`
    }
    return `${value} 分钟`
}
