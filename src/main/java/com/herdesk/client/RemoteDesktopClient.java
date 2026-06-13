package com.herdesk.client;

import com.herdesk.common.DeltaFrameDecoder;
import com.herdesk.common.MessageType;
import com.herdesk.common.NetworkChannel;
import com.herdesk.common.PayloadCodec;
import com.herdesk.common.Protocol;
import com.herdesk.common.QualityLevel;
import com.herdesk.common.ScreenGeometry;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 控制端核心服务：连接被控端、接收画面、发送键鼠。
 */
public class RemoteDesktopClient {

    public interface ClientListener {
        void onStatusChanged(String status);

        void onConnected(ScreenGeometry screenGeometry);

        void onDisconnected(String reason);

        void onFrameUpdated(BufferedImage frame);

        void onError(String message);

        void onFpsUpdated(int fps);
    }

    private final ClientListener listener;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicInteger fpsCounter = new AtomicInteger(0);
    private final AtomicLong fpsWindowStart = new AtomicLong(System.currentTimeMillis());
    private final BlockingDeque<OutboundMessage> outboundQueue = new LinkedBlockingDeque<OutboundMessage>();

    private String host;
    private int port;
    private NetworkChannel channel;
    private Thread receiveThread;
    private Thread senderThread;
    private Thread heartbeatThread;
    private DeltaFrameDecoder frameDecoder;
    private volatile QualityLevel qualityLevel = QualityLevel.BALANCED;

