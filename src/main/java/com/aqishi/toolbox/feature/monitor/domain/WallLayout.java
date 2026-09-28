package com.aqishi.toolbox.feature.monitor.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 画面墙的几何：{@code rows × cols} 的底层网格，加上按阅读顺序（先行后列）排好的格子。
 * 第 i 个格子显示第 i 个通道。格子互不重叠，但不必铺满网格。
 */
public record WallLayout(int rows, int cols, List<Cell> cells) {

    /** 预设布局的画面数，与下拉框顺序一致。 */
    public static final int[] PRESET_SCREENS = {1, 4, 5, 9, 16, 25};
    public static final int MAX_DIMENSION = 10;

    /** 一个格子：左上角位置与跨越的行列数。 */
    public record Cell(int row, int col, int rowSpan, int colSpan) {
        public Cell {
            if (row < 0 || col < 0 || rowSpan < 1 || colSpan < 1) {
                throw new IllegalArgumentException("cell " + row + ":" + col + ":" + rowSpan + ":" + colSpan);
            }
        }

        boolean covers(int r, int c) {
            return r >= row && r < row + rowSpan && c >= col && c < col + colSpan;
        }
    }

    static final Comparator<Cell> READING_ORDER =
            Comparator.comparingInt(Cell::row).thenComparingInt(Cell::col);

    public WallLayout {
        if (rows < 1 || cols < 1 || rows > MAX_DIMENSION || cols > MAX_DIMENSION) {
            throw new IllegalArgumentException("grid " + rows + "x" + cols);
        }
        if (cells == null || cells.isEmpty()) throw new IllegalArgumentException("cells");
        List<Cell> sorted = new ArrayList<>(cells);
        sorted.sort(READING_ORDER);
        boolean[][] used = new boolean[rows][cols];
        for (Cell cell : sorted) {
            if (cell.row() + cell.rowSpan() > rows || cell.col() + cell.colSpan() > cols) {
                throw new IllegalArgumentException("cell outside grid");
            }
            for (int r = cell.row(); r < cell.row() + cell.rowSpan(); r++) {
                for (int c = cell.col(); c < cell.col() + cell.colSpan(); c++) {
                    if (used[r][c]) throw new IllegalArgumentException("overlapping cells");
                    used[r][c] = true;
                }
            }
        }
        cells = List.copyOf(sorted);
    }

    public static WallLayout uniform(int rows, int cols) {
        List<Cell> cells = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                cells.add(new Cell(r, c, 1, 1));
            }
        }
        return new WallLayout(rows, cols, cells);
    }

    /** 第 {@code index} 个预设；5 画面是左侧 4×2 大格加右侧 4 个小格。 */
    public static WallLayout preset(int index) {
        int screens = PRESET_SCREENS[Math.max(0, Math.min(index, PRESET_SCREENS.length - 1))];
        if (screens == 5) {
            return new WallLayout(4, 3, List.of(new Cell(0, 0, 4, 2),
                    new Cell(0, 2, 1, 1), new Cell(1, 2, 1, 1), new Cell(2, 2, 1, 1), new Cell(3, 2, 1, 1)));
        }
        int side = (int) Math.round(Math.sqrt(screens));
        return uniform(side, side);
    }

    public int size() {
        return cells.size();
    }

    /** 覆盖网格坐标 (r, c) 的格子序号，没有则 -1。 */
    public int cellAt(int r, int c) {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).covers(r, c)) return i;
        }
        return -1;
    }

    /** 与旧版命名布局兼容的编码：{@code rows:cols,r:c:rs:cs,...}。 */
    public String encode() {
        StringBuilder sb = new StringBuilder().append(rows).append(':').append(cols);
        for (Cell cell : cells) {
            sb.append(',').append(cell.row()).append(':').append(cell.col())
                    .append(':').append(cell.rowSpan()).append(':').append(cell.colSpan());
        }
        return sb.toString();
    }

    /** 解析 {@link #encode()} 的结果；格式损坏或几何非法时返回 {@code null}。 */
    public static WallLayout decode(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String[] parts = text.trim().split(",");
            String[] dims = parts[0].split(":");
            List<Cell> cells = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String[] seg = parts[i].split(":");
                cells.add(new Cell(Integer.parseInt(seg[0].trim()), Integer.parseInt(seg[1].trim()),
                        Integer.parseInt(seg[2].trim()), Integer.parseInt(seg[3].trim())));
            }
            return new WallLayout(Integer.parseInt(dims[0].trim()), Integer.parseInt(dims[1].trim()), cells);
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
