package com.herdesk.client;

import com.herdesk.common.AppLogger;
import com.herdesk.common.DeltaFrameDecoder;
import com.herdesk.common.MessageType;
import com.herdesk.common.NetworkChannel;
import com.herdesk.common.PayloadCodec;
import com.herdesk.common.Protocol;
import com.herdesk.common.QualityLevel;
import com.herdesk.common.RelayConnector;
import com.herdesk.common.ScreenGeometry;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 控制端核心服务：建立连接、收发协议消息、解码画面帧并转发键鼠事件。
 */
public class RemoteDesktopClient {

    /** 连接状态与画面帧回调，由 UI 层实现。 */
    public interface ClientListener {
        void onStatusChanged(String status);

        void onConnected(ScreenGeometry screenGeometry);

        void onDisconnected(String reason);

        void onFrameUpdated(BufferedImage frame);

        void onError(String message);

        void onFpsUpdated(int fps);

        void onLog(AppLogger.Level level, String message);
    }

    private final ClientListener listener;

    // --- 连接状态 ---
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private String host;
    private int port;
    private NetworkChannel channel;

    // --- 工作线程 ---
    private Thread receiveThread;
    private Thread senderThread;
    private Thread heartbeatThread;

    // --- 出站消息队列（键鼠优先插队） ---
    private final BlockingDeque<OutboundMessage> outboundQueue = new LinkedBlockingDeque<OutboundMessage>();

