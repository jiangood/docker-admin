import React from "react";

/**
 * 概览分区小标题：统一字号 / 间距，右侧预留插槽（数量、复制等）。
 * 取代此前各分区散落的 fontWeight:600 + mt-4 写法。
 */
export default function SectionTitle({title, extra}) {
    return <div className='container-section-title'>
        <span className='container-section-title-text'>{title}</span>
        {extra ? <span className='container-section-title-extra'>{extra}</span> : null}
    </div>
}
