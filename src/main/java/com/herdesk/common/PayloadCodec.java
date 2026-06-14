package com.herdesk.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 协议载荷编解码：握手、画面、键鼠、画质等消息的序列化与反序列化。
 */
public final class PayloadCodec {

    private PayloadCodec() {
    }

    /** 编码握手请求（空载荷） */
    public static byte[] encodeHandshake() {
        return new byte[0];
    }

    /** 解码握手请求（原样返回） */
    public static byte[] decodeHandshake(byte[] payload) {
        return payload;
    }

    /**
     * 编码握手响应：原点、逻辑尺寸、像素尺寸。
     */
    public static byte[] encodeHandshakeResponse(ScreenGeometry geometry) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(geometry.getOriginX());
        out.writeInt(geometry.getOriginY());
        out.writeInt(geometry.getLogicalWidth());
        out.writeInt(geometry.getLogicalHeight());
        out.writeInt(geometry.getPixelWidth());
        out.writeInt(geometry.getPixelHeight());
        return baos.toByteArray();
    }

    /**
     * 解码握手响应。
     * <p>
     * 8 字节载荷走旧协议（仅宽高）；否则读取完整几何并推算缩放比。
     */
    public static ScreenGeometry decodeHandshakeResponse(byte[] payload) throws IOException {
        if (payload == null || payload.length == 8) {
            DataInputStream legacyIn = new DataInputStream(new ByteArrayInputStream(payload));
            int width = legacyIn.readInt();
            int height = legacyIn.readInt();
            return new ScreenGeometry(0, 0, width, height, width, height, 1.0D, 1.0D);
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int originX = in.readInt();
        int originY = in.readInt();
        int logicalWidth = in.readInt();
        int logicalHeight = in.readInt();
        int pixelWidth = in.readInt();
        int pixelHeight = in.readInt();
        double scaleX = pixelWidth / (double) logicalWidth;
        double scaleY = pixelHeight / (double) logicalHeight;
        return new ScreenGeometry(
                originX, originY,
                logicalWidth, logicalHeight,
                pixelWidth, pixelHeight,
                scaleX, scaleY
        );
    }

    /** @deprecated 仅兼容旧协议，新代码请使用 {@link #encodeHandshakeResponse(ScreenGeometry)} */
    public static byte[] encodeHandshakeResponse(int width, int height) throws IOException {
        return encodeHandshakeResponse(new ScreenGeometry(0, 0, width, height, width, height, 1.0D, 1.0D));
    }

    /** @deprecated 仅兼容旧协议，新代码请使用 {@link #decodeHandshakeResponse(byte[])} */
    public static int[] decodeHandshakeResponseLegacy(byte[] payload) throws IOException {
        ScreenGeometry geometry = decodeHandshakeResponse(payload);
        return new int[]{geometry.getPixelWidth(), geometry.getPixelHeight()};
    }

    /** 编码全帧：宽、高、JPEG 长度、JPEG 数据 */
    public static byte[] encodeFullFrame(int width, int height, byte[] jpeg) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(width);
        out.writeInt(height);
        out.writeInt(jpeg.length);
        out.write(jpeg);
        return baos.toByteArray();
    }

    /** 解码全帧载荷 */
    public static FramePayload decodeFullFrame(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int width = in.readInt();
        int height = in.readInt();
        int length = in.readInt();
        byte[] jpeg = new byte[length];
        in.readFully(jpeg);
        return new FramePayload(width, height, jpeg, null);
    }

    /** 编码差分帧：区块数量 + 各区块坐标与 JPEG */
    public static byte[] encodeDeltaFrame(List<DeltaFrameEncoder.BlockPatch> patches) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(patches.size());
        for (DeltaFrameEncoder.BlockPatch patch : patches) {
            out.writeInt(patch.getX());
            out.writeInt(patch.getY());
            out.writeInt(patch.getJpegData().length);
            out.write(patch.getJpegData());
        }
        return baos.toByteArray();
    }

    /** 解码差分帧载荷 */
    public static List<DeltaFrameEncoder.BlockPatch> decodeDeltaFrame(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int count = in.readInt();
        List<DeltaFrameEncoder.BlockPatch> patches = new ArrayList<DeltaFrameEncoder.BlockPatch>(count);
        for (int i = 0; i < count; i++) {
            int x = in.readInt();
            int y = in.readInt();
            int length = in.readInt();
            byte[] jpeg = new byte[length];
            in.readFully(jpeg);
            patches.add(new DeltaFrameEncoder.BlockPatch(x, y, jpeg));
        }
        return patches;
    }

    /** 编码鼠标移动：x、y */
    public static byte[] encodeMouseMove(int x, int y) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(x);
        out.writeInt(y);
        return baos.toByteArray();
    }

    /** 解码鼠标移动，返回 [x, y] */
    public static int[] decodeMouseMove(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new int[]{in.readInt(), in.readInt()};
    }

    /** 编码鼠标按键：坐标、按键码、按下/释放 */
    public static byte[] encodeMouseButton(int x, int y, int button, boolean press) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(button);
        out.writeBoolean(press);
        return baos.toByteArray();
    }

    /** 解码鼠标按键载荷 */
    public static MouseButtonPayload decodeMouseButton(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new MouseButtonPayload(in.readInt(), in.readInt(), in.readInt(), in.readBoolean());
    }

    /** 编码鼠标滚轮：坐标、滚动量 */
    public static byte[] encodeMouseWheel(int x, int y, int rotation) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(rotation);
        return baos.toByteArray();
    }

    /** 解码鼠标滚轮，返回 [x, y, rotation] */
    public static int[] decodeMouseWheel(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new int[]{in.readInt(), in.readInt(), in.readInt()};
    }

    /** 编码键盘事件：键码、修饰键、按下/释放 */
    public static byte[] encodeKeyEvent(int keyCode, int modifiers, boolean press) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(keyCode);
        out.writeInt(modifiers);
        out.writeBoolean(press);
        return baos.toByteArray();
    }

    /** 解码键盘事件载荷 */
    public static KeyEventPayload decodeKeyEvent(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new KeyEventPayload(in.readInt(), in.readInt(), in.readBoolean());
    }

    /** 编码画质档位 */
    public static byte[] encodeQuality(QualityLevel level) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(level.getCode());
        return baos.toByteArray();
    }

    /** 解码画质档位 */
    public static QualityLevel decodeQuality(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return QualityLevel.fromCode(in.readInt());
    }

    /** 画面载荷：全帧或差分（二选一） */
    public static final class FramePayload {
        /** 画面宽度 */
        private final int width;
        /** 画面高度 */
        private final int height;
        /** 全帧 JPEG 数据（差分帧时为 null） */
        private final byte[] fullJpeg;
        /** 差分区块列表（全帧时为 null） */
        private final List<DeltaFrameEncoder.BlockPatch> patches;

        public FramePayload(int width, int height, byte[] fullJpeg,
                            List<DeltaFrameEncoder.BlockPatch> patches) {
            this.width = width;
            this.height = height;
            this.fullJpeg = fullJpeg;
            this.patches = patches;
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

        public List<DeltaFrameEncoder.BlockPatch> getPatches() {
            return patches;
        }
    }

    /** 鼠标按键载荷 */
    public static final class MouseButtonPayload {
        /** 捕获图像 X 坐标 */
        private final int x;
        /** 捕获图像 Y 坐标 */
        private final int y;
        /** 按键码（见 {@link Protocol#MOUSE_LEFT} 等） */
        private final int button;
        /** true 按下，false 释放 */
        private final boolean press;

        public MouseButtonPayload(int x, int y, int button, boolean press) {
            this.x = x;
            this.y = y;
            this.button = button;
            this.press = press;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getButton() {
            return button;
        }

        public boolean isPress() {
            return press;
        }
    }

    /** 键盘事件载荷 */
    public static final class KeyEventPayload {
        /** AWT 键码 */
        private final int keyCode;
        /** 修饰键掩码 */
        private final int modifiers;
        /** true 按下，false 释放 */
        private final boolean press;

        public KeyEventPayload(int keyCode, int modifiers, boolean press) {
            this.keyCode = keyCode;
            this.modifiers = modifiers;
            this.press = press;
        }

        public int getKeyCode() {
            return keyCode;
        }

        public int getModifiers() {
            return modifiers;
        }

        public boolean isPress() {
            return press;
        }
    }
}
