package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker Run 转 Docker Compose 转换器面板。
 */
public class DockerComposePanel extends ToolPanel {

    public DockerComposePanel() {
        super("杂项", "Docker 转换");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 顶部：操作按钮 =====
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton convert = UIUtils.button("转换为 Compose", 130);
        JButton copy = UIUtils.button("复制结果", 100);
        JButton clear = UIUtils.button("清空", 80);
        btns.add(convert); btns.add(copy); btns.add(clear);
        root.add(btns, BorderLayout.NORTH);

        // ===== 中间：输入输出 =====
        JTextArea input = new JTextArea(8, 40);
        input.setFont(UIUtils.monoFont());
        input.setLineWrap(true);
        input.setText("docker run -d --name nginx-server -p 8080:80 -v /my/data:/usr/share/nginx/html -e TZ=Asia/Shanghai --restart always nginx:latest");

        JTextArea out = new JTextArea(10, 40);
        out.setFont(UIUtils.monoFont());
        out.setEditable(false);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                UIUtils.scrollText(input, "输入 Docker Run 命令"),
                UIUtils.scrollText(out, "输出 Docker Compose YAML"));
        split.setResizeWeight(0.4);
        root.add(split, BorderLayout.CENTER);

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

    private String convertToCompose(String runCmd) {
        if (runCmd == null || runCmd.trim().isEmpty()) return "";

        List<String> args = parseCommandLine(runCmd.trim());
        if (args.isEmpty() || (!args.get(0).equalsIgnoreCase("docker") && !args.get(0).equalsIgnoreCase("sudo"))) {
            return "# 错误：命令格式无效，必须以 'docker' 或 'sudo docker' 开头。";
        }
        
        int startIndex = 0;
        if (args.get(0).equalsIgnoreCase("sudo")) {
            startIndex = 1;
        }
        
        if (args.size() <= startIndex + 1 || !args.get(startIndex).equalsIgnoreCase("docker") || !args.get(startIndex + 1).equalsIgnoreCase("run")) {
            return "# 错误：仅支持转换 'docker run' 类型的命令。";
        }

        String name = "my-service";
        String image = null;
        List<String> ports = new ArrayList<>();
        List<String> volumes = new ArrayList<>();
        List<String> env = new ArrayList<>();
        String restart = null;
        String network = null;
        boolean privileged = false;
        String hostname = null;
        List<String> command = new ArrayList<>();

        int i = startIndex + 2;
        while (i < args.size()) {
            String arg = args.get(i);
            if (arg.startsWith("-")) {
                if (arg.equals("-d") || arg.equals("--detach")) {
                    i++;
                } else if (arg.equals("--name")) {
                    if (i + 1 < args.size()) name = cleanQuotes(args.get(i + 1));
                    i += 2;
                } else if (arg.equals("-p") || arg.equals("--publish")) {
                    if (i + 1 < args.size()) ports.add(cleanQuotes(args.get(i + 1)));
                    i += 2;
                } else if (arg.equals("-v") || arg.equals("--volume")) {
                    if (i + 1 < args.size()) volumes.add(cleanQuotes(args.get(i + 1)));
                    i += 2;
                } else if (arg.equals("-e") || arg.equals("--env")) {
                    if (i + 1 < args.size()) env.add(cleanQuotes(args.get(i + 1)));
                    i += 2;
                } else if (arg.equals("--restart")) {
                    if (i + 1 < args.size()) restart = cleanQuotes(args.get(i + 1));
                    i += 2;
                } else if (arg.equals("--network") || arg.equals("--net")) {
                    if (i + 1 < args.size()) network = cleanQuotes(args.get(i + 1));
                    i += 2;
                } else if (arg.equals("--privileged")) {
                    privileged = true;
                    i++;
                } else if (arg.equals("-h") || arg.equals("--hostname")) {
                    if (i + 1 < args.size()) hostname = cleanQuotes(args.get(i + 1));
                    i += 2;
                } else {
                    // 遇到未知参数，如果是单字母或合并的参数跳过
                    i++;
                }
            } else {
                image = arg;
                i++;
                while (i < args.size()) {
                    command.add(args.get(i));
                    i++;
                }
                break;
            }
        }

        if (image == null) {
            return "# 错误：无法从命令中识别出 Image（镜像名称）。";
        }

        StringBuilder yaml = new StringBuilder();
        yaml.append("version: '3.8'\n\n");
        yaml.append("services:\n");
        yaml.append("  ").append(name).append(":\n");
        yaml.append("    image: ").append(image).append("\n");
        yaml.append("    container_name: ").append(name).append("\n");

        if (restart != null) {
            yaml.append("    restart: ").append(restart).append("\n");
        }
        if (privileged) {
            yaml.append("    privileged: true\n");
        }
        if (hostname != null) {
            yaml.append("    hostname: ").append(hostname).append("\n");
        }

        if (!ports.isEmpty()) {
            yaml.append("    ports:\n");
            for (String p : ports) {
                yaml.append("      - \"").append(p).append("\"\n");
            }
        }

        if (!volumes.isEmpty()) {
            yaml.append("    volumes:\n");
            for (String v : volumes) {
                yaml.append("      - ").append(v).append("\n");
            }
        }

        if (!env.isEmpty()) {
            yaml.append("    environment:\n");
            for (String e : env) {
                yaml.append("      - ").append(e).append("\n");
            }
        }

        if (network != null) {
            yaml.append("    networks:\n");
            yaml.append("      - ").append(network).append("\n");
            yaml.append("networks:\n");
            yaml.append("  ").append(network).append(":\n");
            yaml.append("    external: true\n");
        }

        if (!command.isEmpty()) {
            yaml.append("    command: ");
            for (String c : command) {
                yaml.append(c).append(" ");
            }
            yaml.append("\n");
        }

        return yaml.toString();
    }

    private String cleanQuotes(String s) {
        if (s == null) return "";
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private List<String> parseCommandLine(String cmd) {
        List<String> list = new ArrayList<>();
        // 精确匹配带空格和引号的参数
        Matcher m = Pattern.compile("([^\"'\\s]+|'[^']*'|\"[^\"]*\")").matcher(cmd);
        while (m.find()) {
            list.add(m.group());
        }
        return list;
    }
}
