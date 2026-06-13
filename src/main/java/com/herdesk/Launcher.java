package com.herdesk;

import com.herdesk.client.ClientApp;
import com.herdesk.server.ServerApp;
import java.awt.GridLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

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

    private static void showModeChooser() {
        JFrame frame = new JFrame("Her Desk - 模式选择");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel panel = new JPanel(new GridLayout(3, 1, 8, 8));
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        panel.add(new JLabel("请选择运行模式："));

        JButton serverButton = new JButton("被控端（Server）");
        JButton clientButton = new JButton("控制端（Client）");
        serverButton.addActionListener(e -> {
            frame.dispose();
            new ServerApp().start();
        });
        clientButton.addActionListener(e -> {
            frame.dispose();
            new ClientApp().start();
        });

        JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));
        buttons.add(serverButton);
        buttons.add(clientButton);
        panel.add(buttons);

        frame.setContentPane(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
