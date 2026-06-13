package com.herdesk.common;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * 画面差分编码：对比上一帧，仅压缩变化区块。
 */
public class DeltaFrameEncoder {

    private final int blockSize;
    private BufferedImage previousFrame;
    private long[][] previousHashes;
    private int screenWidth;
    private int screenHeight;
    private int blocksX;
    private int blocksY;
    private float jpegQuality = QualityLevel.BALANCED.getJpegQuality();

    public DeltaFrameEncoder(int blockSize) {
        this.blockSize = blockSize;
    }

    public void setJpegQuality(float jpegQuality) {
        this.jpegQuality = Math.max(0.1f, Math.min(1.0f, jpegQuality));
    }

    public void reset() {
        previousFrame = null;
        previousHashes = null;
    }

    public EncodedFrame encode(BufferedImage current) throws IOException {
        if (current == null) {
            throw new IllegalArgumentException("当前帧不能为空");
        }
        if (previousFrame == null
                || previousFrame.getWidth() != current.getWidth()
                || previousFrame.getHeight() != current.getHeight()) {
            previousFrame = copyImage(current);
            initBlockState(current.getWidth(), current.getHeight());
            byte[] jpeg = encodeJpeg(current);
            previousHashes = computeAllHashes(current);
            return EncodedFrame.fullFrame(current.getWidth(), current.getHeight(), jpeg);
        }

        List<BlockPatch> patches = detectChangedBlocks(current);
        if (patches.isEmpty()) {
            return EncodedFrame.empty();
        }

        applyPatches(previousFrame, current, patches);
        updateHashes(current, patches);
        return EncodedFrame.deltaFrame(patches);
    }

    private void initBlockState(int width, int height) {
        screenWidth = width;
        screenHeight = height;
        blocksX = (width + blockSize - 1) / blockSize;
        blocksY = (height + blockSize - 1) / blockSize;
        previousHashes = new long[blocksY][blocksX];
    }

    private long[][] computeAllHashes(BufferedImage image) {
        long[][] hashes = new long[blocksY][blocksX];
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                hashes[by][bx] = hashBlock(image, bx, by);
            }
        }
        return hashes;
    }

    private void updateHashes(BufferedImage image, List<BlockPatch> patches) {
        for (BlockPatch patch : patches) {
            int bx = patch.getX() / blockSize;
            int by = patch.getY() / blockSize;
            previousHashes[by][bx] = hashBlock(image, bx, by);
        }
    }

    private List<BlockPatch> detectChangedBlocks(BufferedImage current) throws IOException {
        List<BlockPatch> patches = new ArrayList<BlockPatch>();
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                long hash = hashBlock(current, bx, by);
                if (hash != previousHashes[by][bx]) {
                    int x = bx * blockSize;
                    int y = by * blockSize;
                    int w = Math.min(blockSize, screenWidth - x);
                    int h = Math.min(blockSize, screenHeight - y);
                    BufferedImage blockImage = current.getSubimage(x, y, w, h);
                    byte[] jpeg = encodeJpeg(blockImage);
                    patches.add(new BlockPatch(x, y, jpeg));
                }
            }
        }
        return patches;
    }

    private long hashBlock(BufferedImage image, int blockX, int blockY) {
        int startX = blockX * blockSize;
        int startY = blockY * blockSize;
        int endX = Math.min(startX + blockSize, image.getWidth());
        int endY = Math.min(startY + blockSize, image.getHeight());
        long hash = 17L;
        for (int y = startY; y < endY; y += 2) {
            for (int x = startX; x < endX; x += 2) {
                hash = hash * 31L + (image.getRGB(x, y) & 0xFFFFFFL);
            }
        }
        return hash;
    }

    private void applyPatches(BufferedImage target, BufferedImage source, List<BlockPatch> patches) {
        Graphics2D g = target.createGraphics();
        try {
            for (BlockPatch patch : patches) {
                int w = Math.min(blockSize, screenWidth - patch.getX());
                int h = Math.min(blockSize, screenHeight - patch.getY());
                BufferedImage block = source.getSubimage(patch.getX(), patch.getY(), w, h);
                g.drawImage(block, patch.getX(), patch.getY(), null);
            }
        } finally {
            g.dispose();
        }
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter writer = null;
        ImageOutputStream ios = null;
        try {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                throw new IOException("未找到 JPEG 编码器");
            }
            writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(jpegQuality);
            }
            param.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
            ios = ImageIO.createImageOutputStream(baos);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            if (ios != null) {
                ios.close();
            }
            if (writer != null) {
                writer.dispose();
            }
        }
        return baos.toByteArray();
    }

    private BufferedImage decodeJpeg(byte[] data) throws IOException {
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
        BufferedImage image = ImageIO.read(bais);
        if (image == null) {
            throw new IOException("JPEG 解码失败");
        }
        return image;
    }

    private BufferedImage copyImage(BufferedImage source) {
        return ScreenCaptureHelper.normalizeRgb(source);
    }

    public static final class EncodedFrame {
        private final boolean fullFrame;
        private final boolean empty;
        private final int width;
        private final int height;
        private final byte[] fullJpeg;
        private final List<BlockPatch> patches;

        private EncodedFrame(boolean fullFrame, boolean empty, int width, int height,
                             byte[] fullJpeg, List<BlockPatch> patches) {
            this.fullFrame = fullFrame;
            this.empty = empty;
            this.width = width;
            this.height = height;
            this.fullJpeg = fullJpeg;
            this.patches = patches;
        }

        public static EncodedFrame fullFrame(int width, int height, byte[] jpeg) {
            return new EncodedFrame(true, false, width, height, jpeg, null);
        }

        public static EncodedFrame deltaFrame(List<BlockPatch> patches) {
            return new EncodedFrame(false, false, 0, 0, null, patches);
        }

        public static EncodedFrame empty() {
            return new EncodedFrame(false, true, 0, 0, null, null);
        }

        public boolean isFullFrame() {
            return fullFrame;
        }

        public boolean isEmpty() {
            return empty;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public byte[] getFullJpeg() {
            return fullJpeg;
        }

        public List<BlockPatch> getPatches() {
            return patches;
        }
    }

    public static final class BlockPatch {
        private final int x;
        private final int y;
        private final byte[] jpegData;

        public BlockPatch(int x, int y, byte[] jpegData) {
            this.x = x;
            this.y = y;
            this.jpegData = jpegData;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public byte[] getJpegData() {
            return jpegData;
        }
    }
}
