package com.herdesk.client;

import com.herdesk.common.ScreenGeometry;
import com.herdesk.common.UiTheme;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.JPanel;

/**
 * 远程桌面画面展示面板：等比缩放居中绘制，支持本地坐标到远程像素映射。
 */
public class RemoteViewPanel extends JPanel {

    /** 当前待绘制的远程帧图像。 */
    private BufferedImage frameImage;
    /** 远程屏幕像素尺寸（来自握手或帧尺寸）。 */
    private int remoteWidth;
    private int remoteHeight;
    /** 帧在面板内的实际绘制区域（等比缩放后居中）。 */
    private int drawX;
    private int drawY;
    private int drawWidth;
    private int drawHeight;

    public RemoteViewPanel() {
        setFocusable(true);
        setBackground(Color.BLACK);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiTheme.BORDER, 2, true),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)
        ));
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                recalculateDrawBounds();
                repaint();
            }
        });
    }

    public void setRemoteScreenGeometry(ScreenGeometry geometry) {
        if (geometry == null) {
            remoteWidth = 0;
            remoteHeight = 0;
        } else {
            remoteWidth = geometry.getPixelWidth();
            remoteHeight = geometry.getPixelHeight();
        }
        recalculateDrawBounds();
    }

    public void setRemoteScreenSize(int width, int height) {
        this.remoteWidth = width;
        this.remoteHeight = height;
        recalculateDrawBounds();
    }

    public synchronized void updateFrame(BufferedImage image) {
        this.frameImage = image;
        if (image != null) {
            remoteWidth = image.getWidth();
            remoteHeight = image.getHeight();
        }
        recalculateDrawBounds();
        repaint();
    }

    public synchronized void clearFrame() {
        frameImage = null;
        repaint();
    }

    /**
     * 容器切换或全屏布局后强制重算绘制区域。
     */
    public void refreshDisplayBounds() {
        recalculateDrawBounds();
        repaint();
    }

    public synchronized BufferedImage getDisplayImage() {
        return frameImage;
    }

    /**
     * 将另一块面板的画面与尺寸同步到本面板（用于进入全屏时复制当前帧）。
     */
    public void copyDisplayFrom(RemoteViewPanel source) {
        if (source == null) {
            return;
        }
        setRemoteScreenSize(source.getRemoteWidth(), source.getRemoteHeight());
        BufferedImage image = source.getDisplayImage();
        if (image != null) {
            updateFrame(image);
        } else {
            clearFrame();
        }
        refreshDisplayBounds();
    }

    public int getRemoteWidth() {
        return remoteWidth;
    }

    public int getRemoteHeight() {
        return remoteHeight;
    }

    /**
     * 将面板坐标转换为远程捕获图像像素坐标（相对图像左上角 0,0）。
     */
    public int[] translateToRemote(int panelX, int panelY) {
        if (remoteWidth <= 0 || remoteHeight <= 0 || drawWidth <= 0 || drawHeight <= 0) {
            return new int[]{0, 0};
        }
        int x = (int) Math.round((panelX - drawX) * (remoteWidth / (double) drawWidth));
        int y = (int) Math.round((panelY - drawY) * (remoteHeight / (double) drawHeight));
        x = clamp(x, 0, remoteWidth - 1);
        y = clamp(y, 0, remoteHeight - 1);
        return new int[]{x, y};
    }

    /**
     * 按面板与远程尺寸计算等比缩放后的绘制矩形，保持宽高比并居中。
     */
    private void recalculateDrawBounds() {
        int panelWidth = getWidth();
        int panelHeight = getHeight();
        if (panelWidth <= 0 || panelHeight <= 0 || remoteWidth <= 0 || remoteHeight <= 0) {
            drawX = 0;
            drawY = 0;
            drawWidth = 0;
            drawHeight = 0;
            return;
        }
        double scale = Math.min(
                panelWidth / (double) remoteWidth,
                panelHeight / (double) remoteHeight
        );
        drawWidth = (int) Math.round(remoteWidth * scale);
        drawHeight = (int) Math.round(remoteHeight * scale);
        drawX = (panelWidth - drawWidth) / 2;
        drawY = (panelHeight - drawHeight) / 2;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        BufferedImage image = frameImage;
        if (image == null || drawWidth <= 0 || drawHeight <= 0) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(new Color(0x2A3A4A));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(UiTheme.MUTED_TEXT);
                g2.setFont(UiTheme.FONT_BODY);
                String text = "等待连接...";
                int tw = g2.getFontMetrics().stringWidth(text);
                g2.drawString(text, Math.max(20, (getWidth() - tw) / 2), getHeight() / 2);
            } finally {
                g2.dispose();
            }
            return;
        }
        g.drawImage(image, drawX, drawY, drawWidth, drawHeight, this);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(1024, 640);
    }

    private int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
