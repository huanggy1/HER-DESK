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

    private BufferedImage canvas;
    private int screenWidth;
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

    public void reset() {
        canvas = null;
        screenWidth = 0;
        screenHeight = 0;
    }

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
