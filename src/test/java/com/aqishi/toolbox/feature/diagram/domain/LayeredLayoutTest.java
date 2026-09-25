package com.aqishi.toolbox.feature.diagram.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LayeredLayoutTest {

    private static List<int[]> edges(int... pairs) {
        List<int[]> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            list.add(new int[]{pairs[i], pairs[i + 1]});
        }
        return list;
    }

    @Test
    void layersAChainLeftToRight() {
        assertArrayEquals(new int[]{0, 1, 2, 3},
                LayeredLayout.assignLevels(4, edges(0, 1, 1, 2, 2, 3), List.of(0)));
    }

    /** 最长路径分层：汇合点要排在两条分支中较长那条之后。 */
    @Test
    void usesLongestPathForMergeNodes() {
        // 0 -> 1 -> 2 -> 3, 0 -> 3
        assertArrayEquals(new int[]{0, 1, 2, 3},
                LayeredLayout.assignLevels(4, edges(0, 1, 1, 2, 2, 3, 0, 3), List.of(0)));
    }

    /** 回归：审批 → 网关 → 驳回回到审批，旧实现在这里无限循环、卡死界面。 */
    @Test
    @Timeout(2)
    void terminatesOnCyclesAndKeepsForwardOrder() {
        // 0 开始 -> 1 审批 -> 2 网关 -> 3 结束, 2 -> 1 驳回
        int[] levels = LayeredLayout.assignLevels(4, edges(0, 1, 1, 2, 2, 3, 2, 1), List.of(0));

        assertArrayEquals(new int[]{0, 1, 2, 3}, levels);
    }

    @Test
    @Timeout(2)
    void handlesGraphThatIsOneBigCycle() {
        int[] levels = LayeredLayout.assignLevels(3, edges(0, 1, 1, 2, 2, 0), List.of());

        assertArrayEquals(new int[]{0, 1, 2}, levels);
    }

    @Test
    void rootsDecideWhereTheCycleIsCut() {
        int[] levels = LayeredLayout.assignLevels(3, edges(0, 1, 1, 2, 2, 0), List.of(1));

        assertEquals(0, levels[1]);
        assertEquals(1, levels[2]);
        assertEquals(2, levels[0]);
    }

    @Test
    void isolatedNodesAndBadEdgesAreTolerated() {
        int[] levels = LayeredLayout.assignLevels(3, edges(0, 0, 0, 7, -1, 1), List.of());

        assertArrayEquals(new int[]{0, 0, 0}, levels);
    }
}