    // --- 画面解码与统计 ---
    private DeltaFrameDecoder frameDecoder;
    private volatile QualityLevel qualityLevel = QualityLevel.BALANCED;
    private final AtomicBoolean firstFrameLogged = new AtomicBoolean(false);
    private final AtomicInteger fpsCounter = new AtomicInteger(0);
    private final AtomicLong fpsWindowStart = new AtomicLong(System.currentTimeMillis());

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
        connectInternal(host, port, false, null, 0, null, null);
    }

    public synchronized void connectViaRelay(String relayHost, int relayPort, String roomId, String password) {
        connectInternal(relayHost, relayPort, true, relayHost, relayPort, roomId, password);
    }

    private synchronized void connectInternal(String host, int port, boolean relayMode,
                                              String relayHost, int relayPort, String roomId, String password) {
        if (connected.get() || connecting.get()) {
            return;
        }
        this.host = host;
        this.port = port;
        connecting.set(true);
        if (relayMode) {
            logInfo("正在经中继 " + relayHost + ":" + relayPort + " 加入房间 " + roomId);
            listener.onStatusChanged("正在经中继 " + relayHost + ":" + relayPort + " 加入房间 " + roomId + " ...");
        } else {
            logInfo("正在连接被控端 " + host + ":" + port);
            listener.onStatusChanged("正在连接 " + host + ":" + port + " ...");
        }

        final boolean useRelay = relayMode;
        final String joinRoomId = roomId;
        final String joinPassword = password;
        final String joinRelayHost = relayHost;
        final int joinRelayPort = relayPort;
        // 连接在独立线程执行，避免阻塞 UI
        Thread connectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (useRelay) {
                    doConnectViaRelay(joinRelayHost, joinRelayPort, joinRoomId, joinPassword);
                } else {
                    doConnect();
                }
            }
        }, "client-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private void doConnectViaRelay(String relayHost, int relayPort, String roomId, String password) {
        String context = "连接中继 " + relayHost + ":" + relayPort + " 房间 " + roomId;
        bindRelayStepLogger();
        try {
            logInfo("开始中继连接流程");
            Socket socket = RelayConnector.joinClient(relayHost, relayPort, roomId, password);
            logInfo("中继隧道就绪，进入 HDRD 握手");
            establishSession(socket);
        } catch (Exception e) {
            connecting.set(false);
            cleanupChannel();
            String error = AppLogger.formatNetworkError(context, e);
            logError(error);
            listener.onError(error);
            listener.onDisconnected("连接失败");
        } finally {
            RelayConnector.unbindStepLogger();
        }
    }

    private void doConnect() {
        String context = "连接被控端 " + host + ":" + port;
        int timeoutSec = AppLogger.CONNECT_TIMEOUT_MS / 1000;
        try {
            logInfo("正在连接 TCP " + host + ":" + port + "（超时 " + timeoutSec + " 秒）");
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), AppLogger.CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            logInfo("TCP 已建立 " + socket.getLocalSocketAddress() + " -> " + socket.getRemoteSocketAddress());
            establishSession(socket);
        } catch (Exception e) {
            connecting.set(false);
            cleanupChannel();
            String error = AppLogger.formatNetworkError(context, e);
            logError(error);
            listener.onError(error);
            listener.onDisconnected("连接失败");
        }
    }

    /** 握手成功后启动发送、心跳、接收三条守护线程。 */
    private void establishSession(Socket socket) throws IOException {
        firstFrameLogged.set(false);
        channel = new NetworkChannel(socket);
        logInfo("发送 HANDSHAKE 请求");
        channel.send(MessageType.HANDSHAKE, PayloadCodec.encodeHandshake());

        logInfo("等待被控端 HANDSHAKE 响应...");
        NetworkChannel.ReceivedMessage response = channel.receive();
        if (response.getType() != MessageType.HANDSHAKE) {
            String detail = "握手响应无效，收到消息类型: " + response.getType();
            logError(detail);
            throw new IOException(detail);
        }
        ScreenGeometry geometry = PayloadCodec.decodeHandshakeResponse(response.getPayload());
        frameDecoder.reset();
        connected.set(true);
        connecting.set(false);
        logInfo("握手成功，远程屏幕像素 "
                + geometry.getPixelWidth() + "x" + geometry.getPixelHeight()
                + "，逻辑 " + geometry.getLogicalWidth() + "x" + geometry.getLogicalHeight()
                + "，缩放 " + String.format("%.2f", geometry.getScaleX())
                + "x" + String.format("%.2f", geometry.getScaleY()));
        listener.onConnected(geometry);
        listener.onStatusChanged("已连接");

        senderThread = new Thread(new SenderLoop(), "client-sender");
        senderThread.setDaemon(true);
        senderThread.start();

        heartbeatThread = new Thread(new HeartbeatLoop(), "client-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        sendQuality(qualityLevel);
        logInfo("已发送画质设置: " + qualityLevel.name());

        receiveThread = new Thread(new ReceiveLoop(), "client-receive");
        receiveThread.setDaemon(true);
        receiveThread.start();
        logInfo("数据通道已启动（发送/接收/心跳线程）");
    }

    public synchronized void disconnect() {
        if (!connected.get() && !connecting.get()) {
            return;
        }
        logInfo("用户主动断开连接");
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
            logInfo("切换画质为 " + level.name());
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

    /** 键鼠等交互消息插队，画质等非紧急消息排队尾。 */
    private void enqueue(MessageType type, byte[] payload, boolean priority) {
        OutboundMessage message = new OutboundMessage(type, payload);
        if (priority) {
            outboundQueue.offerFirst(message);
        } else {
            outboundQueue.offerLast(message);
        }
    }

    /** 从出站队列取消息并写入网络通道，遇断开则触发 shutdown。 */
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
                        boolean closed = NetworkChannel.isConnectionClosed(e);
                        shutdown(closed ? "连接已断开" : e.getMessage());
                        if (closed) {
                            logWarn("发送通道断开");
                        } else {
                            logError(AppLogger.formatNetworkError("发送数据失败", e));
                        }
                    }
                    break;
                }
            }
        }
    }

    /** 循环读取被控端消息，解码全帧/差分帧并通知 UI。 */
    private class ReceiveLoop implements Runnable {
        @Override
        public void run() {
            while (connected.get()) {
                try {
                    NetworkChannel.ReceivedMessage message = channel.receive();
                    handleServerMessage(message);
                } catch (IOException e) {
                    if (connected.get()) {
                        boolean closed = NetworkChannel.isConnectionClosed(e);
                        shutdown(closed ? "连接已断开" : e.getMessage());
                        if (closed) {
                            logWarn("接收通道断开");
                        } else {
                            logError(AppLogger.formatNetworkError("接收数据失败", e));
                        }
                    }
                    break;
                }
            }
        }
    }

    /** 按协议类型分发：全帧、差分帧、心跳、断开。 */
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
                logWarn("被控端主动断开连接");
                shutdown("被控端已断开");
                break;
            default:
                break;
        }
    }

    private void notifyFrame(BufferedImage image) {
        if (firstFrameLogged.compareAndSet(false, true)) {
            logInfo("收到首帧画面 " + image.getWidth() + "x" + image.getHeight());
        }
        recordFps();
        listener.onFrameUpdated(image);
    }

    /** 滑动 1 秒窗口统计接收帧率。 */
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

    /** 定时发送心跳，维持连接存活。 */
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

    /** 统一关闭连接：停线程、清队列、重置解码器并通知 UI。 */
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
        if ("已断开连接".equals(reason)) {
            logInfo(reason);
        } else if ("连接失败".equals(reason)) {
            // 详细错误已在连接阶段记录
        } else {
            logWarn("连接结束：" + reason);
        }
        listener.onDisconnected(reason);
        listener.onStatusChanged(reason);
        listener.onFpsUpdated(0);
    }

    private void logInfo(String message) {
        listener.onLog(AppLogger.Level.INFO, message);
    }

    private void logWarn(String message) {
        listener.onLog(AppLogger.Level.WARN, message);
    }

    private void logError(String message) {
        listener.onLog(AppLogger.Level.ERROR, message);
    }

    private void bindRelayStepLogger() {
        RelayConnector.bindStepLogger(new RelayConnector.StepLogger() {
            @Override
            public void log(AppLogger.Level level, String message) {
                listener.onLog(level, message);
            }
        });
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

    /** 待发送的协议消息封装。 */
    private static final class OutboundMessage {
        private final MessageType type;
        private final byte[] payload;
        private OutboundMessage(MessageType type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }
}
