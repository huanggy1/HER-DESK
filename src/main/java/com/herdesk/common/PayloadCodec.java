package com.herdesk.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 协议载荷编解码。
 */
public final class PayloadCodec {

    private PayloadCodec() {
    }

    public static byte[] encodeHandshake() {
        return new byte[0];
    }

    public static byte[] decodeHandshake(byte[] payload) {
        return payload;
    }

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

    public static byte[] encodeFullFrame(int width, int height, byte[] jpeg) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(width);
        out.writeInt(height);
        out.writeInt(jpeg.length);
        out.write(jpeg);
        return baos.toByteArray();
    }

    public static FramePayload decodeFullFrame(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int width = in.readInt();
        int height = in.readInt();
        int length = in.readInt();
        byte[] jpeg = new byte[length];
        in.readFully(jpeg);
        return new FramePayload(width, height, jpeg, null);
    }

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

    public static byte[] encodeMouseMove(int x, int y) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(x);
        out.writeInt(y);
        return baos.toByteArray();
    }

    public static int[] decodeMouseMove(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new int[]{in.readInt(), in.readInt()};
    }

    public static byte[] encodeMouseButton(int x, int y, int button, boolean press) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(button);
        out.writeBoolean(press);
        return baos.toByteArray();
    }

    public static MouseButtonPayload decodeMouseButton(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new MouseButtonPayload(in.readInt(), in.readInt(), in.readInt(), in.readBoolean());
    }

    public static byte[] encodeMouseWheel(int x, int y, int rotation) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(rotation);
        return baos.toByteArray();
    }

    public static int[] decodeMouseWheel(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new int[]{in.readInt(), in.readInt(), in.readInt()};
    }

    public static byte[] encodeKeyEvent(int keyCode, int modifiers, boolean press) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(keyCode);
        out.writeInt(modifiers);
        out.writeBoolean(press);
        return baos.toByteArray();
    }

    public static KeyEventPayload decodeKeyEvent(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new KeyEventPayload(in.readInt(), in.readInt(), in.readBoolean());
    }

    public static byte[] encodeQuality(QualityLevel level) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(level.getCode());
        return baos.toByteArray();
    }

    public static QualityLevel decodeQuality(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return QualityLevel.fromCode(in.readInt());
    }

    public static final class FramePayload {
        private final int width;
        private final int height;
        private final byte[] fullJpeg;
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

    public static final class MouseButtonPayload {
        private final int x;
        private final int y;
        private final int button;
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

    public static final class KeyEventPayload {
        private final int keyCode;
        private final int modifiers;
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
