package com.herdesk.client;

import com.herdesk.common.AppLogger;
import com.herdesk.common.ConnectionMode;
import com.herdesk.common.LogPanel;
import com.herdesk.common.Protocol;
import com.herdesk.common.QualityLevel;
import com.herdesk.common.RelayAuth;
import com.herdesk.common.RelayConnector;
import com.herdesk.common.RoomIdGenerator;
import com.herdesk.common.ScreenGeometry;
import com.herdesk.common.UiTheme;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;

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
    private JTextField roomPasswordField;
    private JButton connectButton;
    private JButton disconnectButton;
    private JButton fullscreenButton;
    private JButton exitFullscreenButton;
    private JPanel fullscreenGlassPane;
    private JFrame fullscreenFrame;
    private JPanel viewCard;
    private Border viewPanelBorder;
    private GraphicsDevice fullscreenDevice;
    private JPanel topPanel;
    private JPanel bottomPanel;
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
        UiTheme.install();
        frame = new JFrame("Her Desk - 控制端");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        UiTheme.styleFrame(frame);
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

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(UiTheme.BACKGROUND);
        root.add(UiTheme.createHeader("Her Desk 控制端", "连接并远程操作被控端桌面"), BorderLayout.NORTH);

        JPanel body = UiTheme.createRootPanel();
        body.setLayout(new BorderLayout(0, 10));

        topPanel = UiTheme.createCard("连接设置");
        topPanel.setLayout(new BorderLayout(8, 10));

        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        modeRow.setOpaque(false);
        JLabel modeLabel = new JLabel("连接模式");
        UiTheme.styleLabel(modeLabel);
        modeRow.add(modeLabel);
        modeBox = new JComboBox<ConnectionMode>(ConnectionMode.values());
        UiTheme.styleComboBox(modeBox);
        modeBox.addActionListener(e -> switchModePanel());
        modeRow.add(modeBox);
        JLabel qualityLabel = new JLabel("画质");
        UiTheme.styleLabel(qualityLabel);
        modeRow.add(qualityLabel);
        qualityBox = new JComboBox<QualityLevel>(QualityLevel.values());
        UiTheme.styleComboBox(qualityBox);
        qualityBox.setSelectedItem(QualityLevel.BALANCED);
        qualityBox.addActionListener(e -> onQualityChanged());
        modeRow.add(qualityBox);
        topPanel.add(modeRow, BorderLayout.NORTH);

        modeCardPanel = new JPanel(new CardLayout());
        modeCardPanel.setOpaque(false);
        modeCardPanel.add(createDirectPanel(), ConnectionMode.DIRECT.name());
        modeCardPanel.add(createRelayPanel(), ConnectionMode.RELAY.name());
        topPanel.add(modeCardPanel, BorderLayout.CENTER);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        actionRow.setOpaque(false);
        connectButton = UiTheme.createPrimaryButton("连接");
        disconnectButton = UiTheme.createDangerButton("断开");
        disconnectButton.setEnabled(false);
        connectButton.addActionListener(e -> connect());
        disconnectButton.addActionListener(e -> disconnect());
        fullscreenButton = UiTheme.createSecondaryButton("全屏");
        fullscreenButton.setEnabled(false);
        fullscreenButton.addActionListener(e -> enterFullscreen());
        actionRow.add(connectButton);
        actionRow.add(disconnectButton);
        actionRow.add(fullscreenButton);
        topPanel.add(actionRow, BorderLayout.SOUTH);

        viewPanel = new RemoteViewPanel();
        viewPanelBorder = viewPanel.getBorder();
        frameScheduler = new FrameDisplayScheduler(image -> viewPanel.updateFrame(image));
        bindInputListeners();

        exitFullscreenButton = UiTheme.createPrimaryButton("退出全屏");
        exitFullscreenButton.addActionListener(e -> exitFullscreen());

        viewCard = UiTheme.createCard("远程画面");
        viewCard.setLayout(new BorderLayout());
        viewCard.add(viewPanel, BorderLayout.CENTER);

        JPanel centerPanel = new JPanel(new BorderLayout(0, 6));
        centerPanel.setOpaque(false);
        centerPanel.add(viewCard, BorderLayout.CENTER);

        bottomPanel = new JPanel(new BorderLayout(6, 6));
        bottomPanel.setOpaque(false);
        JPanel statusRow = UiTheme.createStatusBar();
        statusLabel = new JLabel("状态: 未连接");
        UiTheme.styleStatusLabel(statusLabel);
        fpsLabel = new JLabel("帧率: -");
        UiTheme.styleStatusLabel(fpsLabel);
        statusRow.add(statusLabel, BorderLayout.WEST);
        statusRow.add(fpsLabel, BorderLayout.EAST);
        bottomPanel.add(statusRow, BorderLayout.NORTH);
        logPanel = new LogPanel(8);
        bottomPanel.add(logPanel, BorderLayout.CENTER);

        body.add(topPanel, BorderLayout.NORTH);
        body.add(centerPanel, BorderLayout.CENTER);
        body.add(bottomPanel, BorderLayout.SOUTH);
        root.add(body, BorderLayout.CENTER);

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
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        panel.setOpaque(false);
        JLabel hostLabel = new JLabel("被控端 IP");
        UiTheme.styleLabel(hostLabel);
        panel.add(hostLabel);
        hostField = new JTextField("192.168.1.100", 16);
        UiTheme.styleNetworkField(hostField);
        panel.add(hostField);
        JLabel portLabel = new JLabel("端口");
        UiTheme.styleLabel(portLabel);
        panel.add(portLabel);
        portField = new JTextField(String.valueOf(Protocol.DEFAULT_PORT), 8);
        UiTheme.styleNetworkField(portField);
        panel.add(portField);
        return panel;
    }

    private JPanel createRelayPanel() {
        JPanel panel = new JPanel(new GridLayout(3, 4, 10, 8));
        panel.setOpaque(false);
        JLabel h1 = new JLabel("中继地址");
        UiTheme.styleLabel(h1);
        panel.add(h1);
        relayHostField = new JTextField("198.176.62.33", 18);
        UiTheme.styleNetworkField(relayHostField);
        panel.add(relayHostField);
        JLabel h2 = new JLabel("中继端口");
        UiTheme.styleLabel(h2);
        panel.add(h2);
        relayPortField = new JTextField("11111", 8);
        UiTheme.styleNetworkField(relayPortField);
        panel.add(relayPortField);
        JLabel h3 = new JLabel("房间号");
        UiTheme.styleLabel(h3);
        panel.add(h3);
        roomIdField = new JTextField(12);
        UiTheme.styleNetworkField(roomIdField);
        panel.add(roomIdField);
        JLabel h4 = new JLabel("房间密码");
        UiTheme.styleLabel(h4);
        panel.add(h4);
        roomPasswordField = new JTextField(RelayAuth.DEFAULT_PASSWORD, 12);
        UiTheme.styleNetworkField(roomPasswordField);
        panel.add(roomPasswordField);
        panel.add(new JLabel());
        panel.add(new JLabel());
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
        fullscreen = true;

        viewCard.remove(viewPanel);
        viewPanel.setBorder(null);

        fullscreenDevice = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        fullscreenFrame = new JFrame();
        fullscreenFrame.setUndecorated(true);
        fullscreenFrame.setBackground(Color.BLACK);
        fullscreenFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        fullscreenFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exitFullscreen();
            }
        });
        fullscreenFrame.setLayout(new BorderLayout());
        fullscreenFrame.add(viewPanel, BorderLayout.CENTER);

        fullscreenGlassPane = createFullscreenGlassPane();
        fullscreenFrame.setGlassPane(fullscreenGlassPane);
        fullscreenGlassPane.setVisible(true);

        fullscreenFrame.getRootPane().registerKeyboardAction(
                e -> exitFullscreen(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        fullscreenFrame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                refreshFullscreenLayout();
            }

            @Override
            public void componentShown(ComponentEvent e) {
                refreshFullscreenLayout();
            }
        });

        try {
            fullscreenDevice.setFullScreenWindow(fullscreenFrame);
        } catch (Exception ex) {
            Rectangle screenBounds = fullscreenDevice.getDefaultConfiguration().getBounds();
            fullscreenFrame.setBounds(screenBounds);
            fullscreenFrame.setVisible(true);
        }
        refreshFullscreenLayout();
        SwingUtilities.invokeLater(this::refreshFullscreenLayout);

        frame.setVisible(false);
        viewPanel.requestFocusInWindow();
        logPanel.append(AppLogger.Level.INFO, "进入独占全屏模式");
    }

    private JPanel createFullscreenGlassPane() {
        JPanel glass = new JPanel(new FlowLayout(FlowLayout.RIGHT, 20, 20)) {
            @Override
            public boolean contains(int x, int y) {
                for (java.awt.Component child : getComponents()) {
                    if (child.getBounds().contains(x, y)) {
                        return true;
                    }
                }
                return false;
            }
        };
        glass.setOpaque(false);
        glass.add(exitFullscreenButton);
        return glass;
    }

    private void refreshFullscreenLayout() {
        if (!fullscreen || fullscreenFrame == null) {
            return;
        }
        viewPanel.revalidate();
        viewPanel.repaint();
        if (fullscreenGlassPane != null) {
            fullscreenGlassPane.revalidate();
            fullscreenGlassPane.repaint();
        }
    }

    private void exitFullscreen() {
        if (!fullscreen) {
            return;
        }
        fullscreen = false;

        if (fullscreenDevice != null) {
            fullscreenDevice.setFullScreenWindow(null);
        }
        if (fullscreenFrame != null) {
            fullscreenFrame.getRootPane().unregisterKeyboardAction(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
            fullscreenFrame.remove(viewPanel);
            if (fullscreenGlassPane != null) {
                fullscreenGlassPane.remove(exitFullscreenButton);
                fullscreenGlassPane.setVisible(false);
                fullscreenFrame.setGlassPane(new JPanel());
            }
            fullscreenFrame.dispose();
            fullscreenFrame = null;
        }
        fullscreenGlassPane = null;
        fullscreenDevice = null;

        viewPanel.setBorder(viewPanelBorder);
        viewCard.add(viewPanel, BorderLayout.CENTER);

        frame.setVisible(true);
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
                String password = roomPasswordField.getText().trim();
                int relayPort = parsePort(relayPortField.getText().trim(), RelayConnector.DEFAULT_RELAY_PORT);
                if (relayHost.isEmpty()) {
                    String msg = "请输入中继地址";
                    statusLabel.setText("状态: " + msg);
                    logPanel.append(AppLogger.Level.ERROR, msg);
                    return;
                }
                RelayConnector.validateRoomId(roomId);
                RelayAuth.validatePassword(password);
                logPanel.append(AppLogger.Level.INFO,
                        "用户发起连接：公网中继 " + relayHost + ":" + relayPort + "，房间 " + roomId);
                client.connectViaRelay(relayHost, relayPort, roomId, password);
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
        roomPasswordField.setEnabled(enabled);
        connectButton.setEnabled(enabled);
        disconnectButton.setEnabled(!enabled);
        qualityBox.setEnabled(enabled);
        fullscreenButton.setEnabled(!enabled);
    }
}
