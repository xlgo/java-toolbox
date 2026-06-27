package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;

/**
 * IP 子网计算器面板。
 */
public class SubnetPanel extends ToolPanel {

    public SubnetPanel() {
        super("杂项", "子网计算器");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 顶部：输入 =====
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JLabel label = new JLabel("IP/CIDR:");
        label.setFont(UIUtils.plainFont());
        JTextField input = new JTextField("192.168.1.100/24");
        input.setFont(UIUtils.monoFont());
        input.setPreferredSize(new Dimension(0, 32));

        JButton btn = UIUtils.button("计算", 80);

        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        inputRow.add(label, BorderLayout.WEST);
        inputRow.add(input, BorderLayout.CENTER);
        inputRow.add(btn, BorderLayout.EAST);

        top.add(inputRow, BorderLayout.NORTH);

        JLabel desc = new JLabel("格式说明：输入带掩码的 IP 地址，例如 192.168.1.100/24 或 10.0.0.1/8");
        desc.setFont(UIUtils.plainFont().deriveFont(11f));
        desc.setForeground(UIManager.getColor("Label.disabledForeground"));
        top.add(desc, BorderLayout.SOUTH);

        root.add(top, BorderLayout.NORTH);

        // ===== 中间：计算结果 =====
        JTextArea out = new JTextArea();
        out.setFont(UIUtils.monoFont());
        out.setEditable(false);
        JScrollPane scroll = UIUtils.scrollText(out, "子网划分详细信息");
        root.add(scroll, BorderLayout.CENTER);

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
