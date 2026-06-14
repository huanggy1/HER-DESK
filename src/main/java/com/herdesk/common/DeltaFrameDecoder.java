package com.herdesk.common;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 画面差分解码：将全帧或增量区块合并到本地画布。
 */
public class DeltaFrameDecoder {

    /** 当前累积的画面画布 */
    private BufferedImage canvas;
    /** 画布逻辑宽度 */
    private int screenWidth;
    /** 画布逻辑高度 */
    private int screenHeight;

    public BufferedImage getCanvas() {
        return canvas;
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    /** 清空画布，需重新接收全帧 */
    public void reset() {
        canvas = null;
        screenWidth = 0;
        screenHeight = 0;
    }

    /**
     * 应用全帧：解码 JPEG 并设为画布。
     * <p>
     * 若 JPEG 尺寸与声明不一致，缩放至目标宽高。
     */
    public BufferedImage applyFullFrame(int width, int height, byte[] jpegData) throws IOException {
        screenWidth = width;
        screenHeight = height;
        canvas = decodeJpeg(jpegData);
        if (canvas.getWidth() != width || canvas.getHeight() != height) {
            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            try {
                g.drawImage(canvas, 0, 0, width, height, null);
            } finally {
                g.dispose();
            }
            canvas = resized;
        }
        return canvas;
    }

    /**
     * 应用差分帧：将各区块 JPEG 绘制到画布对应位置。
     *
     * @throws IOException 尚未收到全帧
     */
    public BufferedImage applyDeltaFrame(List<DeltaFrameEncoder.BlockPatch> patches) throws IOException {
        if (canvas == null) {
            throw new IOException("尚未收到全帧，无法应用差分");
        }
        Graphics2D g = canvas.createGraphics();
        try {
            for (DeltaFrameEncoder.BlockPatch patch : patches) {
                BufferedImage block = decodeJpeg(patch.getJpegData());
                g.drawImage(block, patch.getX(), patch.getY(), null);
            }
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private BufferedImage decodeJpeg(byte[] data) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null) {
            throw new IOException("JPEG 解码失败");
        }
        return image;
    }
}
