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

    /** 区块边长（像素） */
    private final int blockSize;
    /** 上一帧完整图像（用于差分与补丁合并） */
    private BufferedImage previousFrame;
    /** 各区块像素哈希，用于快速检测变化 */
    private long[][] previousHashes;
    /** 当前画面宽度 */
    private int screenWidth;
    /** 当前画面高度 */
    private int screenHeight;
    /** 横向区块数 */
    private int blocksX;
    /** 纵向区块数 */
    private int blocksY;
    /** JPEG 压缩质量 */
    private float jpegQuality = QualityLevel.BALANCED.getJpegQuality();

    public DeltaFrameEncoder(int blockSize) {
        this.blockSize = blockSize;
    }

    /** 设置 JPEG 质量，限制在 0.1~1.0 */
    public void setJpegQuality(float jpegQuality) {
        this.jpegQuality = Math.max(0.1f, Math.min(1.0f, jpegQuality));
    }

    /** 清空上一帧状态，下次 encode 将发送全帧 */
    public void reset() {
        previousFrame = null;
        previousHashes = null;
    }

    /**
     * 编码当前帧。
     * <p>
     * 首帧或尺寸变化 → 全帧；无变化 → empty；有变化 → 差分区块列表。
     */
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

    /** 按当前尺寸初始化区块网格 */
    private void initBlockState(int width, int height) {
        screenWidth = width;
        screenHeight = height;
        blocksX = (width + blockSize - 1) / blockSize;
        blocksY = (height + blockSize - 1) / blockSize;
        previousHashes = new long[blocksY][blocksX];
    }

    /** 计算所有区块哈希 */
    private long[][] computeAllHashes(BufferedImage image) {
        long[][] hashes = new long[blocksY][blocksX];
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                hashes[by][bx] = hashBlock(image, bx, by);
            }
        }
        return hashes;
    }

    /** 仅更新变化区块的哈希 */
    private void updateHashes(BufferedImage image, List<BlockPatch> patches) {
        for (BlockPatch patch : patches) {
            int bx = patch.getX() / blockSize;
            int by = patch.getY() / blockSize;
            previousHashes[by][bx] = hashBlock(image, bx, by);
        }
    }

    /** 遍历区块，哈希不同则 JPEG 压缩该区块 */
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

    /** 对区块内像素隔点采样计算哈希 */
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

    /** 将变化区块绘制到上一帧副本，保持基准同步 */
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

    /** JPEG 压缩单张图像 */
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

    /** 编码结果：全帧、差分或空帧（三选一） */
    public static final class EncodedFrame {
        /** 是否为全帧 */
        private final boolean fullFrame;
        /** 是否无变化（跳过发送） */
        private final boolean empty;
        /** 全帧宽度 */
        private final int width;
        /** 全帧高度 */
        private final int height;
        /** 全帧 JPEG */
        private final byte[] fullJpeg;
        /** 差分区块列表 */
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

    /** 单个变化区块：左上角坐标 + JPEG 数据 */
    public static final class BlockPatch {
        /** 区块左上角 X */
        private final int x;
        /** 区块左上角 Y */
        private final int y;
        /** 区块 JPEG 数据 */
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
