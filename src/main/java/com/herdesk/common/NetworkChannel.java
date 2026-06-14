package com.herdesk.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 带粘包/分包处理的 TCP 通道封装。
 * <p>
 * 发送/接收完整 HDRD 协议帧，写操作串行化。
 */
public class NetworkChannel implements AutoCloseable {

    /** 底层 TCP 套接字 */
    private final Socket socket;
    /** 帧数据输入流 */
    private final DataInputStream in;
    /** 帧数据输出流 */
    private final DataOutputStream out;
    /** 通道是否已关闭 */
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** 写锁，保证帧原子发送 */
    private final Object writeLock = new Object();

    /**
     * 包装已连接的 Socket，启用 TCP_NODELAY 与 keepAlive。
     */
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

    /**
     * 发送一帧：魔数 + 类型 + 长度 + 载荷。
     *
     * @throws IOException 连接已关闭或写入失败
     */
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

    /**
     * 阻塞读取一帧；校验魔数与载荷长度。
     *
     * @throws IOException 连接已关闭、魔数不匹配或长度非法
     */
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

    /** 连接是否仍可用（未关闭且 Socket 已连接） */
    public boolean isConnected() {
        return !closed.get() && socket.isConnected() && !socket.isClosed();
    }

    /** 幂等关闭 Socket */
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

    /** 接收到的单帧消息 */
    public static final class ReceivedMessage {
        /** 消息类型 */
        private final MessageType type;
        /** 载荷字节（可能为空数组） */
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

    /**
     * 判断 IOException 是否表示对端已断开。
     * <p>
     * 匹配 EOF、Connection reset、Broken pipe 等常见断连消息。
     */
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
