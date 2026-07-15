package com.aqishi.toolbox.misc;

import java.util.List;

class DiagramData {
    public List<NodeDto> nodes;
    public List<EdgeDto> edges;
}

class NodeDto {
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

class EdgeDto {
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

class PointDto {
    public int x;
    public int y;
    public PointDto() {}
    public PointDto(int x, int y) { this.x = x; this.y = y; }
}
