package com.aqishi.toolbox.monitor;

import com.aqishi.toolbox.util.I18n;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制端的远程桌面视口窗口。
 */
public class RemoteControlWindow extends JFrame {

    private final DesktopChannel channel;
    private final String peerId;
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.concurrent.atomic.AtomicBoolean windowClosed = new java.util.concurrent.atomic.AtomicBoolean(false);

    private BufferedImage currentFrame;
    private ScreenPanel screenPanel;
    private JWindow floatToolbarWindow;

    // 功能子弹窗
    private FileTransferDialog fileTransferDialog;
    private RemoteTerminalDialog terminalDialog;

    // 画板功能变量
    private boolean drawMode = false;
    private Point lastDrawPoint = null;
    private final List<DrawingStroke> localStrokes = new ArrayList<>();

    public RemoteControlWindow(DesktopChannel channel, String peerId) {
        super(I18n.get("remote_desktop.window_title", peerId));
        this.channel = channel;
        this.peerId = peerId;

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1024, 768);
        setLocationRelativeTo(null);

        initComponents();
        setupListeners();
        setupFloatToolbar();

        channel.setMessageListener(this::dispatchMessage);
        channel.setCloseListener(() -> {
            if (windowClosed.compareAndSet(false, true)) {
                JOptionPane.showMessageDialog(this, I18n.get("remote_desktop.alert_disconnected"), I18n.get("remote_desktop.ft_title"), JOptionPane.INFORMATION_MESSAGE);
                closeAndDispose();
            }
        });
    }

    private void initComponents() {
        screenPanel = new ScreenPanel();
        add(screenPanel, BorderLayout.CENTER);
    }

    private void setupListeners() {
        screenPanel.setFocusable(true);
        screenPanel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (drawMode) return;
                sendControlEvent("key", "press", e.getKeyCode(), 0, 0, 0);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (drawMode) return;
                sendControlEvent("key", "release", e.getKeyCode(), 0, 0, 0);
            }
        });

        screenPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                screenPanel.requestFocusInWindow();
                if (drawMode) {
                    lastDrawPoint = getRatioCoordinates(e.getPoint());
                    return;
                }
                Point r = getRatioCoordinates(e.getPoint());
                if (r != null) {
                    sendControlEvent("mouse", "press", 0, e.getButton(), r.x / 10000.0, r.y / 10000.0);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (drawMode) {
                    lastDrawPoint = null;
                    return;
                }
                Point r = getRatioCoordinates(e.getPoint());
                if (r != null) {
                    sendControlEvent("mouse", "release", 0, e.getButton(), r.x / 10000.0, r.y / 10000.0);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                screenPanel.requestFocusInWindow();
            }
        });

        screenPanel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (drawMode) return;
                Point r = getRatioCoordinates(e.getPoint());
                if (r != null) {
                    sendControlEvent("mouse", "move", 0, 0, r.x / 10000.0, r.y / 10000.0);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Point r = getRatioCoordinates(e.getPoint());
                if (r == null) return;
                
                if (drawMode) {
                    if (lastDrawPoint != null) {
                        Point curr = r;
                        synchronized (localStrokes) {
                            localStrokes.add(new DrawingStroke(lastDrawPoint.x / 10000.0, lastDrawPoint.y / 10000.0,
                                    curr.x / 10000.0, curr.y / 10000.0, Color.RED, 4));
                        }
                        sendDrawingStroke(lastDrawPoint.x / 10000.0, lastDrawPoint.y / 10000.0,
                                curr.x / 10000.0, curr.y / 10000.0);
                        lastDrawPoint = curr;
                        screenPanel.repaint();
                    }
                } else {
                    sendControlEvent("mouse", "drag", 0, 0, r.x / 10000.0, r.y / 10000.0);
                }
            }
        });

        screenPanel.addMouseWheelListener(e -> {
            if (drawMode) return;
            Point r = getRatioCoordinates(e.getPoint());
            if (r != null) {
                sendControlEvent("mouse", "wheel", 0, e.getWheelRotation(), r.x / 10000.0, r.y / 10000.0);
            }
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeAndDispose();
            }
        });

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                positionFloatToolbar();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                positionFloatToolbar();
            }
        });
    }

    private void setupFloatToolbar() {
        floatToolbarWindow = new JWindow(this);
        floatToolbarWindow.setLayout(new FlowLayout(FlowLayout.CENTER, 8, 4));
        floatToolbarWindow.setBackground(new Color(45, 45, 45, 230));
        
        JLabel statusLbl = new JLabel(channel.isP2P() ? I18n.get("remote_desktop.status_p2p") : I18n.get("remote_desktop.status_relay"));
        statusLbl.setForeground(channel.isP2P() ? new Color(75, 181, 67) : new Color(255, 140, 0));
        statusLbl.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        floatToolbarWindow.add(statusLbl);
        floatToolbarWindow.add(new JLabel("|"));

        JButton ssBtn = createToolbarButton(I18n.get("remote_desktop.toolbar_ss"));
        JButton ftBtn = createToolbarButton(I18n.get("remote_desktop.toolbar_ft"));
        JButton cmdBtn = createToolbarButton(I18n.get("remote_desktop.toolbar_term"));
        JToggleButton drawBtn = new JToggleButton(I18n.get("remote_desktop.toolbar_draw"));
        drawBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        drawBtn.setPreferredSize(new Dimension(80, 24));
        
        JButton clearBtn = createToolbarButton(I18n.get("remote_desktop.toolbar_clear"));
        clearBtn.setEnabled(false);

        JButton disconnectBtn = createToolbarButton(I18n.get("remote_desktop.toolbar_disconnect"));

        floatToolbarWindow.add(ssBtn);
        floatToolbarWindow.add(ftBtn);
        floatToolbarWindow.add(cmdBtn);
        floatToolbarWindow.add(drawBtn);
        floatToolbarWindow.add(clearBtn);
        floatToolbarWindow.add(disconnectBtn);

        ssBtn.addActionListener(e -> takeLocalScreenshot());
        ftBtn.addActionListener(e -> {
            if (fileTransferDialog == null) {
                fileTransferDialog = new FileTransferDialog(this, channel);
            }
            fileTransferDialog.setVisible(true);
        });
        cmdBtn.addActionListener(e -> {
            if (terminalDialog == null) {
                terminalDialog = new RemoteTerminalDialog(this, channel);
            }
            terminalDialog.setVisible(true);
        });
        drawBtn.addActionListener(e -> {
            drawMode = drawBtn.isSelected();
            clearBtn.setEnabled(drawMode);
            screenPanel.requestFocusInWindow();
        });
        clearBtn.addActionListener(e -> {
            synchronized (localStrokes) {
                localStrokes.clear();
            }
            sendDrawingClear();
            screenPanel.repaint();
        });
        disconnectBtn.addActionListener(e -> closeAndDispose());

        floatToolbarWindow.pack();
        floatToolbarWindow.setVisible(true);
        
        SwingUtilities.invokeLater(this::positionFloatToolbar);
    }

    private JButton createToolbarButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        btn.setPreferredSize(new Dimension(90, 24));
        btn.setFocusable(false);
        return btn;
    }

    private void positionFloatToolbar() {
        if (floatToolbarWindow == null || !floatToolbarWindow.isShowing()) return;
        Point mainLoc = getLocationOnScreen();
        int toolbarX = mainLoc.x + (getWidth() - floatToolbarWindow.getWidth()) / 2;
        int toolbarY = mainLoc.y + 40;
        floatToolbarWindow.setLocation(toolbarX, toolbarY);
    }

    private Point getRatioCoordinates(Point p) {
        if (currentFrame == null) return null;
        int pw = screenPanel.getWidth();
        int ph = screenPanel.getHeight();
        int iw = currentFrame.getWidth();
        int ih = currentFrame.getHeight();

        double screenRatio = (double) pw / ph;
        double imgRatio = (double) iw / ih;

        int dw, dh, dx, dy;
        if (screenRatio > imgRatio) {
            dh = ph;
            dw = (int) (ph * imgRatio);
            dx = (pw - dw) / 2;
            dy = 0;
        } else {
            dw = pw;
            dh = (int) (pw / imgRatio);
            dx = 0;
            dy = (ph - dh) / 2;
        }

        if (p.x >= dx && p.x <= dx + dw && p.y >= dy && p.y <= dy + dh) {
            double rx = (double) (p.x - dx) / dw;
            double ry = (double) (p.y - dy) / dh;
            return new Point((int) (rx * 10000), (int) (ry * 10000));
        }
        return null;
    }

    private void sendControlEvent(String type, String action, int keyCode, int mouseBtn, double rx, double ry) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("type", type);
            event.put("action", action);
            event.put("keyCode", keyCode);
            event.put("button", mouseBtn);
            event.put("x", rx);
            event.put("y", ry);

            byte[] payload = mapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
            channel.send(new DesktopMessage(DesktopMessage.TYPE_CONTROL_EVENT, payload));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendDrawingStroke(double x1, double y1, double x2, double y2) {
        try {
            Map<String, Object> stroke = new HashMap<>();
            stroke.put("action", "draw");
            stroke.put("x1", x1);
            stroke.put("y1", y1);
            stroke.put("x2", x2);
            stroke.put("y2", y2);
            stroke.put("color", "#FF0000");
            stroke.put("thickness", 4);

            byte[] payload = mapper.writeValueAsString(stroke).getBytes(StandardCharsets.UTF_8);
            channel.send(new DesktopMessage(DesktopMessage.TYPE_DRAWING, payload));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendDrawingClear() {
        try {
            Map<String, Object> stroke = new HashMap<>();
            stroke.put("action", "clear");
            byte[] payload = mapper.writeValueAsString(stroke).getBytes(StandardCharsets.UTF_8);
            channel.send(new DesktopMessage(DesktopMessage.TYPE_DRAWING, payload));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void takeLocalScreenshot() {
        if (currentFrame == null) {
            JOptionPane.showMessageDialog(this, I18n.get("remote_desktop.alert_no_frame"), I18n.get("remote_desktop.ft_title"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(I18n.get("remote_desktop.ft_choose_title"));
        chooser.setSelectedFile(new File("remote_screenshot_" + System.currentTimeMillis() + ".png"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                ImageIO.write(currentFrame, "png", chooser.getSelectedFile());
                JOptionPane.showMessageDialog(this, I18n.get("remote_desktop.alert_ss_saved"), I18n.get("remote_desktop.ft_title"), JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, I18n.get("remote_desktop.alert_ss_fail", e.getMessage()), I18n.get("remote_desktop.ft_title"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void dispatchMessage(DesktopMessage msg) {
        switch (msg.getType()) {
            case DesktopMessage.TYPE_SCREEN_FRAME:
                try {
                    ByteArrayInputStream bais = new ByteArrayInputStream(msg.getPayload());
                    BufferedImage img = ImageIO.read(bais);
                    if (img != null) {
                        currentFrame = img;
                        screenPanel.repaint();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case DesktopMessage.TYPE_CMD_RESPONSE:
                if (terminalDialog != null && terminalDialog.isShowing()) {
                    try {
                        String jsonStr = new String(msg.getPayload(), StandardCharsets.UTF_8);
                        Map<String, Object> resp = mapper.readValue(jsonStr, Map.class);
                        terminalDialog.handleCmdResponse(resp);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                break;
            case DesktopMessage.TYPE_FILE_TRANSFER:
                if (fileTransferDialog != null && fileTransferDialog.isShowing()) {
                    fileTransferDialog.handleControlResponse(msg.getPayload());
                }
                break;
        }
    }

    private void closeAndDispose() {
        windowClosed.set(true);
        if (floatToolbarWindow != null) {
            floatToolbarWindow.dispose();
        }
        if (terminalDialog != null) {
            terminalDialog.dispose();
        }
        if (fileTransferDialog != null) {
            fileTransferDialog.dispose();
        }
        channel.close();
        dispose();
    }

    private class ScreenPanel extends JPanel {
        ScreenPanel() {
            setBackground(Color.BLACK);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (currentFrame == null) {
                g.setColor(Color.WHITE);
                g.drawString(I18n.get("remote_desktop.window_waiting"), getWidth() / 2 - 80, getHeight() / 2);
                return;
            }

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            int pw = getWidth();
            int ph = getHeight();
            int iw = currentFrame.getWidth();
            int ih = currentFrame.getHeight();

            double screenRatio = (double) pw / ph;
            double imgRatio = (double) iw / ih;

            int dw, dh, dx, dy;
            if (screenRatio > imgRatio) {
                dh = ph;
                dw = (int) (ph * imgRatio);
                dx = (pw - dw) / 2;
                dy = 0;
            } else {
                dw = pw;
                dh = (int) (pw / imgRatio);
                dx = 0;
                dy = (ph - dh) / 2;
            }

            g2.drawImage(currentFrame, dx, dy, dw, dh, null);

            if (drawMode) {
                g2.setColor(Color.RED);
                g2.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                synchronized (localStrokes) {
                    for (DrawingStroke s : localStrokes) {
                        int x1 = (int) (s.x1 * dw + dx);
                        int y1 = (int) (s.y1 * dh + dy);
                        int x2 = (int) (s.x2 * dw + dx);
                        int y2 = (int) (s.y2 * dh + dy);
                        g2.drawLine(x1, y1, x2, y2);
                    }
                }
            }

            g2.dispose();
        }
    }

    private static class DrawingStroke {
        double x1, y1, x2, y2;
        Color color;
        int thickness;

        DrawingStroke(double x1, double y1, double x2, double y2, Color color, int thickness) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.color = color;
            this.thickness = thickness;
        }
    }
}
