package com.aqishi.toolbox.feature.monitor.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 画面墙的布局与通道分配，不依赖 Swing，也不依赖具体播放器。
 *
 * <p>通道表独立于布局：第 i 个格子显示第 i 个通道。切换布局只换几何、不动通道表——
 * 缩小时多出来的通道成为"隐藏通道"，仍然保存在配置里，再次放大时回到原来的格子。
 * 选择保留而不是确认后丢弃：切布局是高频的临时动作，弹窗打断且容易误点"确定"；
 * 保留则完全可逆，真要删掉用"清空"即可（清空会连隐藏通道一起清掉）。</p>
 *
 * <p>合并与拆分改变格子的阅读顺序，因此按格子重新映射：未受影响的格子保留自己的源，
 * 合并格取所选格中第一个有源的，其余被合并掉的源排到隐藏通道的最前面，同样不丢；
 * 拆分出来的空格子再从隐藏通道依次补位。</p>
 */
public final class VideoWallModel {

    private WallLayout layout;
    /** 下标即通道号（0 起）；可比格子数长，超出部分为隐藏通道；空位为 null。 */
    private final List<VideoSource> channels = new ArrayList<>();

    public VideoWallModel(WallLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    /** 从持久化状态恢复；{@code channels} 可含 null 空位。 */
    public static VideoWallModel restore(WallLayout layout, List<VideoSource> channels) {
        VideoWallModel model = new VideoWallModel(layout);
        if (channels != null) model.channels.addAll(channels);
        model.trim();
        return model;
    }

    public WallLayout layout() {
        return layout;
    }

    public int cellCount() {
        return layout.size();
    }

    /** 全部通道（含隐藏通道与 null 空位），只读。 */
    public List<VideoSource> channels() {
        return Collections.unmodifiableList(channels);
    }

    /** 第 {@code cell} 个格子当前显示的源；越界或空格返回 null。 */
    public VideoSource source(int cell) {
        return cell >= 0 && cell < cellCount() && cell < channels.size() ? channels.get(cell) : null;
    }

    /** 当前可见（在格子里）的源，按格子顺序。 */
    public List<VideoSource> visibleSources() {
        List<VideoSource> out = new ArrayList<>();
        for (int i = 0; i < Math.min(cellCount(), channels.size()); i++) {
            if (channels.get(i) != null) out.add(channels.get(i));
        }
        return out;
    }

    /** 因布局缩小而暂时不显示、但仍保存着的源。 */
    public List<VideoSource> hiddenSources() {
        List<VideoSource> out = new ArrayList<>();
        for (int i = cellCount(); i < channels.size(); i++) {
            if (channels.get(i) != null) out.add(channels.get(i));
        }
        return out;
    }

    public int cellOf(String sourceId) {
        for (int i = 0; i < Math.min(cellCount(), channels.size()); i++) {
            VideoSource s = channels.get(i);
            if (s != null && s.id().equals(sourceId)) return i;
        }
        return -1;
    }

    /** 第一个空格子；没有则 -1。 */
    public int firstEmptyCell() {
        for (int i = 0; i < cellCount(); i++) {
            if (source(i) == null) return i;
        }
        return -1;
    }

    /** 换布局：通道表不变，超出新格子数的通道被隐藏而不是丢弃。 */
    public void setLayout(WallLayout next) {
        this.layout = Objects.requireNonNull(next, "layout");
    }

    public void assign(int cell, VideoSource source) {
        if (cell < 0 || cell >= cellCount()) throw new IndexOutOfBoundsException("cell " + cell);
        while (channels.size() <= cell) channels.add(null);
        channels.set(cell, Objects.requireNonNull(source, "source"));
    }

    /** 清空一个格子，返回原来的源。 */
    public VideoSource clear(int cell) {
        VideoSource old = source(cell);
        if (old != null) {
            channels.set(cell, null);
            trim();
        }
        return old;
    }

    /** 清空全部通道，包括隐藏通道。 */
    public void clearAll() {
        channels.clear();
    }

    /** 按 id 替换源（播放状态、还原后的凭据）；找不到返回 false。 */
    public boolean replace(VideoSource updated) {
        for (int i = 0; i < channels.size(); i++) {
            VideoSource s = channels.get(i);
            if (s != null && s.id().equals(updated.id())) {
                channels.set(i, updated);
                return true;
            }
        }
        return false;
    }

    /**
     * 把所选格子合并成它们的包围矩形。所选格子不构成完整矩形（或少于 2 个）时不改动并返回 false。
     * {@code selection} 的顺序决定合并格保留谁的源：第一个有源的格子胜出。
     */
    public boolean merge(Collection<Integer> selection) {
        Set<Integer> picked = new LinkedHashSet<>(selection);
        if (picked.size() < 2) return false;
        int minR = Integer.MAX_VALUE, maxR = -1, minC = Integer.MAX_VALUE, maxC = -1;
        for (int index : picked) {
            if (index < 0 || index >= cellCount()) return false;
            WallLayout.Cell c = layout.cells().get(index);
            minR = Math.min(minR, c.row());
            maxR = Math.max(maxR, c.row() + c.rowSpan() - 1);
            minC = Math.min(minC, c.col());
            maxC = Math.max(maxC, c.col() + c.colSpan() - 1);
        }
        for (int r = minR; r <= maxR; r++) {
            for (int c = minC; c <= maxC; c++) {
                if (!picked.contains(layout.cellAt(r, c))) return false;
            }
        }
        WallLayout.Cell merged = new WallLayout.Cell(minR, minC, maxR - minR + 1, maxC - minC + 1);
        List<WallLayout.Cell> cells = new ArrayList<>();
        for (int i = 0; i < cellCount(); i++) {
            if (!picked.contains(i)) cells.add(layout.cells().get(i));
        }
        cells.add(merged);
        WallLayout next = new WallLayout(layout.rows(), layout.cols(), cells);
        int mergedIndex = next.cells().indexOf(merged);
        List<Integer> order = new ArrayList<>(picked);
        for (int i = 0; i < cellCount(); i++) {
            if (!picked.contains(i)) order.add(i);
        }
        int[] target = new int[cellCount()];
        for (int i = 0; i < cellCount(); i++) {
            target[i] = picked.contains(i) ? mergedIndex : next.cells().indexOf(layout.cells().get(i));
        }
        remap(next, order, target);
        return true;
    }

    /** 把合并格拆回 1×1 小格，源留在左上角那一格。已是 1×1 时返回 false。 */
    public boolean split(int cell) {
        if (cell < 0 || cell >= cellCount()) return false;
        WallLayout.Cell big = layout.cells().get(cell);
        if (big.rowSpan() == 1 && big.colSpan() == 1) return false;
        List<WallLayout.Cell> cells = new ArrayList<>();
        for (int i = 0; i < cellCount(); i++) {
            if (i != cell) cells.add(layout.cells().get(i));
        }
        for (int r = big.row(); r < big.row() + big.rowSpan(); r++) {
            for (int c = big.col(); c < big.col() + big.colSpan(); c++) {
                cells.add(new WallLayout.Cell(r, c, 1, 1));
            }
        }
        WallLayout next = new WallLayout(layout.rows(), layout.cols(), cells);
        int[] target = new int[cellCount()];
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < cellCount(); i++) {
            order.add(i);
            target[i] = i == cell ? next.cellAt(big.row(), big.col()) : next.cells().indexOf(layout.cells().get(i));
        }
        remap(next, order, target);
        return true;
    }

