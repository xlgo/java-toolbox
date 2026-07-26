package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;

import javax.swing.*;
import java.awt.*;

/**
 * IP 子网计算器面板。
 */
public class SubnetPanel extends ToolPanel {

    public SubnetPanel() {
        super("dev", "subnet.calc",
                "Subnet", "CIDR", "IP", "子网掩码",
                "网络地址", "广播地址", "子网");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 配置卡片：输入 + 格式说明 =====
        // 说明文字交给 FormGrid.caption，自动与输入列左边缘对齐，不再手工调字号与颜色
        JTextField input = Fields.mono("192.168.1.100/24");

        FormGrid form = new FormGrid();
        form.row("IP/CIDR", input);
        form.caption("格式说明：输入带掩码的 IP 地址，例如 192.168.1.100/24 或 10.0.0.1/8");

        JButton btn = Buttons.primary("计算");
        Card config = Card.titled("子网参数");
        config.setContent(form);
        config.addHeaderAction(btn);

        // ===== 结果卡片：等宽明细占满剩余高度 =====
        JTextArea out = Fields.output(12, 60);
        Card result = Card.flush("子网划分详细信息");
        result.setContent(Fields.scroll(out));

        root.add(config, BorderLayout.NORTH);
        root.add(result, BorderLayout.CENTER);

        btn.addActionListener(e -> {
            out.setText(calculateSubnet(input.getText().trim()));
        });

        // 默认触发一次计算
        btn.doClick();

        return root;
    }

    private String calculateSubnet(String ipCidr) {
        try {
            String[] parts = ipCidr.split("/");
            if (parts.length != 2) {
                return "错误：请输入包含掩码的格式，例如 192.168.1.1/24";
            }
            String ipStr = parts[0].trim();
            int prefix = Integer.parseInt(parts[1].trim());
            if (prefix < 0 || prefix > 32) {
                return "错误：CIDR 掩码范围必须为 0 - 32";
            }

            // 解析 IP
            String[] ipBytes = ipStr.split("\\.");
            if (ipBytes.length != 4) {
                return "错误：IP 地址格式不合法";
            }
            long ip = 0;
            for (int i = 0; i < 4; i++) {
                int b = Integer.parseInt(ipBytes[i].trim());
                if (b < 0 || b > 255) return "错误：每个 IP 段必须在 0 - 255 之间";
                ip |= ((long) b) << (24 - i * 8);
            }

            // 计算掩码
            long mask = 0;
            if (prefix > 0) {
                mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            }

            long network = ip & mask;
            long broadcast = network | (~mask & 0xFFFFFFFFL);

            long firstIp = prefix >= 31 ? 0 : network + 1;
            long lastIp = prefix >= 31 ? 0 : broadcast - 1;
            long totalHosts = prefix >= 31 ? 0 : (broadcast - network - 1);
            if (prefix == 32) {
                firstIp = ip;
                lastIp = ip;
                totalHosts = 1;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("输入 IP/掩码:  ").append(ipStr).append("/").append(prefix).append("\n\n");
            
            sb.append("网络地址 (Network ID):      ").append(toIpStr(network))
                    .append("   (").append(toBinaryStr(network)).append(")\n");
            sb.append("子网掩码 (Subnet Mask):    ").append(toIpStr(mask))
                    .append("   (").append(toBinaryStr(mask)).append(")\n");
            sb.append("广播地址 (Broadcast ID):   ").append(toIpStr(broadcast))
                    .append("   (").append(toBinaryStr(broadcast)).append(")\n\n");

            if (prefix < 31) {
                sb.append("可用 IP 范围:  ").append(toIpStr(firstIp)).append(" - ").append(toIpStr(lastIp)).append("\n");
            } else if (prefix == 32) {
                sb.append("可用 IP 范围:  ").append(toIpStr(firstIp)).append("\n");
            } else {
                sb.append("可用 IP 范围:  点对点连接 (无独立主机 IP)\n");
            }
            sb.append("可用主机数 (Hosts): ").append(totalHosts).append(" 个\n");

            return sb.toString();
        } catch (NumberFormatException e) {
            return "错误：掩码位数或 IP 各段必须为数字";
        } catch (Exception e) {
            return "计算出错: " + e.getMessage();
        }
    }

    private String toIpStr(long ip) {
        return ((ip >> 24) & 0xFF) + "." +
               ((ip >> 16) & 0xFF) + "." +
               ((ip >> 8) & 0xFF) + "." +
               (ip & 0xFF);
    }

    private String toBinaryStr(long val) {
        StringBuilder sb = new StringBuilder();
        for (int i = 24; i >= 0; i -= 8) {
            String b = Long.toBinaryString((val >> i) & 0xFF);
            while (b.length() < 8) b = "0" + b;
            sb.append(b);
            if (i > 0) sb.append(".");
        }
        return sb.toString();
    }
}
