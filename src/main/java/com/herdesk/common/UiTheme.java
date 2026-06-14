package com.herdesk.common;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicButtonUI;

/**
 * Her Desk 浅蓝浅色 UI 主题：全局颜色、字体与常用 Swing 组件样式工厂。
 */
public final class UiTheme {

    // --- 背景与卡片 ---
    public static final Color BACKGROUND = color(0xF0F6FC);
    public static final Color CARD = Color.WHITE;
    public static final Color HEADER_BG = color(0xD6E8F7);

    // --- 主色与边框 ---
    public static final Color PRIMARY = color(0x5B9BD5);
    public static final Color PRIMARY_DARK = color(0x3A7FBF);
    public static final Color PRIMARY_LIGHT = color(0xE8F2FA);
    public static final Color BORDER = color(0xC5DCF0);

    // --- 文字色 ---
    public static final Color TITLE_TEXT = color(0x1E3A5F);
    public static final Color BODY_TEXT = color(0x4A6078);
    public static final Color MUTED_TEXT = color(0x7A92A8);

    // --- 功能色 ---
    public static final Color LOG_BG = color(0xF8FBFE);
    public static final Color DANGER = color(0xD9534F);
    public static final Color DANGER_LIGHT = color(0xFDECEA);

    // --- 字体 ---
    public static final Font FONT_TITLE = deriveFont(Font.BOLD, 18f);
    public static final Font FONT_SUBTITLE = deriveFont(Font.PLAIN, 13f);
    public static final Font FONT_BODY = deriveFont(Font.PLAIN, 13f);
    public static final Font FONT_LABEL = deriveFont(Font.PLAIN, 13f);
    public static final Font FONT_BUTTON = deriveFont(Font.BOLD, 13f);
    public static final Font FONT_STATUS = deriveFont(Font.PLAIN, 12f);
    /** 日志与网络参数等宽字体。 */
    public static final Font FONT_MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    /** 组件内边距：小 / 中 / 大。 */
    private static final EmptyBorder PADDING_SM = new EmptyBorder(8, 12, 8, 12);
    private static final EmptyBorder PADDING_MD = new EmptyBorder(12, 16, 12, 16);
    private static final EmptyBorder PADDING_LG = new EmptyBorder(16, 20, 16, 20);

    private UiTheme() {
    }

    /** 将主题颜色与字体写入 UIManager，供全局 Swing 组件继承。 */
    public static void install() {
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Label.foreground", BODY_TEXT);
        UIManager.put("Label.font", FONT_LABEL);
        UIManager.put("Button.font", FONT_BUTTON);
        UIManager.put("TextField.font", FONT_BODY);
        UIManager.put("ComboBox.font", FONT_BODY);
        UIManager.put("TextArea.font", FONT_MONO);
        UIManager.put("TitledBorder.font", FONT_LABEL);
        UIManager.put("TitledBorder.titleColor", TITLE_TEXT);
    }

    public static void styleFrame(JFrame frame) {
        frame.getContentPane().setBackground(BACKGROUND);
    }

    public static JPanel createRootPanel() {
        JPanel panel = new JPanel();
        panel.setBackground(BACKGROUND);
        panel.setBorder(PADDING_MD);
        return panel;
    }

