package com.aqishi.toolbox.feature.diagram.domain;

/** 流程节点的持久化 DTO；坐标和尺寸单位为画布像素。 */
public final class NodeDto {
    public String id;
    public String type;
    public String name;
    public int x;
    public int y;
    public int w;
    public int h;
    public int bgColor;
    public int borderColor;
    public int textColor;
    public int fontSize;
    public boolean isBold;
    public boolean isDashedBorder;
    public float borderThickness;
}
