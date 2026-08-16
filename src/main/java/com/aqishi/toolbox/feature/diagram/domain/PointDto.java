package com.aqishi.toolbox.feature.diagram.domain;

/** 流程连线折点 DTO，坐标单位为画布像素。 */
public final class PointDto {
    public int x;
    public int y;

    public PointDto() {
    }

    public PointDto(int x, int y) {
        this.x = x;
        this.y = y;
    }
}
