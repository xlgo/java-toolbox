package com.aqishi.toolbox.feature.diagram.domain;

import java.util.List;

/** 流程连线的持久化 DTO；routingType 兼容 manhattan、straight、bezier。 */
public final class EdgeDto {
    public String id;
    public String label;
    public String sourceId;
    public String targetId;
    public int sourcePort;
    public int targetPort;
    public Double sourceRelX;
    public Double sourceRelY;
    public Double targetRelX;
    public Double targetRelY;
    public int lineColor;
    public boolean isDashed;
    public String routingType;
    public double labelPosition;
    public List<PointDto> waypoints;
}
