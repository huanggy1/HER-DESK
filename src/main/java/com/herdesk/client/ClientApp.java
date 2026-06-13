package com.herdesk.client;

import com.herdesk.common.Protocol;
import com.herdesk.common.QualityLevel;
import com.herdesk.common.ScreenGeometry;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
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
    private JTextField hostField;
    private JTextField portField;
    private JButton connectButton;
    private JButton disconnectButton;
    private JLabel statusLabel;
    private JLabel fpsLabel;
    private JComboBox<QualityLevel> qualityBox;
    private RemoteViewPanel viewPanel;
    private RemoteDesktopClient client;
    private FrameDisplayScheduler frameScheduler;

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
                disconnect();
                frame.dispose();
            }
        });

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        topPanel.add(new JLabel("被控端 IP:"));
        hostField = new JTextField("192.168.1.100", 14);
        topPanel.add(hostField);
        topPanel.add(new JLabel("端口:"));
        portField = new JTextField(String.valueOf(Protocol.DEFAULT_PORT), 6);
        topPanel.add(portField);
        connectButton = new JButton("连接");
        disconnectButton = new JButton("断开");
        disconnectButton.setEnabled(false);
        connectButton.addActionListener(e -> connect());
        disconnectButton.addActionListener(e -> disconnect());
        topPanel.add(connectButton);
        topPanel.add(disconnectButton);
        topPanel.add(new JLabel("画质:"));
        qualityBox = new JComboBox<QualityLevel>(QualityLevel.values());
        qualityBox.setSelectedItem(QualityLevel.BALANCED);
        qualityBox.addActionListener(e -> onQualityChanged());
        topPanel.add(qualityBox);

        viewPanel = new RemoteViewPanel();
        frameScheduler = new FrameDisplayScheduler(image -> viewPanel.updateFrame(image));
        bindInputListeners();

        JPanel bottomPanel = new JPanel(new BorderLayout());
        statusLabel = new JLabel("状态: 未连接");
        fpsLabel = new JLabel("帧率: -");
        bottomPanel.add(statusLabel, BorderLayout.WEST);
        bottomPanel.add(fpsLabel, BorderLayout.EAST);

        root.add(topPanel, BorderLayout.NORTH);
        root.add(viewPanel, BorderLayout.CENTER);
        root.add(bottomPanel, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setSize(1100, 720);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        client = new RemoteDesktopClient(new RemoteDesktopClient.ClientListener() {
            @Override
            public void onStatusChanged(String status) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("状态: " + status));
            }

            @Override
            public void onConnected(ScreenGeometry geometry) {
                SwingUtilities.invokeLater(() -> {
                    viewPanel.setRemoteScreenGeometry(geometry);
                    connectButton.setEnabled(false);
                    disconnectButton.setEnabled(true);
                    hostField.setEnabled(false);
                    portField.setEnabled(false);
                    viewPanel.requestFocusInWindow();
                });
            }

            @Override
            public void onDisconnected(String reason) {
                SwingUtilities.invokeLater(() -> {
                    frameScheduler.reset();
                    viewPanel.clearFrame();
                    connectButton.setEnabled(true);
                    disconnectButton.setEnabled(false);
                    hostField.setEnabled(true);
                    portField.setEnabled(true);
                    fpsLabel.setText("帧率: -");
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
                SwingUtilities.invokeLater(() -> fpsLabel.setText("帧率: " + (fps > 0 ? fps + " FPS" : "-")));
            }
        });
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
                sendKey(e, true);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                sendKey(e, false);
            }
        });
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
        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            statusLabel.setText("状态: 端口无效");
            return;
        }
        if (host.isEmpty()) {
            statusLabel.setText("状态: 请输入 IP");
            return;
        }
        client.connect(host, port);
    }

    private void disconnect() {
        if (client != null) {
            client.disconnect();
        }
    }

    private void onQualityChanged() {
        if (client != null) {
            QualityLevel level = (QualityLevel) qualityBox.getSelectedItem();
            client.setQuality(level);
        }
    }
}
