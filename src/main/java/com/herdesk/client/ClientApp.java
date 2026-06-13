package com.herdesk.client;

import com.herdesk.common.AppLogger;
import com.herdesk.common.ConnectionMode;
import com.herdesk.common.LogPanel;
import com.herdesk.common.Protocol;
import com.herdesk.common.QualityLevel;
import com.herdesk.common.RelayConnector;
import com.herdesk.common.ScreenGeometry;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * 控制端 Swing 界面。
 */
public class ClientApp {

    private JFrame frame;
    private JComboBox<ConnectionMode> modeBox;
    private JPanel modeCardPanel;
    private JTextField hostField;
    private JTextField portField;
    private JTextField relayHostField;
    private JTextField relayPortField;
    private JTextField roomIdField;
    private JButton connectButton;
    private JButton disconnectButton;
    private JButton fullscreenButton;
    private JButton exitFullscreenButton;
    private JLabel fullscreenFpsLabel;
    private JPanel topPanel;
    private JPanel bottomPanel;
    private JPanel fullscreenBar;
    private boolean fullscreen;
    private Rectangle restoredBounds;
    private JLabel statusLabel;
    private JLabel fpsLabel;
    private JComboBox<QualityLevel> qualityBox;
    private RemoteViewPanel viewPanel;
    private RemoteDesktopClient client;
    private FrameDisplayScheduler frameScheduler;
    private LogPanel logPanel;