    /**
     * 按格子重新映射可见通道：{@code order} 中靠前的旧格子优先占据 {@code target} 指向的新格子，
     * 没占到的源排在隐藏通道最前面，原有隐藏通道顺延。
     *
     * <p>新产生且仍空着的格子（拆分出来的小格）按顺序从隐藏通道补位，
     * 与放大布局时隐藏通道回到新格子的行为一致——所以"合并再拆分"能原样还原。</p>
     */
    private void remap(WallLayout next, List<Integer> order, int[] target) {
        int oldCount = cellCount();
        VideoSource[] placed = new VideoSource[next.size()];
        List<VideoSource> overflow = new ArrayList<>();
        for (int oldIndex : order) {
            VideoSource s = source(oldIndex);
            if (s == null) continue;
            int t = target[oldIndex];
            if (t >= 0 && placed[t] == null) placed[t] = s;
            else overflow.add(s);
        }
        if (channels.size() > oldCount) overflow.addAll(channels.subList(oldCount, channels.size()));
        Set<WallLayout.Cell> before = new HashSet<>(layout.cells());
        for (int i = 0; i < placed.length; i++) {
            if (placed[i] != null || before.contains(next.cells().get(i))) continue;
            int k = 0;
            while (k < overflow.size() && overflow.get(k) == null) k++;
            if (k == overflow.size()) break;
            placed[i] = overflow.remove(k);
        }
        channels.clear();
        Collections.addAll(channels, placed);
        channels.addAll(overflow);
        layout = next;
        trim();
    }

    private void trim() {
        while (!channels.isEmpty() && channels.get(channels.size() - 1) == null) {
            channels.remove(channels.size() - 1);
        }
    }
}
