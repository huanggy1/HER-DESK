package com.herdesk.common;

import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * 可滚动的日志面板。
 */
public class LogPanel extends JPanel {

    private static final int MAX_LINES = 500;

    private final JTextArea textArea;

    public LogPanel(int rows) {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(UiTheme.CARD);
        textArea = new JTextArea(rows, 0);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        UiTheme.styleTextArea(textArea);
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(BorderFactory.createLineBorder(UiTheme.BORDER, 1, true));
        scrollPane.getViewport().setBackground(UiTheme.LOG_BG);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(UiTheme.BORDER, 1, true),
                        "日志",
                        javax.swing.border.TitledBorder.LEFT,
                        javax.swing.border.TitledBorder.TOP,
                        UiTheme.FONT_LABEL,
                        UiTheme.TITLE_TEXT
                ),
                BorderFactory.createEmptyBorder(4, 8, 8, 8)
        ));
        add(scrollPane, BorderLayout.CENTER);
    }

    public void append(AppLogger.Level level, String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        final String line = AppLogger.formatLine(level, message);
        if (SwingUtilities.isEventDispatchThread()) {
            appendOnEdt(line);
        } else {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    appendOnEdt(line);
                }
            });
        }
    }

    private void appendOnEdt(String line) {
        textArea.append(line + "\n");
        trimLines();
        textArea.setCaretPosition(textArea.getDocument().getLength());
    }

    private void trimLines() {
        int lineCount = textArea.getLineCount();
        if (lineCount <= MAX_LINES) {
            return;
        }
        try {
            int startOffset = textArea.getLineStartOffset(lineCount - MAX_LINES);
            textArea.replaceRange("", 0, startOffset);
        } catch (Exception ignored) {
            textArea.setText("");
        }
    }
}
