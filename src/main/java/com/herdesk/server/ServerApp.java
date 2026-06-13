package com.herdesk.server;

import com.herdesk.common.AppLogger;
import com.herdesk.common.ConnectionMode;
import com.herdesk.common.LogPanel;
import com.herdesk.common.NetworkAddressUtil;
import com.herdesk.common.Protocol;
import com.herdesk.common.RelayConnector;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Font;
import java.awt.GridLayout;
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
import javax.swing.Timer;

/**
 * 被控端 Swing 界面。
 */
public class ServerApp {

    private JFrame frame;
    private JComboBox<ConnectionMode> modeBox;
    private JPanel modeCardPanel;
    private JTextField portField;
    private JTextField relayHostField;
    private JTextField relayPortField;
    private JTextField roomIdField;
    private JLabel ipLabel;
    private JLabel statusLabel;
    private JLabel clientLabel;
    private JLabel fpsLabel;
    private JButton startButton;
    private JButton stopButton;
    private RemoteDesktopServer server;
    private Timer fpsTimer;
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
        frame = new JFrame("Her Desk - 被控端");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopServer();
                frame.dispose();
            }
        });

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel infoPanel = new JPanel(new GridLayout(4, 1, 4, 4));
        ipLabel = new JLabel("本机 IP: " + NetworkAddressUtil.getPrimaryLocalIpv4());
        statusLabel = new JLabel("状态: 未启动");
        clientLabel = new JLabel("客户端: 无");
        fpsLabel = new JLabel("帧率: -");
        Font bold = ipLabel.getFont().deriveFont(Font.BOLD, 14f);
        ipLabel.setFont(bold);
        infoPanel.add(ipLabel);
        infoPanel.add(statusLabel);
        infoPanel.add(clientLabel);
        infoPanel.add(fpsLabel);

        JPanel modePanel = new JPanel(new BorderLayout(8, 8));
        JPanel modeSelectPanel = new JPanel(new BorderLayout(8, 0));
        modeSelectPanel.add(new JLabel("连接模式:"), BorderLayout.WEST);
        modeBox = new JComboBox<ConnectionMode>(ConnectionMode.values());
        modeBox.addActionListener(e -> switchModePanel());
        modeSelectPanel.add(modeBox, BorderLayout.CENTER);
        modePanel.add(modeSelectPanel, BorderLayout.NORTH);

        modeCardPanel = new JPanel(new CardLayout());
        modeCardPanel.add(createDirectPanel(), ConnectionMode.DIRECT.name());
        modeCardPanel.add(createRelayPanel(), ConnectionMode.RELAY.name());
        modePanel.add(modeCardPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        startButton = new JButton("启动服务");
        stopButton = new JButton("停止服务");
        stopButton.setEnabled(false);
        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
        buttonPanel.add(startButton);
        buttonPanel.add(stopButton);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(infoPanel, BorderLayout.NORTH);
        center.add(modePanel, BorderLayout.CENTER);
        logPanel = new LogPanel(8);
        center.add(logPanel, BorderLayout.SOUTH);

        root.add(center, BorderLayout.CENTER);
        root.add(buttonPanel, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setMinimumSize(new java.awt.Dimension(520, 520));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        switchModePanel();
        fpsTimer = new Timer(1000, e -> updateFps());
        fpsTimer.start();
        logPanel.append(AppLogger.Level.INFO, "被控端已就绪");
    }

    private JPanel createDirectPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        panel.add(new JLabel("监听端口:"), BorderLayout.WEST);
        portField = new JTextField(String.valueOf(Protocol.DEFAULT_PORT), 8);
        panel.add(portField, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createRelayPanel() {
        JPanel panel = new JPanel(new GridLayout(3, 2, 8, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        panel.add(new JLabel("中继地址:"));
        relayHostField = new JTextField("your-server.com", 12);
        panel.add(relayHostField);
        panel.add(new JLabel("中继端口:"));
        relayPortField = new JTextField(String.valueOf(RelayConnector.DEFAULT_RELAY_PORT), 8);
        panel.add(relayPortField);
        panel.add(new JLabel("房间号:"));
        roomIdField = new JTextField("room001", 12);
        panel.add(roomIdField);
        return panel;
    }

    private void switchModePanel() {
        ConnectionMode mode = (ConnectionMode) modeBox.getSelectedItem();
        CardLayout layout = (CardLayout) modeCardPanel.getLayout();
        layout.show(modeCardPanel, mode.name());
        if (mode == ConnectionMode.RELAY) {
            ipLabel.setText("中继模式：控制端通过公网中继连接");
        } else {
            ipLabel.setText("本机 IP: " + NetworkAddressUtil.getPrimaryLocalIpv4());
        }
    }

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
                int relayPort = parsePort(relayPortField.getText().trim(), RelayConnector.DEFAULT_RELAY_PORT);
                if (relayHost.isEmpty()) {
                    String msg = "请输入中继地址";
                    statusLabel.setText("状态: " + msg);
                    logPanel.append(AppLogger.Level.ERROR, msg);
                    return;
                }
                RelayConnector.validateRoomId(roomId);
                logPanel.append(AppLogger.Level.INFO,
                        "用户启动服务：公网中继 " + relayHost + ":" + relayPort + "，房间 " + roomId);
                server = new RemoteDesktopServer(relayHost, relayPort, roomId, listener);
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

    private void setInputsEnabled(boolean enabled) {
        modeBox.setEnabled(enabled);
        portField.setEnabled(enabled);
        relayHostField.setEnabled(enabled);
        relayPortField.setEnabled(enabled);
        roomIdField.setEnabled(enabled);
        startButton.setEnabled(enabled);
        stopButton.setEnabled(!enabled);
    }

    private void updateFps() {
        if (server != null && server.isClientConnected()) {
            fpsLabel.setText("帧率: " + server.getCurrentFps() + " FPS");
        } else {
            fpsLabel.setText("帧率: -");
        }
    }
}
