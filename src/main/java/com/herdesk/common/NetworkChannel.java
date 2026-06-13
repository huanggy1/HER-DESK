package com.herdesk.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 带粘包/分包处理的 TCP 通道封装。
 */
public class NetworkChannel implements AutoCloseable {

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    public NetworkChannel(Socket socket) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    public Socket getSocket() {
        return socket;
    }

    public void send(MessageType type, byte[] payload) throws IOException {
        if (closed.get()) {
            throw new IOException("连接已关闭");
        }
        synchronized (writeLock) {
            out.writeInt(Protocol.MAGIC);
            out.writeByte(type.getCode());
            int length = payload == null ? 0 : payload.length;
            out.writeInt(length);
            if (length > 0) {
                out.write(payload);
            }
            out.flush();
        }
    }

    public ReceivedMessage receive() throws IOException {
        if (closed.get()) {
            throw new IOException("连接已关闭");
        }
        int magic = in.readInt();
        if (magic != Protocol.MAGIC) {
            throw new IOException("协议魔数不匹配: 0x" + Integer.toHexString(magic));
        }
        int typeCode = in.readUnsignedByte();
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("非法载荷长度: " + length);
        }
        byte[] payload = new byte[length];
        if (length > 0) {
            in.readFully(payload);
        }
        return new ReceivedMessage(MessageType.fromCode(typeCode), payload);
    }

    public boolean isConnected() {
        return !closed.get() && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭时忽略 IO 异常
            }
        }
    }

    public static final class ReceivedMessage {
        private final MessageType type;
        private final byte[] payload;

        public ReceivedMessage(MessageType type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }

        public MessageType getType() {
            return type;
        }

        public byte[] getPayload() {
            return payload;
        }
    }

    public static boolean isConnectionClosed(IOException e) {
        if (e instanceof EOFException) {
            return true;
        }
        String message = e.getMessage();
        return message != null && (
                message.contains("Connection reset")
                        || message.contains("Broken pipe")
                        || message.contains("Socket closed")
                        || message.contains("连接已关闭")
        );
    }
}
