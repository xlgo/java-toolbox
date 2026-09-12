package com.aqishi.toolbox.feature.cloud.ui;

import com.aqishi.toolbox.util.Json;
import com.aqishi.toolbox.util.UIUtils;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pod 级操作的公共步骤。
 *
 * <p>容器控制台、文件下载与上传在开始前都要先解析目标 Pod 的容器列表：
 * 单容器直接使用，多容器弹窗让用户选择。</p>
 */
final class K8sPodOperations {

    private K8sPodOperations() {
    }

    /**
     * 拉取 Pod 的容器列表并回调选中的容器名。
     *
     * <p>解析失败或没有容器时提示错误且不回调；Pod 含多个容器时弹窗选择，
     * 用户取消同样不回调。</p>
     */
    static void pickContainer(Component parent, K8sClusterContext ctx,
                              String ns, String podName, Consumer<String> onContainer) {
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                String resp = ctx.request("GET",
                        "/api/v1/namespaces/" + ns + "/pods/" + podName, null);
                JsonNode root = Json.mapper().readTree(resp);
                List<String> list = new ArrayList<>();
                JsonNode specs = root.path("spec").path("containers");
                if (specs.isArray()) {
                    for (JsonNode c : specs) {
                        list.add(c.path("name").asText());
                    }
                }
                return list;
            }

            @Override
            protected void done() {
                List<String> containers;
                try {
                    containers = get();
                } catch (Exception ex) {
                    UIUtils.error(parent, "获取 Pod 详情失败: " + ex.getMessage());
                    return;
                }
                if (containers.isEmpty()) {
                    UIUtils.error(parent, "找不到容器配置！");
                    return;
                }
                if (containers.size() == 1) {
                    onContainer.accept(containers.get(0));
                    return;
                }
                String[] arr = containers.toArray(new String[0]);
                String choice = (String) JOptionPane.showInputDialog(
                        parent,
                        "Pod 中包含多个容器，请选择容器：",
                        "选择容器",
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        arr,
                        arr[0]);
                if (choice != null) {
                    onContainer.accept(choice);
                }
            }
        }.execute();
    }
}
