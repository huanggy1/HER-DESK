package com.herdesk.server;

import com.herdesk.common.NetworkAddressUtil;
import com.herdesk.common.Protocol;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
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
    private JTextField portField;
    private JLabel ipLabel;
    private JLabel statusLabel;
    private JLabel clientLabel;
    private JLabel fpsLabel;
    private JButton startButton;
    private JButton stopButton;
    private RemoteDesktopServer server;
    private Timer fpsTimer;

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

        JPanel portPanel = new JPanel(new BorderLayout(8, 0));
        portPanel.add(new JLabel("监听端口:"), BorderLayout.WEST);
        portField = new JTextField(String.valueOf(Protocol.DEFAULT_PORT), 8);
        portPanel.add(portField, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        startButton = new JButton("启动服务");
        stopButton = new JButton("停止服务");
        stopButton.setEnabled(false);
        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
        buttonPanel.add(startButton);
        buttonPanel.add(stopButton);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(infoPanel, BorderLayout.CENTER);
        center.add(portPanel, BorderLayout.SOUTH);

        root.add(center, BorderLayout.CENTER);
        root.add(buttonPanel, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        fpsTimer = new Timer(1000, e -> updateFps());
        fpsTimer.start();
    }

    private void startServer() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("端口范围 1-65535");
            }
        } catch (NumberFormatException e) {
            statusLabel.setText("状态: 端口无效");
            return;
        }

        if (server != null && server.isRunning()) {
            return;
        }

        portField.setEnabled(false);
        startButton.setEnabled(false);
        stopButton.setEnabled(true);

        server = new RemoteDesktopServer(port, new RemoteDesktopServer.ServerListener() {
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
        });
        server.start();
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        portField.setEnabled(true);
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        clientLabel.setText("客户端: 无");
        statusLabel.setText("状态: 未启动");
        fpsLabel.setText("帧率: -");
    }

    private void updateFps() {
        if (server != null && server.isClientConnected()) {
            fpsLabel.setText("帧率: " + server.getCurrentFps() + " FPS");
        } else {
            fpsLabel.setText("帧率: -");
        }
    }
}
