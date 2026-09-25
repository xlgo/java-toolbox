package com.aqishi.toolbox.feature.cloud.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.cloud.domain.DockerRunConverter;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;

/**
 * Docker Run 转 Docker Compose 转换器面板。
 */
public class DockerComposePanel extends ToolPanel {

    public DockerComposePanel() {
        super(ToolCatalog.DOCKER_CONVERT);
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        JTextArea input = Fields.area(8, 40);
        input.setText("docker run -d --name nginx-server -p 8080:80 -v /my/data:/usr/share/nginx/html -e TZ=Asia/Shanghai --restart always nginx:latest");

        JTextArea out = Fields.output(10, 40);

        JButton convert = Buttons.primary("转换为 Compose");
        JButton copy = Buttons.ghost("复制结果");
        JButton clear = Buttons.ghost("清空");

        // ===== 顶部操作卡片 =====
        // 这个工具没有可调参数，所以卡片里只放一行操作条：左侧说明支持的参数，右侧是按钮。
        // 按钮留在顶部而不是页面中央，命令与结果才能各自占满下方的空间。
        ActionBar bar = new ActionBar();
        bar.left(Fields.caption("识别 --name / -p / -v / -e / --restart / --network / --privileged / -h 等常用参数"));
        bar.right(clear);
        bar.right(convert);
        Card config = Card.titled("Docker Run 转 Compose");
        config.setContent(bar);

        // ===== 命令与 YAML 左右并排 =====
        // 两侧都是等宽文本，横向分栏时窗口一变宽两边同时受益；复制动作跟着结果卡片走。
        Card inputCard = Card.flush("输入 Docker Run 命令");
        inputCard.setContent(Fields.scroll(input));

        Card outputCard = Card.flush("输出 Docker Compose YAML");
        outputCard.setContent(Fields.scroll(out));
        outputCard.addHeaderAction(copy);

        root.add(config, BorderLayout.NORTH);
        root.add(Layouts.splitHorizontal(inputCard, outputCard, 0.5), BorderLayout.CENTER);

        convert.addActionListener(e -> {
            try {
                out.setText(convertToCompose(input.getText()));
            } catch (Exception ex) {
                out.setText("转换出错：" + ex.getMessage());
            }
        });

        copy.addActionListener(e -> UIUtils.copyToClipboard(out.getText()));
        clear.addActionListener(e -> { input.setText(""); out.setText(""); });

        // 默认触发一次转换
        convert.doClick();

        return root;
    }

    /** 转换失败时抛出异常，由调用方显示；被忽略的参数以 YAML 注释的形式列在结果末尾。 */
    private String convertToCompose(String runCmd) {
        if (runCmd == null || runCmd.trim().isEmpty()) return "";
        DockerRunConverter.Result result = DockerRunConverter.convert(runCmd);
        StringBuilder text = new StringBuilder(result.yaml());
        for (String warning : result.warnings()) {
            text.append("# ").append(warning).append('\n');
        }
        return text.toString();
    }
}
