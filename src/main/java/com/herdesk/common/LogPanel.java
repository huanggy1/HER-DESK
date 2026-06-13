package com.herdesk.common;

import java.awt.BorderLayout;
import java.awt.Font;
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
        setBorder(BorderFactory.createTitledBorder("日志"));
        textArea = new JTextArea(rows, 0);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, textArea.getFont().getSize());
        textArea.setFont(mono);
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
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
