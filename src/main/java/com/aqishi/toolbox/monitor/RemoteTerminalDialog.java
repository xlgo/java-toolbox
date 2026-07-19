package com.aqishi.toolbox.monitor;

import com.aqishi.toolbox.util.I18n;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 控制端的远程命令行交互终端对话框。
 */
public class RemoteTerminalDialog extends JDialog {

    private final DesktopChannel channel;
    private final String sessionId;
    private final ObjectMapper mapper = new ObjectMapper();

    private JTextArea terminalArea;
    private JTextField inputField;
    private JButton clearBtn;

    public RemoteTerminalDialog(Window parent, DesktopChannel channel) {
        super(parent, I18n.get("remote_desktop.term_title"), ModalityType.MODELESS);
        this.channel = channel;
        this.sessionId = UUID.randomUUID().toString();

        initComponents();
        setupListeners();

        setSize(700, 450);
        setLocationRelativeTo(parent);

        sendSystemCommand("");
    }

    private void initComponents() {
        setLayout(new BorderLayout(5, 5));

        terminalArea = new JTextArea();
        terminalArea.setEditable(false);
        terminalArea.setFont(new Font("Consolas", Font.PLAIN, 14));
        terminalArea.setBackground(new Color(30, 30, 30));
        terminalArea.setForeground(new Color(220, 220, 220));
        terminalArea.setLineWrap(true);
        terminalArea.setWrapStyleWord(true);
        terminalArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        DefaultCaret caret = (DefaultCaret) terminalArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane scroll = new JScrollPane(terminalArea);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, UIManager.getColor("Component.borderColor")));
        add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(5, 5));
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JLabel promptLabel = new JLabel("CMD > ");
        promptLabel.setFont(new Font("Consolas", Font.BOLD, 14));
        bottom.add(promptLabel, BorderLayout.WEST);

        inputField = new JTextField();
        inputField.setFont(new Font("Consolas", Font.PLAIN, 14));
        bottom.add(inputField, BorderLayout.CENTER);

        clearBtn = new JButton(I18n.get("remote_desktop.term_clear"));
        bottom.add(clearBtn, BorderLayout.EAST);

        add(bottom, BorderLayout.SOUTH);
    }

    private void setupListeners() {
        inputField.addActionListener(e -> {
            String cmd = inputField.getText().trim();
            if (!cmd.isEmpty()) {
                appendOutput("> " + cmd + "\n");
                sendSystemCommand(cmd);
                inputField.setText("");
            }
        });

        clearBtn.addActionListener(e -> terminalArea.setText(""));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                sendCloseTerminalRequest();
            }
        });
    }

    public void handleCmdResponse(Map<String, Object> resp) {
        String output = (String) resp.get("output");
        if (output != null) {
            appendOutput(output);
        }
    }

    private void appendOutput(String text) {
        SwingUtilities.invokeLater(() -> {
            terminalArea.append(text);
            terminalArea.setCaretPosition(terminalArea.getDocument().getLength());
        });
    }

    private void sendSystemCommand(String cmd) {
        try {
            Map<String, Object> req = new HashMap<>();
            req.put("action", "exec");
            req.put("command", cmd);
            req.put("sessionId", sessionId);

            byte[] payload = mapper.writeValueAsString(req).getBytes(StandardCharsets.UTF_8);
            channel.send(new DesktopMessage(DesktopMessage.TYPE_CMD_REQUEST, payload));
        } catch (Exception e) {
            appendOutput(I18n.get("remote_desktop.term_send_fail", e.getMessage()) + "\n");
        }
    }

    private void sendCloseTerminalRequest() {
        try {
            Map<String, Object> req = new HashMap<>();
            req.put("action", "close");
            req.put("sessionId", sessionId);

            byte[] payload = mapper.writeValueAsString(req).getBytes(StandardCharsets.UTF_8);
            channel.send(new DesktopMessage(DesktopMessage.TYPE_CMD_REQUEST, payload));
        } catch (Exception ignored) {}
    }
}
