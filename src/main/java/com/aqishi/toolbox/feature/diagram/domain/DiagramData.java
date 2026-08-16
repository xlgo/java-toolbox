package com.aqishi.toolbox.feature.diagram.domain;

import java.util.List;

/** 流程图文件的顶层序列化模型。字段名保持旧 JSON 格式不变。 */
public final class DiagramData {
    public List<NodeDto> nodes;
    public List<EdgeDto> edges;
}
