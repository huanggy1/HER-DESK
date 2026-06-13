package com.herdesk.client;

import com.herdesk.common.ScreenGeometry;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;

/**
 * 远程桌面画面展示面板，支持缩放自适应。
 */
public class RemoteViewPanel extends JPanel {

    private BufferedImage frameImage;
    private int remoteWidth;
    private int remoteHeight;
    private int drawX;
    private int drawY;
    private int drawWidth;
    private int drawHeight;

    public RemoteViewPanel() {
        setFocusable(true);
        setBackground(java.awt.Color.BLACK);
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
            g.setColor(java.awt.Color.DARK_GRAY);
            g.drawString("等待连接...", 20, 30);
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
