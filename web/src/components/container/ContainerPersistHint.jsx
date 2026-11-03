import React from "react";
import ContainerHint from "./ContainerHint";
import {isPersistentPath, writableMountDestinations} from "./utils";

/**
 * 持久性提示：容器内只有挂载到主机目录或数据卷的路径会在容器重建后保留，
 * 其余（镜像层里的）修改会随容器重建一起丢失。
 * <p>
 * 传入 path 时按当前浏览目录判断是否落在挂载点内，用于「文件」页签；
 * 不传 path 时给统一提示，用于「终端」页签。
 * 文案尽量保持单行，且允许关闭（按容器 + 类别记忆，切页签不再重复出现）。
 */
export default function ContainerPersistHint({mounts, path, containerKey, className}) {
    const dirs = writableMountDestinations(mounts)
    // 挂载点可能很多，提示只留一行，超出部分由悬停提示补全
    const list = dirs.join('、')
    const key = kind => (containerKey ? `persist:${containerKey}:${kind}` : undefined)

    if (path) {
        if (dirs.length === 0) {
            return <ContainerHint id={key('no-mount')} type='warning' className={className}
                                  text='容器没有可写挂载目录，这里的修改会在重建后丢失'/>
        }
        if (isPersistentPath(path, mounts)) {
            return <ContainerHint id={key('mounted')} type='success' className={className}
                                  text='当前目录已挂载，修改会保留'/>
        }
        return <ContainerHint id={key('unmounted')} type='warning' className={className}
                              text={`未挂载，修改会在重建后丢失；可持久化：${list}`}/>
    }

    if (dirs.length === 0) {
        return <ContainerHint id={key('console-no-mount')} type='warning' className={className}
                              text='容器没有可写挂载目录，终端里的修改会在重建后丢失'/>
    }
    return <ContainerHint id={key('console')} type='warning' className={className}
                          text={`只有挂载目录的修改会保留：${list}`}/>
}
