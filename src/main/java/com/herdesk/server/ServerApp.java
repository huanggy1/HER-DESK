package com.herdesk.server;

import com.herdesk.common.AppLogger;
import com.herdesk.common.ConnectionMode;
import com.herdesk.common.LogPanel;
import com.herdesk.common.NetworkAddressUtil;
import com.herdesk.common.Protocol;
import com.herdesk.common.RelayAuth;
import com.herdesk.common.RelayConnector;
import com.herdesk.common.RoomIdGenerator;
import com.herdesk.common.UiTheme;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 被控端 Swing 图形界面。
 * <p>
 * 提供连接模式选择、服务启停、运行状态展示与日志面板。
 */
public class ServerApp {

    // ---- 窗口 ----
    private JFrame frame;

    // ---- 连接设置 ----
    /** 直连 / 中继模式切换。 */
    private JComboBox<ConnectionMode> modeBox;
    /** 模式对应参数面板容器（CardLayout）。 */
    private JPanel modeCardPanel;
    /** 直连监听端口。 */
    private JTextField portField;
    /** 中继服务器地址。 */
    private JTextField relayHostField;
    /** 中继服务器端口。 */
    private JTextField relayPortField;
    /** 中继房间号。 */
    private JTextField roomIdField;
    /** 中继房间密码。 */
    private JTextField roomPasswordField;
    /** 重新生成房间号按钮。 */
    private JButton regenerateRoomButton;

    // ---- 运行状态 ----
    /** 本机 IP 或中继模式提示。 */
    private JLabel ipLabel;
    /** 服务状态文案。 */
    private JLabel statusLabel;
    /** 当前控制端地址。 */
    private JLabel clientLabel;
    /** 实时发送帧率。 */
    private JLabel fpsLabel;

    // ---- 服务控制 ----
    private JButton startButton;
    private JButton stopButton;
    /** 被控端核心服务实例。 */
    private RemoteDesktopServer server;
    /** 每秒刷新 FPS 显示的定时器。 */
    private Timer fpsTimer;

    // ---- 日志 ----
    private LogPanel logPanel;

