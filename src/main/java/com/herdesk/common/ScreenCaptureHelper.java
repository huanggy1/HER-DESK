package com.herdesk.common;

import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * 跨平台屏幕截图：色彩归一化、Retina 缩放到逻辑分辨率、Mac 主屏捕获。
 */
public final class ScreenCaptureHelper {

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase();

    private ScreenCaptureHelper() {
    }

    public static boolean isMac() {
        return OS_NAME.contains("mac");
    }

    /**
     * 探测屏幕几何并在逻辑分辨率下统一坐标（pixel = logical，scale = 1）。
     */
    public static ScreenGeometry detect(Robot robot) {
        Rectangle bounds = resolveCaptureBounds();
        BufferedImage raw = robot.createScreenCapture(bounds);
        BufferedImage logical = toLogicalImage(raw, bounds);
        return new ScreenGeometry(
                bounds.x,
                bounds.y,
                bounds.width,
                bounds.height,
                logical.getWidth(),
                logical.getHeight(),
                1.0D,
                1.0D
        );
    }

    /**
     * 截图并返回逻辑分辨率的 RGB 图像。
     */
    public static BufferedImage capture(Robot robot, ScreenGeometry geometry) {
        Rectangle bounds = geometry != null
                ? geometry.getCaptureBounds()
                : resolveCaptureBounds();
        BufferedImage raw = robot.createScreenCapture(bounds);
        return toLogicalImage(raw, bounds);
    }

    /**
     * Mac 仅捕获主屏（避免虚拟桌面 union 在 Retina 下截屏异常），其他系统捕获虚拟桌面。
     */
    public static Rectangle resolveCaptureBounds() {
        if (isMac()) {
            return getPrimaryScreenBounds();
        }
        return ScreenGeometry.computeVirtualBounds();
    }

    public static Rectangle getPrimaryScreenBounds() {
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsConfiguration config = environment.getDefaultScreenDevice().getDefaultConfiguration();
        return config.getBounds();
    }

    /**
     * 将 Robot 原始截图转为标准 RGB，并按逻辑分辨率缩放（解决 Retina 偏色与画面放大问题）。
     */
    public static BufferedImage toLogicalImage(BufferedImage raw, Rectangle logicalBounds) {
        BufferedImage normalized = normalizeRgb(raw);
        int logicalWidth = logicalBounds.width;
        int logicalHeight = logicalBounds.height;
        if (logicalWidth <= 0 || logicalHeight <= 0) {
            return normalized;
        }
        if (normalized.getWidth() == logicalWidth && normalized.getHeight() == logicalHeight) {
            return normalized;
        }
        BufferedImage scaled = new BufferedImage(logicalWidth, logicalHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(normalized, 0, 0, logicalWidth, logicalHeight, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    /**
     * 归一化为 TYPE_INT_RGB，修复 macOS 截图偏粉/偏紫问题。
     */
    public static BufferedImage normalizeRgb(BufferedImage source) {
        if (source == null) {
            return null;
        }
        if (source.getType() == BufferedImage.TYPE_INT_RGB
                && source.getColorModel().getPixelSize() == 24) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(
                source.getWidth(),
                source.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    /**
     * 估算单屏缩放比（用于诊断）。
     */
    public static double resolveScaleX(GraphicsConfiguration config) {
        AffineTransform transform = config.getDefaultTransform();
        return transform.getScaleX();
    }

    public static double resolveScaleY(GraphicsConfiguration config) {
        AffineTransform transform = config.getDefaultTransform();
        return transform.getScaleY();
    }
}
