package com.aqishi.toolbox.feature.diagram.domain;

/**
 * 旧版流程图 DTO 文件的兼容占位类型。
 *
 * <p>实际序列化模型已拆分为 {@link DiagramData}、{@link NodeDto}、
 * {@link EdgeDto} 和 {@link PointDto}；保留本类型仅用于旧包路径和源码引用的
 * 迁移窗口，不应再作为新的序列化入口。</p>
 */
@Deprecated
public final class FlowchartDataDto {
    private FlowchartDataDto() {
    }
}