    public static JPanel createHeader(String title, String subtitle) {
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setOpaque(true);
        header.setBackground(HEADER_BG);
        header.setBorder(new CompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                new EmptyBorder(14, 18, 14, 18)
        ));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FONT_TITLE);
        titleLabel.setForeground(TITLE_TEXT);
        header.add(titleLabel, BorderLayout.NORTH);
        if (subtitle != null && !subtitle.isEmpty()) {
            JLabel subLabel = new JLabel(subtitle);
            subLabel.setFont(FONT_SUBTITLE);
            subLabel.setForeground(MUTED_TEXT);
            header.add(subLabel, BorderLayout.CENTER);
        }
        return header;
    }

    public static JPanel createCard(String title) {
        JPanel card = new JPanel();
        card.setOpaque(true);
        card.setBackground(CARD);
        Border inner = new EmptyBorder(12, 14, 12, 14);
        if (title != null && !title.isEmpty()) {
            TitledBorder titled = BorderFactory.createTitledBorder(
                    new LineBorder(BORDER, 1, true),
                    title,
                    TitledBorder.LEFT,
                    TitledBorder.TOP,
                    FONT_LABEL,
                    TITLE_TEXT
            );
            card.setBorder(new CompoundBorder(titled, inner));
        } else {
            card.setBorder(new CompoundBorder(new LineBorder(BORDER, 1, true), inner));
        }
        return card;
    }

    public static JPanel createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setOpaque(true);
        bar.setBackground(PRIMARY_LIGHT);
        bar.setBorder(new CompoundBorder(
                new LineBorder(BORDER, 1, true),
                new EmptyBorder(8, 12, 8, 12)
        ));
        return bar;
    }

    public static void styleLabel(JLabel label) {
        label.setFont(FONT_LABEL);
        label.setForeground(BODY_TEXT);
    }

    public static void styleTitleLabel(JLabel label) {
        label.setFont(FONT_TITLE.deriveFont(14f));
        label.setForeground(TITLE_TEXT);
    }

    public static void styleStatusLabel(JLabel label) {
        label.setFont(FONT_STATUS);
        label.setForeground(BODY_TEXT);
    }

    public static void styleMutedLabel(JLabel label) {
        label.setFont(FONT_STATUS);
        label.setForeground(MUTED_TEXT);
    }

    public static void styleTextField(JTextField field) {
        field.setFont(FONT_BODY);
        field.setForeground(TITLE_TEXT);
        field.setBackground(Color.WHITE);
        field.setCaretColor(PRIMARY_DARK);
        field.setBorder(new CompoundBorder(
                new LineBorder(BORDER, 1, true),
                new EmptyBorder(6, 10, 6, 10)
        ));
    }

    /** IP、端口、房间号等网络参数输入框：等宽字体，圆点显示更清晰。 */
    public static void styleNetworkField(JTextField field) {
        field.setFont(FONT_MONO);
        field.setForeground(TITLE_TEXT);
        field.setBackground(Color.WHITE);
        field.setCaretColor(PRIMARY_DARK);
        field.setBorder(new CompoundBorder(
                new LineBorder(BORDER, 1, true),
                new EmptyBorder(6, 10, 6, 10)
        ));
    }

    public static void styleComboBox(JComboBox<?> comboBox) {
        comboBox.setFont(FONT_BODY);
        comboBox.setForeground(TITLE_TEXT);
        comboBox.setBackground(Color.WHITE);
        comboBox.setBorder(new CompoundBorder(
                new LineBorder(BORDER, 1, true),
                new EmptyBorder(4, 8, 4, 8)
        ));
    }

    public static void styleTextArea(JTextArea area) {
        area.setFont(FONT_MONO);
        area.setForeground(TITLE_TEXT);
        area.setBackground(LOG_BG);
        area.setCaretColor(PRIMARY_DARK);
        area.setBorder(new EmptyBorder(8, 10, 8, 10));
    }

    public static JButton createPrimaryButton(String text) {
        JButton button = new JButton(text);
        stylePrimaryButton(button);
        return button;
    }

    public static JButton createSecondaryButton(String text) {
        JButton button = new JButton(text);
        styleSecondaryButton(button);
        return button;
    }

    public static JButton createDangerButton(String text) {
        JButton button = new JButton(text);
        styleDangerButton(button);
        return button;
    }

    public static void stylePrimaryButton(JButton button) {
        applyButtonStyle(button, PRIMARY, Color.WHITE, PRIMARY_DARK, true);
    }

    public static void styleSecondaryButton(JButton button) {
        applyButtonStyle(button, PRIMARY_LIGHT, PRIMARY_DARK, BORDER, true);
    }

    public static void styleDangerButton(JButton button) {
        applyButtonStyle(button, DANGER_LIGHT, DANGER, color(0xF5C6C4), true);
    }

    public static void stylePanelTransparent(JPanel panel) {
        panel.setOpaque(false);
    }

    public static void makeOpaque(Component component, Color background) {
        if (component instanceof JComponent) {
            JComponent jc = (JComponent) component;
            jc.setOpaque(true);
            jc.setBackground(background);
        }
    }

    private static void applyButtonStyle(JButton button, Color bg, Color fg, Color borderColor, boolean bold) {
        button.setFont(bold ? FONT_BUTTON : FONT_BODY);
        button.setForeground(fg);
        button.setBackground(bg);
        button.setOpaque(true);
        button.setBorderPainted(true);
        button.setFocusPainted(false);
        button.setContentAreaFilled(true);
        button.setUI(new BasicButtonUI());
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(new CompoundBorder(
                new LineBorder(borderColor, 1, true),
                new EmptyBorder(8, 18, 8, 18)
        ));
        button.setPreferredSize(new Dimension(Math.max(96, button.getPreferredSize().width), 36));
    }

    private static Color color(int rgb) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static Font deriveFont(int style, float size) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        }
        return base.deriveFont(style, size);
    }
}