    public void start() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                createAndShowUi();
            }
        });
    }

    private void createAndShowUi() {
        frame = new JFrame("Her Desk - 控制端");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (fullscreen) {
                    exitFullscreen();
                }
                disconnect();
                frame.dispose();
            }
        });

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        topPanel = new JPanel(new BorderLayout(8, 8));

        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        modeRow.add(new JLabel("连接模式:"));
        modeBox = new JComboBox<ConnectionMode>(ConnectionMode.values());
        modeBox.addActionListener(e -> switchModePanel());
        modeRow.add(modeBox);
        modeRow.add(new JLabel("画质:"));
        qualityBox = new JComboBox<QualityLevel>(QualityLevel.values());
        qualityBox.setSelectedItem(QualityLevel.BALANCED);
        qualityBox.addActionListener(e -> onQualityChanged());
        modeRow.add(qualityBox);
        topPanel.add(modeRow, BorderLayout.NORTH);

        modeCardPanel = new JPanel(new CardLayout());
        modeCardPanel.add(createDirectPanel(), ConnectionMode.DIRECT.name());
        modeCardPanel.add(createRelayPanel(), ConnectionMode.RELAY.name());
        topPanel.add(modeCardPanel, BorderLayout.CENTER);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        connectButton = new JButton("连接");
        disconnectButton = new JButton("断开");
        disconnectButton.setEnabled(false);
        connectButton.addActionListener(e -> connect());
        disconnectButton.addActionListener(e -> disconnect());
        actionRow.add(connectButton);
        actionRow.add(disconnectButton);
        fullscreenButton = new JButton("全屏");
        fullscreenButton.setEnabled(false);
        fullscreenButton.addActionListener(e -> enterFullscreen());
        actionRow.add(fullscreenButton);
        topPanel.add(actionRow, BorderLayout.SOUTH);

        viewPanel = new RemoteViewPanel();
        frameScheduler = new FrameDisplayScheduler(image -> viewPanel.updateFrame(image));
        bindInputListeners();

        fullscreenBar = new JPanel(new BorderLayout(8, 0));
        fullscreenBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        fullscreenBar.setVisible(false);
        exitFullscreenButton = new JButton("退出全屏");
        exitFullscreenButton.addActionListener(e -> exitFullscreen());
        JPanel fullscreenBarLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        fullscreenBarLeft.add(exitFullscreenButton);
        fullscreenBarLeft.add(new JLabel("全屏模式 — Esc 退出"));
        fullscreenBar.add(fullscreenBarLeft, BorderLayout.WEST);
        fullscreenFpsLabel = new JLabel("帧率: -");
        fullscreenBar.add(fullscreenFpsLabel, BorderLayout.EAST);

        JPanel centerPanel = new JPanel(new BorderLayout(0, 0));
        centerPanel.add(fullscreenBar, BorderLayout.NORTH);
        centerPanel.add(viewPanel, BorderLayout.CENTER);

        bottomPanel = new JPanel(new BorderLayout(4, 4));
        statusLabel = new JLabel("状态: 未连接");
        fpsLabel = new JLabel("帧率: -");
        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.add(statusLabel, BorderLayout.WEST);
        statusRow.add(fpsLabel, BorderLayout.EAST);
        bottomPanel.add(statusRow, BorderLayout.NORTH);
        logPanel = new LogPanel(8);
        bottomPanel.add(logPanel, BorderLayout.CENTER);

        root.add(topPanel, BorderLayout.NORTH);
        root.add(centerPanel, BorderLayout.CENTER);
        root.add(bottomPanel, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setSize(1100, 860);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        switchModePanel();

        client = new RemoteDesktopClient(new RemoteDesktopClient.ClientListener() {
            @Override
            public void onStatusChanged(String status) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("状态: " + status));
            }

            @Override
            public void onConnected(ScreenGeometry geometry) {
                SwingUtilities.invokeLater(() -> {
                    viewPanel.setRemoteScreenGeometry(geometry);
                    setInputsEnabled(false);
                    viewPanel.requestFocusInWindow();
                });
            }

            @Override
            public void onDisconnected(String reason) {
                SwingUtilities.invokeLater(() -> {
                    if (fullscreen) {
                        exitFullscreen();
                    }
                    frameScheduler.reset();
                    viewPanel.clearFrame();
                    setInputsEnabled(true);
                    updateFpsLabels(-1);
                });
            }

            @Override
            public void onFrameUpdated(final java.awt.image.BufferedImage frameImage) {
                frameScheduler.submit(frameImage);
            }

            @Override
            public void onError(String message) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("状态: " + message));
            }

            @Override
            public void onFpsUpdated(int fps) {
                SwingUtilities.invokeLater(() -> updateFpsLabels(fps));
            }

            @Override
            public void onLog(AppLogger.Level level, String message) {
                logPanel.append(level, message);
            }
        });
        logPanel.append(AppLogger.Level.INFO, "控制端已就绪");
    }

    private JPanel createDirectPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panel.add(new JLabel("被控端 IP:"));
        hostField = new JTextField("192.168.1.100", 14);
        panel.add(hostField);
        panel.add(new JLabel("端口:"));
        portField = new JTextField(String.valueOf(Protocol.DEFAULT_PORT), 6);
        panel.add(portField);
        return panel;
    }

    private JPanel createRelayPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 6, 8, 0));
        panel.add(new JLabel("中继地址:"));
        relayHostField = new JTextField("your-server.com", 14);
        panel.add(relayHostField);
        panel.add(new JLabel("中继端口:"));
        relayPortField = new JTextField(String.valueOf(RelayConnector.DEFAULT_RELAY_PORT), 6);
        panel.add(relayPortField);
        panel.add(new JLabel("房间号:"));
        roomIdField = new JTextField("room001", 10);
        panel.add(roomIdField);
        return panel;
    }

    private void switchModePanel() {
        ConnectionMode mode = (ConnectionMode) modeBox.getSelectedItem();
        CardLayout layout = (CardLayout) modeCardPanel.getLayout();
        layout.show(modeCardPanel, mode.name());
    }

    private void bindInputListeners() {
        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                sendMouseMove(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                sendMouseMove(e);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                viewPanel.requestFocusInWindow();
                sendMouseButton(e, true);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                sendMouseButton(e, false);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (client == null || !client.isConnected()) {
                    return;
                }
                int[] remote = viewPanel.translateToRemote(e.getX(), e.getY());
                client.sendMouseWheel(remote[0], remote[1], e.getWheelRotation());
            }
        };
        viewPanel.addMouseListener(mouseAdapter);
        viewPanel.addMouseMotionListener(mouseAdapter);
        viewPanel.addMouseWheelListener(mouseAdapter);

        viewPanel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE && fullscreen) {
                    exitFullscreen();
                    return;
                }
                sendKey(e, true);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE && fullscreen) {
                    return;
                }
                sendKey(e, false);
            }
        });
    }

    private void enterFullscreen() {
        if (fullscreen || client == null || !client.isConnected()) {
            return;
        }
        restoredBounds = frame.getBounds();
        topPanel.setVisible(false);
        bottomPanel.setVisible(false);
        fullscreenBar.setVisible(true);
        fullscreen = true;
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        viewPanel.requestFocusInWindow();
        logPanel.append(AppLogger.Level.INFO, "进入全屏模式");
        frame.revalidate();
        frame.repaint();
    }

    private void exitFullscreen() {
        if (!fullscreen) {
            return;
        }
        topPanel.setVisible(true);
        bottomPanel.setVisible(true);
        fullscreenBar.setVisible(false);
        fullscreen = false;
        frame.setExtendedState(JFrame.NORMAL);
        if (restoredBounds != null) {
            frame.setBounds(restoredBounds);
        } else {
            frame.setSize(1100, 860);
            frame.setLocationRelativeTo(null);
        }
        viewPanel.requestFocusInWindow();
        logPanel.append(AppLogger.Level.INFO, "退出全屏模式");
        frame.revalidate();
        frame.repaint();
    }

    private void updateFpsLabels(int fps) {
        String text = "帧率: " + (fps > 0 ? fps + " FPS" : "-");
        fpsLabel.setText(text);
        fullscreenFpsLabel.setText(text);
    }

    private void sendMouseMove(MouseEvent e) {
        if (client == null || !client.isConnected()) {
            return;
        }
        int[] remote = viewPanel.translateToRemote(e.getX(), e.getY());
        client.sendMouseMove(remote[0], remote[1]);
    }

    private void sendMouseButton(MouseEvent e, boolean press) {
        if (client == null || !client.isConnected()) {
            return;
        }
        int[] remote = viewPanel.translateToRemote(e.getX(), e.getY());
        int button = Protocol.MOUSE_LEFT;
        if (SwingUtilities.isRightMouseButton(e)) {
            button = Protocol.MOUSE_RIGHT;
        } else if (SwingUtilities.isMiddleMouseButton(e)) {
            button = Protocol.MOUSE_MIDDLE;
        }
        client.sendMouseButton(remote[0], remote[1], button, press);
    }

    private void sendKey(KeyEvent e, boolean press) {
        if (client == null || !client.isConnected()) {
            return;
        }
        client.sendKeyEvent(e.getKeyCode(), e.getModifiersEx(), press);
    }

    private void connect() {
        ConnectionMode mode = (ConnectionMode) modeBox.getSelectedItem();
        try {
            if (mode == ConnectionMode.RELAY) {
                String relayHost = relayHostField.getText().trim();
                String roomId = roomIdField.getText().trim();
                int relayPort = parsePort(relayPortField.getText().trim(), RelayConnector.DEFAULT_RELAY_PORT);
                if (relayHost.isEmpty()) {
                    String msg = "请输入中继地址";
                    statusLabel.setText("状态: " + msg);
                    logPanel.append(AppLogger.Level.ERROR, msg);
                    return;
                }
                RelayConnector.validateRoomId(roomId);
                logPanel.append(AppLogger.Level.INFO,
                        "用户发起连接：公网中继 " + relayHost + ":" + relayPort + "，房间 " + roomId);
                client.connectViaRelay(relayHost, relayPort, roomId);
            } else {
                String host = hostField.getText().trim();
                int port = parsePort(portField.getText().trim(), Protocol.DEFAULT_PORT);
                if (host.isEmpty()) {
                    String msg = "请输入 IP";
                    statusLabel.setText("状态: " + msg);
                    logPanel.append(AppLogger.Level.ERROR, msg);
                    return;
                }
                logPanel.append(AppLogger.Level.INFO,
                        "用户发起连接：内网直连 " + host + ":" + port + "，画质 "
                                + qualityBox.getSelectedItem());
                client.connect(host, port);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            statusLabel.setText("状态: " + msg);
            logPanel.append(AppLogger.Level.ERROR, "连接参数错误：" + msg);
        }
    }

    private int parsePort(String text, int defaultValue) {
        if (text == null || text.isEmpty()) {
            return defaultValue;
        }
        int port = Integer.parseInt(text);
        if (port < 1 || port > 65535) {
            throw new NumberFormatException("端口无效");
        }
        return port;
    }

    private void disconnect() {
        if (client != null) {
            logPanel.append(AppLogger.Level.INFO, "用户点击断开连接");
            client.disconnect();
        }
    }

    private void onQualityChanged() {
        if (client != null) {
            QualityLevel level = (QualityLevel) qualityBox.getSelectedItem();
            client.setQuality(level);
        }
    }

    private void setInputsEnabled(boolean enabled) {
        modeBox.setEnabled(enabled);
        hostField.setEnabled(enabled);
        portField.setEnabled(enabled);
        relayHostField.setEnabled(enabled);
        relayPortField.setEnabled(enabled);
        roomIdField.setEnabled(enabled);
        connectButton.setEnabled(enabled);
        disconnectButton.setEnabled(!enabled);
        qualityBox.setEnabled(enabled);
        fullscreenButton.setEnabled(!enabled);
    }
}
