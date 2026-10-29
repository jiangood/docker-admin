import React from "react";
import {Alert} from "antd";
import {isPersistentPath, writableMountDestinations} from "./utils";

/**
 * 持久性提示：容器内只有挂载到主机目录或数据卷的路径会在容器重建后保留，
 * 其余（镜像层里的）修改会随容器重建一起丢失。
 * <p>
 * 传入 path 时按当前浏览目录判断是否落在挂载点内，用于「文件」页签；
 * 不传 path 时给统一提示，用于「终端」页签。
 */
export default function ContainerPersistHint({mounts, path, className}) {
    const dirs = writableMountDestinations(mounts)
    const list = dirs.join('、')

    if (path) {
        if (dirs.length === 0) {
            return <Alert type='warning' showIcon className={className}
                          title='当前容器没有挂载任何可写目录'
                          description='这里的修改只存在于当前容器，容器重建后会全部丢失。需要长期保留请改为挂载主机目录或数据卷。'/>
        }
        if (isPersistentPath(path, mounts)) {
            return <Alert type='success' showIcon className={className}
                          title='当前目录已挂载，修改会被保留'
                          description={`可持久化的目录：${list}；这些目录之外的修改仍会随容器重建丢失。`}/>
        }
        return <Alert type='warning' showIcon className={className}
                      title='当前目录未挂载到主机'
                      description={`该目录下的修改会随容器重建丢失。可持久化的目录：${list}。`}/>
    }

    if (dirs.length === 0) {
        return <Alert type='warning' showIcon className={className}
                      title='容器内没有可写挂载目录'
                      description='在终端里写入的文件会随容器重建丢失。需要长期保留请改为挂载主机目录或数据卷。'/>
    }
    return <Alert type='warning' showIcon className={className}
                  title='非挂载目录的修改会在容器重建后丢失'
                  description={`只有以下目录挂载自主机或数据卷，内容会被保留：${list}；其余位置均为容器内的临时内容。`}/>
}