    /** 在 EDT 上创建并显示主窗口。 */
    public void start() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                createAndShowUi();
            }
        });
    }

    /** 构建 UI 布局、绑定事件并启动 FPS 定时器。 */
    private void createAndShowUi() {
        UiTheme.install();
        frame = new JFrame("Her Desk - 被控端");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        UiTheme.styleFrame(frame);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopServer();
                frame.dispose();
            }
        });

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(UiTheme.BACKGROUND);
        root.add(UiTheme.createHeader("Her Desk 被控端", "共享本机桌面，等待控制端连接"), BorderLayout.NORTH);

        JPanel body = UiTheme.createRootPanel();
        body.setLayout(new BorderLayout(0, 10));

        JPanel infoCard = UiTheme.createCard("运行状态");
        infoCard.setLayout(new GridLayout(4, 1, 4, 8));
        ipLabel = new JLabel("本机 IP: " + NetworkAddressUtil.getPrimaryLocalIpv4());
        UiTheme.styleTitleLabel(ipLabel);
        statusLabel = new JLabel("状态: 未启动");
        UiTheme.styleStatusLabel(statusLabel);
        clientLabel = new JLabel("客户端: 无");
        UiTheme.styleStatusLabel(clientLabel);
        fpsLabel = new JLabel("帧率: -");
        UiTheme.styleStatusLabel(fpsLabel);
        infoCard.add(ipLabel);
        infoCard.add(statusLabel);
        infoCard.add(clientLabel);
        infoCard.add(fpsLabel);

        JPanel modeCard = UiTheme.createCard("连接设置");
        modeCard.setLayout(new BorderLayout(8, 10));
        JPanel modeSelectPanel = new JPanel(new BorderLayout(10, 0));
        modeSelectPanel.setOpaque(false);
        JLabel modeLabel = new JLabel("连接模式");
        UiTheme.styleLabel(modeLabel);
        modeSelectPanel.add(modeLabel, BorderLayout.WEST);
        modeBox = new JComboBox<ConnectionMode>(ConnectionMode.values());
        UiTheme.styleComboBox(modeBox);
        modeBox.addActionListener(e -> switchModePanel());
        modeSelectPanel.add(modeBox, BorderLayout.CENTER);
        modeCard.add(modeSelectPanel, BorderLayout.NORTH);

        modeCardPanel = new JPanel(new CardLayout());
        modeCardPanel.setOpaque(false);
        modeCardPanel.add(createDirectPanel(), ConnectionMode.DIRECT.name());
        modeCardPanel.add(createRelayPanel(), ConnectionMode.RELAY.name());
        modeCard.add(modeCardPanel, BorderLayout.CENTER);

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        center.add(infoCard, BorderLayout.NORTH);
        center.add(modeCard, BorderLayout.CENTER);
        logPanel = new LogPanel(8);
        center.add(logPanel, BorderLayout.SOUTH);

        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 12, 0));
        buttonPanel.setOpaque(false);
        startButton = UiTheme.createPrimaryButton("启动服务");
        stopButton = UiTheme.createDangerButton("停止服务");
        stopButton.setEnabled(false);
        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
        buttonPanel.add(startButton);
        buttonPanel.add(stopButton);

        body.add(center, BorderLayout.CENTER);
        body.add(buttonPanel, BorderLayout.SOUTH);
        root.add(body, BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.setMinimumSize(new java.awt.Dimension(540, 560));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        switchModePanel();
        fpsTimer = new Timer(1000, e -> updateFps());
        fpsTimer.start();
        logPanel.append(AppLogger.Level.INFO, "被控端已就绪");
    }

    private JPanel createDirectPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setOpaque(false);
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 0, 0, 0));
        JLabel portLabel = new JLabel("监听端口");
        UiTheme.styleLabel(portLabel);
        panel.add(portLabel, BorderLayout.WEST);
        portField = new JTextField(String.valueOf(Protocol.DEFAULT_PORT), 8);
        UiTheme.styleNetworkField(portField);
        panel.add(portField, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createRelayPanel() {
        JPanel panel = new JPanel(new GridLayout(4, 2, 10, 8));
        panel.setOpaque(false);
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 0, 0, 0));
        JLabel l1 = new JLabel("中继地址");
        UiTheme.styleLabel(l1);
        panel.add(l1);
        relayHostField = new JTextField("198.176.62.33", 18);
        UiTheme.styleNetworkField(relayHostField);
        panel.add(relayHostField);
        JLabel l2 = new JLabel("中继端口");
        UiTheme.styleLabel(l2);
        panel.add(l2);
        relayPortField = new JTextField("11111", 8);
        UiTheme.styleNetworkField(relayPortField);
        panel.add(relayPortField);
        JLabel l3 = new JLabel("房间号");
        UiTheme.styleLabel(l3);
        panel.add(l3);
        JPanel roomRow = new JPanel(new BorderLayout(8, 0));
        roomRow.setOpaque(false);
        roomIdField = new JTextField(12);
        UiTheme.styleNetworkField(roomIdField);
        roomRow.add(roomIdField, BorderLayout.CENTER);
        regenerateRoomButton = UiTheme.createSecondaryButton("重新生成");
        regenerateRoomButton.addActionListener(e -> regenerateRoomId());
        roomRow.add(regenerateRoomButton, BorderLayout.EAST);
        panel.add(roomRow);
        JLabel l4 = new JLabel("房间密码");
        UiTheme.styleLabel(l4);
        panel.add(l4);
        roomPasswordField = new JTextField(RelayAuth.DEFAULT_PASSWORD, 12);
        UiTheme.styleNetworkField(roomPasswordField);
        panel.add(roomPasswordField);
        regenerateRoomId();
        return panel;
    }

    /** 随机生成 6 位房间号并写入输入框。 */
    private void regenerateRoomId() {
        if (roomIdField != null) {
            roomIdField.setText(RoomIdGenerator.generate());
            if (logPanel != null) {
                logPanel.append(AppLogger.Level.INFO, "已生成新房间号: " + roomIdField.getText());
            }
        }
    }

    /** 根据当前连接模式切换参数面板与 IP 提示文案。 */
    private void switchModePanel() {
        ConnectionMode mode = (ConnectionMode) modeBox.getSelectedItem();
        CardLayout layout = (CardLayout) modeCardPanel.getLayout();
        layout.show(modeCardPanel, mode.name());
        if (mode == ConnectionMode.RELAY) {
            ipLabel.setText("中继模式：控制端通过公网中继连接");
            if (roomIdField != null && roomIdField.getText().trim().isEmpty()) {
                regenerateRoomId();
            }
        } else {
            ipLabel.setText("本机 IP: " + NetworkAddressUtil.getPrimaryLocalIpv4());
        }
    }

    /**
     * 校验参数并启动 {@link RemoteDesktopServer}（直连或中继）。
     */
    private void startServer() {
        if (server != null && server.isRunning()) {
            return;
        }

        ConnectionMode mode = (ConnectionMode) modeBox.getSelectedItem();
        RemoteDesktopServer.ServerListener listener = createListener();

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
                        "用户启动服务：公网中继 " + relayHost + ":" + relayPort
                                + "，房间 " + roomId);
                server = new RemoteDesktopServer(relayHost, relayPort, roomId, password, listener);
            } else {
                int port = parsePort(portField.getText().trim(), Protocol.DEFAULT_PORT);
                logPanel.append(AppLogger.Level.INFO, "用户启动服务：内网直连，监听端口 " + port);
                server = new RemoteDesktopServer(port, listener);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            statusLabel.setText("状态: " + msg);
            logPanel.append(AppLogger.Level.ERROR, "启动参数错误：" + msg);
            return;
        }

        setInputsEnabled(false);
        server.start();
    }

    /** 创建服务层回调，将事件派发到 Swing EDT 更新 UI。 */
    private RemoteDesktopServer.ServerListener createListener() {
        return new RemoteDesktopServer.ServerListener() {
            @Override
            public void onStatusChanged(String status) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("状态: " + status));
            }

            @Override
            public void onClientConnected(String remoteAddress) {
                SwingUtilities.invokeLater(() -> clientLabel.setText("客户端: " + remoteAddress));
            }

            @Override
            public void onClientDisconnected(String reason) {
                SwingUtilities.invokeLater(() -> {
                    clientLabel.setText("客户端: 无");
                    statusLabel.setText("状态: " + reason);
                });
            }

            @Override
            public void onError(String message) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("状态: " + message));
            }

            @Override
            public void onLog(AppLogger.Level level, String message) {
                logPanel.append(level, message);
            }
        };
    }

    private int parsePort(String text, int defaultValue) {
        if (text == null || text.isEmpty()) {
            return defaultValue;
        }
        int port = Integer.parseInt(text);
        if (port < 1 || port > 65535) {
            throw new NumberFormatException("端口范围 1-65535");
        }
        return port;
    }

    /** 停止服务并恢复输入控件与状态标签。 */
    private void stopServer() {
        if (server != null) {
            logPanel.append(AppLogger.Level.INFO, "用户点击停止服务");
            server.stop();
            server = null;
        }
        setInputsEnabled(true);
        clientLabel.setText("客户端: 无");
        statusLabel.setText("状态: 未启动");
        fpsLabel.setText("帧率: -");
        logPanel.append(AppLogger.Level.INFO, "服务已停止");
        switchModePanel();
    }

    /** 服务运行期间禁用连接参数与启动按钮。 */
    private void setInputsEnabled(boolean enabled) {
        modeBox.setEnabled(enabled);
        portField.setEnabled(enabled);
        relayHostField.setEnabled(enabled);
        relayPortField.setEnabled(enabled);
        roomIdField.setEnabled(enabled);
        roomPasswordField.setEnabled(enabled);
        if (regenerateRoomButton != null) {
            regenerateRoomButton.setEnabled(enabled);
        }
        startButton.setEnabled(enabled);
        stopButton.setEnabled(!enabled);
    }

    /** 每秒从服务层拉取 FPS 并刷新标签。 */
    private void updateFps() {
        if (server != null && server.isClientConnected()) {
            fpsLabel.setText("帧率: " + server.getCurrentFps() + " FPS");
        } else {
            fpsLabel.setText("帧率: -");
        }
    }
}