    public RemoteDesktopClient(ClientListener listener) {
        this.listener = listener;
        this.frameDecoder = new DeltaFrameDecoder();
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isConnecting() {
        return connecting.get();
    }

    public QualityLevel getQualityLevel() {
        return qualityLevel;
    }

    public synchronized void connect(String host, int port) {
        if (connected.get() || connecting.get()) {
            return;
        }
        this.host = host;
        this.port = port;
        connecting.set(true);
        listener.onStatusChanged("正在连接 " + host + ":" + port + " ...");

        Thread connectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                doConnect();
            }
        }, "client-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private void doConnect() {
        try {
            java.net.Socket socket = new java.net.Socket(host, port);
            channel = new NetworkChannel(socket);
            channel.send(MessageType.HANDSHAKE, PayloadCodec.encodeHandshake());

            NetworkChannel.ReceivedMessage response = channel.receive();
            if (response.getType() != MessageType.HANDSHAKE) {
                throw new IOException("握手响应无效");
            }
            ScreenGeometry geometry = PayloadCodec.decodeHandshakeResponse(response.getPayload());
            frameDecoder.reset();
            connected.set(true);
            connecting.set(false);
            listener.onConnected(geometry);
            listener.onStatusChanged("已连接");

            senderThread = new Thread(new SenderLoop(), "client-sender");
            senderThread.setDaemon(true);
            senderThread.start();

            heartbeatThread = new Thread(new HeartbeatLoop(), "client-heartbeat");
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();

            sendQuality(qualityLevel);

            receiveThread = new Thread(new ReceiveLoop(), "client-receive");
            receiveThread.setDaemon(true);
            receiveThread.start();
        } catch (Exception e) {
            connecting.set(false);
            cleanupChannel();
            listener.onError("连接失败: " + e.getMessage());
            listener.onDisconnected("连接失败");
        }
    }

    public synchronized void disconnect() {
        if (!connected.get() && !connecting.get()) {
            return;
        }
        enqueue(MessageType.DISCONNECT, new byte[0], true);
        shutdown("已断开连接");
    }

    public void sendMouseMove(int x, int y) {
        if (!connected.get()) {
            return;
        }
        try {
            enqueue(MessageType.MOUSE_MOVE, PayloadCodec.encodeMouseMove(x, y), true);
        } catch (IOException ignored) {
            // 编码不会失败
        }
    }

    public void sendMouseButton(int x, int y, int button, boolean press) {
        if (!connected.get()) {
            return;
        }
        try {
            enqueue(MessageType.MOUSE_BUTTON, PayloadCodec.encodeMouseButton(x, y, button, press), true);
        } catch (IOException ignored) {
            // 编码不会失败
        }
    }

    public void sendMouseWheel(int x, int y, int rotation) {
        if (!connected.get()) {
            return;
        }
        try {
            enqueue(MessageType.MOUSE_WHEEL, PayloadCodec.encodeMouseWheel(x, y, rotation), true);
        } catch (IOException ignored) {
            // 编码不会失败
        }
    }

    public void sendKeyEvent(int keyCode, int modifiers, boolean press) {
        if (!connected.get()) {
            return;
        }
        try {
            enqueue(MessageType.KEY_EVENT, PayloadCodec.encodeKeyEvent(keyCode, modifiers, press), true);
        } catch (IOException ignored) {
            // 编码不会失败
        }
    }

    public void setQuality(QualityLevel level) {
        this.qualityLevel = level;
        if (connected.get()) {
            sendQuality(level);
        }
    }

    private void sendQuality(QualityLevel level) {
        try {
            enqueue(MessageType.QUALITY, PayloadCodec.encodeQuality(level), false);
        } catch (IOException ignored) {
            // 编码不会失败
        }
    }

    private void enqueue(MessageType type, byte[] payload, boolean priority) {
        OutboundMessage message = new OutboundMessage(type, payload);
        if (priority) {
            outboundQueue.offerFirst(message);
        } else {
            outboundQueue.offerLast(message);
        }
    }

    private class SenderLoop implements Runnable {
        @Override
        public void run() {
            while (connected.get()) {
                try {
                    OutboundMessage message = outboundQueue.take();
                    channel.send(message.type, message.payload);
                    if (message.type == MessageType.DISCONNECT) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    if (connected.get()) {
                        shutdown(NetworkChannel.isConnectionClosed(e) ? "连接已断开" : e.getMessage());
                    }
                    break;
                }
            }
        }
    }

    private class ReceiveLoop implements Runnable {
        @Override
        public void run() {
            while (connected.get()) {
                try {
                    NetworkChannel.ReceivedMessage message = channel.receive();
                    handleServerMessage(message);
                } catch (IOException e) {
                    if (connected.get()) {
                        shutdown(NetworkChannel.isConnectionClosed(e) ? "连接已断开" : e.getMessage());
                    }
                    break;
                }
            }
        }
    }

    private void handleServerMessage(NetworkChannel.ReceivedMessage message) throws IOException {
        MessageType type = message.getType();
        byte[] payload = message.getPayload();
        switch (type) {
            case FULL_FRAME:
                PayloadCodec.FramePayload full = PayloadCodec.decodeFullFrame(payload);
                BufferedImage fullImage = frameDecoder.applyFullFrame(
                        full.getWidth(),
                        full.getHeight(),
                        full.getFullJpeg()
                );
                notifyFrame(fullImage);
                break;
            case DELTA_FRAME:
                BufferedImage deltaImage = frameDecoder.applyDeltaFrame(
                        PayloadCodec.decodeDeltaFrame(payload)
                );
                notifyFrame(deltaImage);
                break;
            case HEARTBEAT:
                break;
            case DISCONNECT:
                shutdown("被控端已断开");
                break;
            default:
                break;
        }
    }

    private void notifyFrame(BufferedImage image) {
        recordFps();
        listener.onFrameUpdated(image);
    }

    private void recordFps() {
        fpsCounter.incrementAndGet();
        long now = System.currentTimeMillis();
        long windowStart = fpsWindowStart.get();
        if (now - windowStart >= 1000L) {
            int fps = fpsCounter.getAndSet(0);
            fpsWindowStart.set(now);
            listener.onFpsUpdated(fps);
        }
    }

    private class HeartbeatLoop implements Runnable {
        @Override
        public void run() {
            while (connected.get()) {
                try {
                    Thread.sleep(Protocol.HEARTBEAT_INTERVAL_MS);
                    enqueue(MessageType.HEARTBEAT, new byte[0], false);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private synchronized void shutdown(String reason) {
        if (!connected.get() && !connecting.get()) {
            cleanupChannel();
            return;
        }
        connected.set(false);
        connecting.set(false);
        outboundQueue.clear();
        joinThread(receiveThread);
        joinThread(senderThread);
        joinThread(heartbeatThread);
        receiveThread = null;
        senderThread = null;
        heartbeatThread = null;
        cleanupChannel();
        frameDecoder.reset();
        listener.onDisconnected(reason);
        listener.onStatusChanged(reason);
        listener.onFpsUpdated(0);
    }

    private void cleanupChannel() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }

    private void joinThread(Thread thread) {
        if (thread != null) {
            try {
                thread.join(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class OutboundMessage {
        private final MessageType type;
        private final byte[] payload;
        private OutboundMessage(MessageType type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }
}
