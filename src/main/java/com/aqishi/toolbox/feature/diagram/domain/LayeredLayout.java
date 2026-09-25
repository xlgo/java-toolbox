package com.aqishi.toolbox.feature.diagram.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * 有向图分层：给每个节点分配一个"列号"，使每条边尽量从左指向右。
 *
 * <p>流程图里回环很常见（"驳回 → 回到审批"）。早先的实现沿边反复抬高目标层级并重新入队，
 * 遇到环就永远停不下来，在界面线程上直接卡死。这里先用 DFS 找出回边——指向当前搜索路径上
 * 祖先节点的边——分层时忽略它们，剩下的是无环图，再按最长路径分层，必然终止。</p>
 */
public final class LayeredLayout {

    private LayeredLayout() {
    }

    /**
     * @param nodeCount 节点数，节点用 {@code 0..nodeCount-1} 表示
     * @param edges     边列表，每项 {@code {source, target}}；自环与越界项被忽略
     * @param roots     优先作为起点的节点（例如开始事件），决定环被"切开"的位置；可为空
     * @return 每个节点的层号，从 0 开始
     */
    public static int[] assignLevels(int nodeCount, List<int[]> edges, List<Integer> roots) {
        List<List<Integer>> outgoing = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            outgoing.add(new ArrayList<>());
        }
        for (int[] edge : edges) {
            if (edge[0] != edge[1] && inRange(edge[0], nodeCount) && inRange(edge[1], nodeCount)) {
                outgoing.get(edge[0]).add(edge[1]);
            }
        }

        boolean[][] backEdge = findBackEdges(nodeCount, outgoing, roots);

        int[] inDegree = new int[nodeCount];
        for (int source = 0; source < nodeCount; source++) {
            for (int target : outgoing.get(source)) {
                if (!backEdge[source][target]) {
                    inDegree[target]++;
                }
            }
        }
        int[] level = new int[nodeCount];
        Deque<Integer> ready = new ArrayDeque<>();
        for (int node = 0; node < nodeCount; node++) {
            if (inDegree[node] == 0) {
                ready.add(node);
            }
        }
        while (!ready.isEmpty()) {
            int node = ready.poll();
            for (int target : outgoing.get(node)) {
                if (backEdge[node][target]) {
                    continue;
                }
                level[target] = Math.max(level[target], level[node] + 1);
                if (--inDegree[target] == 0) {
                    ready.add(target);
                }
            }
        }
        return level;
    }

    /** 迭代式 DFS，避免深图栈溢出。先从 roots 出发，再从其余未访问节点出发。 */
    private static boolean[][] findBackEdges(int nodeCount, List<List<Integer>> outgoing, List<Integer> roots) {
        boolean[][] back = new boolean[nodeCount][nodeCount];
        int[] state = new int[nodeCount]; // 0 未访问，1 在栈上，2 已完成
        List<Integer> order = new ArrayList<>();
        if (roots != null) {
            for (Integer root : roots) {
                if (root != null && inRange(root, nodeCount)) {
                    order.add(root);
                }
            }
        }
        for (int node = 0; node < nodeCount; node++) {
            order.add(node);
        }
        int[] nextChild = new int[nodeCount];
        Arrays.fill(nextChild, 0);
        for (int start : order) {
            if (state[start] != 0) {
                continue;
            }
            Deque<Integer> stack = new ArrayDeque<>();
            stack.push(start);
            state[start] = 1;
            while (!stack.isEmpty()) {
                int node = stack.peek();
                List<Integer> children = outgoing.get(node);
                if (nextChild[node] < children.size()) {
                    int child = children.get(nextChild[node]++);
                    if (state[child] == 1) {
                        back[node][child] = true;
                    } else if (state[child] == 0) {
                        state[child] = 1;
                        stack.push(child);
                    }
                } else {
                    state[node] = 2;
                    stack.pop();
                }
            }
        }
        return back;
    }

    private static boolean inRange(int node, int nodeCount) {
        return node >= 0 && node < nodeCount;
    }
}
