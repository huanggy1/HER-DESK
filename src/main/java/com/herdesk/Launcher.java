package com.herdesk;

import com.herdesk.client.ClientApp;
import com.herdesk.common.UiTheme;
import com.herdesk.server.ServerApp;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * 统一启动入口：一个 JAR 同时支持被控端与控制端。
 * <p>
 * 用法：
 * - 双击或 java -jar her-desk.jar          → 弹出模式选择
 * - java -jar her-desk.jar server           → 直接启动被控端
 * - java -jar her-desk.jar client           → 直接启动控制端
 */
public class Launcher {

    public static void main(String[] args) {
        UiTheme.install();
        if (args != null && args.length > 0) {
            String mode = args[0].trim().toLowerCase();
            if ("server".equals(mode)) {
                new ServerApp().start();
                return;
            }
            if ("client".equals(mode)) {
                new ClientApp().start();
                return;
            }
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                showModeChooser();
            }
        });
    }

    /** 无命令行参数时弹出模式选择对话框。 */
    private static void showModeChooser() {
        JFrame frame = new JFrame("Her Desk");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        UiTheme.styleFrame(frame);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(UiTheme.BACKGROUND);
        root.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 16, 0));
        root.add(UiTheme.createHeader("Her Desk", "内网远程桌面工具"), BorderLayout.NORTH);

        JPanel content = UiTheme.createCard("请选择运行模式");
        content.setLayout(new BorderLayout(0, 16));
        JLabel hint = new JLabel("选择本机作为被控端或控制端：", SwingConstants.CENTER);
        UiTheme.styleLabel(hint);
        content.add(hint, BorderLayout.NORTH);

        JButton serverButton = UiTheme.createPrimaryButton("被控端（Server）");
        JButton clientButton = UiTheme.createSecondaryButton("控制端（Client）");
        serverButton.addActionListener(e -> {
            frame.dispose();
            new ServerApp().start();
        });
        clientButton.addActionListener(e -> {
            frame.dispose();
            new ClientApp().start();
        });

        JPanel buttons = new JPanel(new GridLayout(1, 2, 12, 0));
        buttons.setOpaque(false);
        buttons.add(serverButton);
        buttons.add(clientButton);
        content.add(buttons, BorderLayout.CENTER);

        JPanel wrap = UiTheme.createRootPanel();
        wrap.setLayout(new BorderLayout());
        wrap.add(content, BorderLayout.CENTER);
        root.add(wrap, BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.setSize(480, 280);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
