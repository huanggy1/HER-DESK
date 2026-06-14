package com.herdesk.common;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

/**
 * 屏幕几何信息：多显示器虚拟桌面边界 + Retina 缩放因子。
 * <p>
 * 控制端发送「捕获图像像素坐标」，被控端通过本类转换为 Robot 可用的屏幕逻辑坐标。
 */
public final class ScreenGeometry {

    /** 虚拟桌面左上角 X（逻辑坐标） */
    private final int originX;
    /** 虚拟桌面左上角 Y（逻辑坐标） */
    private final int originY;
    /** 捕获区域逻辑宽度 */
    private final int logicalWidth;
    /** 捕获区域逻辑高度 */
    private final int logicalHeight;
    /** 捕获图像像素宽度 */
    private final int pixelWidth;
    /** 捕获图像像素高度 */
    private final int pixelHeight;
    /** 水平缩放比（pixel / logical） */
    private final double scaleX;
    /** 垂直缩放比（pixel / logical） */
    private final double scaleY;

    public ScreenGeometry(int originX, int originY,
                          int logicalWidth, int logicalHeight,
                          int pixelWidth, int pixelHeight,
                          double scaleX, double scaleY) {
        this.originX = originX;
        this.originY = originY;
        this.logicalWidth = logicalWidth;
        this.logicalHeight = logicalHeight;
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
        this.scaleX = scaleX <= 0 ? 1.0D : scaleX;
        this.scaleY = scaleY <= 0 ? 1.0D : scaleY;
    }

    public int getOriginX() {
        return originX;
    }

    public int getOriginY() {
        return originY;
    }

    public int getLogicalWidth() {
        return logicalWidth;
    }

    public int getLogicalHeight() {
        return logicalHeight;
    }

    public int getPixelWidth() {
        return pixelWidth;
    }

    public int getPixelHeight() {
        return pixelHeight;
    }

    public double getScaleX() {
        return scaleX;
    }

    public double getScaleY() {
        return scaleY;
    }

    /** 返回 Robot 截图用的逻辑边界矩形 */
    public Rectangle getCaptureBounds() {
        return new Rectangle(originX, originY, logicalWidth, logicalHeight);
    }

    /**
     * 探测当前虚拟桌面几何：合并所有显示器，并通过试截图确定 Retina 缩放比。
     * @deprecated 请使用 {@link ScreenCaptureHelper#detect(Robot)}
     */
    @Deprecated
    public static ScreenGeometry detect(Robot robot) {
        return ScreenCaptureHelper.detect(robot);
    }

    /**
     * 计算所有显示器的联合边界（虚拟桌面）。
     * <p>
     * 若合并结果无效，回退为 Toolkit 屏幕尺寸。
     */
    public static Rectangle computeVirtualBounds() {
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] devices = environment.getScreenDevices();
        Rectangle union = new Rectangle();
        for (GraphicsDevice device : devices) {
            GraphicsConfiguration config = device.getDefaultConfiguration();
            union = union.union(config.getBounds());
        }
        if (union.width <= 0 || union.height <= 0) {
            java.awt.Dimension size = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            union = new Rectangle(0, 0, size.width, size.height);
        }
        return union;
    }

    /**
     * 将捕获图像像素坐标转换为屏幕逻辑坐标（供 Robot 使用）。
     */
    public int[] toScreenCoords(int pixelX, int pixelY) {
        int screenX = originX + (int) Math.round(pixelX / scaleX);
        int screenY = originY + (int) Math.round(pixelY / scaleY);
        return new int[]{screenX, screenY};
    }
}
