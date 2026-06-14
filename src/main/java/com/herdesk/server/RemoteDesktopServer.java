package com.herdesk.server;

import com.herdesk.common.AppLogger;
import com.herdesk.common.DeltaFrameEncoder;
import com.herdesk.common.MessageType;
import com.herdesk.common.NetworkChannel;
import com.herdesk.common.PayloadCodec;
import com.herdesk.common.Protocol;
import com.herdesk.common.QualityLevel;
import com.herdesk.common.RelayConnector;
import com.herdesk.common.ScreenCaptureHelper;
import com.herdesk.common.ScreenGeometry;
import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 被控端核心服务。
 * <p>
 * 支持直连与中继两种模式：接受控制端连接、握手后循环截图差分发送，
 * 并接收远程键鼠指令交由 {@link InputExecutor} 执行。
 */
public class RemoteDesktopServer {

    /**
     * 服务状态回调，供 UI 层更新界面与日志。
     */
    public interface ServerListener {

        /** 服务状态文案变更（如等待连接、已注册等）。 */
        void onStatusChanged(String status);

        /** 控制端连接成功。 */
        void onClientConnected(String remoteAddress);

        /** 控制端断开，附带原因。 */
        void onClientDisconnected(String reason);

        /** 不可恢复或需提示的错误。 */
        void onError(String message);

        /** 结构化日志输出。 */
        void onLog(AppLogger.Level level, String message);
    }

    // ---- 连接配置 ----
    /** 直连模式监听端口；中继模式下为 0。 */
    private final int port;
    /** 是否通过公网中继注册房间。 */
    private final boolean relayMode;
    /** 中继服务器地址。 */
    private final String relayHost;
    /** 中继服务器端口。 */
    private final int relayPort;
    /** 中继房间号。 */
    private final String roomId;
    /** 中继房间密码。 */
    private final String roomPassword;
    /** UI/日志回调。 */
    private final ServerListener listener;

    // ---- 运行状态 ----
    /** 服务是否已启动。 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 当前是否有控制端连接。 */
    private final AtomicBoolean clientConnected = new AtomicBoolean(false);

    // ---- 截图与帧率 ----
    /** 自适应截图间隔（毫秒）。 */
    private final AtomicInteger captureIntervalMs = new AtomicInteger(100);
    /** 上一帧发送时间戳。 */
    private final AtomicLong lastFrameTime = new AtomicLong(0);
    /** 当前 1 秒窗口内已发送帧数。 */
    private final AtomicInteger framesInWindow = new AtomicInteger(0);
    /** 对外展示的 FPS。 */
    private final AtomicInteger displayedFps = new AtomicInteger(0);
    /** FPS 统计窗口起始时间。 */
    private final AtomicLong fpsWindowStart = new AtomicLong(System.currentTimeMillis());
    /** 键鼠活动后请求加速截图。 */
    private final AtomicBoolean captureRequested = new AtomicBoolean(false);
    /** 最近一次键鼠输入时间。 */
    private final AtomicLong lastInputTime = new AtomicLong(0);
    /** 距上次全帧重置以来已发送帧数。 */
    private int framesSinceFullFrame = 0;
    /** 是否已记录首帧日志（避免重复打印）。 */
    private final AtomicBoolean firstFrameLogged = new AtomicBoolean(false);

    // ---- 工作线程 ----
    private Thread acceptThread;
    private Thread relayThread;
    private Thread captureThread;
    private Thread heartbeatThread;
    private Thread receiveThread;

    // ---- 网络与执行 ----
    private java.net.ServerSocket serverSocket;
    private NetworkChannel clientChannel;
    private InputExecutor inputExecutor;
    private DeltaFrameEncoder frameEncoder;
    /** 当前 JPEG 画质档位。 */
    private volatile QualityLevel qualityLevel = QualityLevel.BALANCED;
    /** 本机屏幕几何信息（握手后确定）。 */
    private volatile ScreenGeometry screenGeometry;

    public RemoteDesktopServer(int port, ServerListener listener) {
        this(port, false, null, 0, null, null, listener);
    }

    public RemoteDesktopServer(String relayHost, int relayPort, String roomId, String roomPassword,
                               ServerListener listener) {
        this(0, true, relayHost, relayPort, roomId, roomPassword, listener);
    }

    private RemoteDesktopServer(int port, boolean relayMode, String relayHost,
                                int relayPort, String roomId, String roomPassword, ServerListener listener) {
        this.port = port;
        this.relayMode = relayMode;
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.roomId = roomId;
        this.roomPassword = roomPassword;
        this.listener = listener;
        this.frameEncoder = new DeltaFrameEncoder(Protocol.BLOCK_SIZE);
        this.frameEncoder.setJpegQuality(qualityLevel.getJpegQuality());
    }

    public boolean isRelayMode() {
        return relayMode;
    }

    public String getRoomId() {
        return roomId;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isClientConnected() {
        return clientConnected.get();
    }

    public int getCurrentFps() {
        return displayedFps.get();
    }

    /**
     * 启动服务：直连模式监听端口，中继模式注册房间并等待 JOIN。
     */
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        try {
            inputExecutor = new InputExecutor();
            running.set(true);
            if (relayMode) {
                logInfo("启动中继模式，目标 " + relayHost + ":" + relayPort + "，房间 " + roomId);
                listener.onStatusChanged("正在连接中继 " + relayHost + ":" + relayPort + " ...");
                relayThread = new Thread(new RelayLoop(), "server-relay");
                relayThread.setDaemon(true);
                relayThread.start();
            } else {
                serverSocket = new java.net.ServerSocket(port);
                logInfo("直连模式已启动，监听端口 " + port);
                listener.onStatusChanged("服务已启动，等待连接...");
                acceptThread = new Thread(new AcceptLoop(), "server-accept");
                acceptThread.setDaemon(true);
                acceptThread.start();
            }
        } catch (Exception e) {
            running.set(false);
            String error = AppLogger.formatNetworkError("启动服务失败", e);
            logError(error);
            listener.onError(error);
        }
    }

    /**
     * 停止服务：断开客户端、关闭套接字并回收所有工作线程。
     */
    public synchronized void stop() {
        if (!running.get()) {
            return;
        }
        logInfo("正在停止服务");
        running.set(false);
        disconnectClient("服务已停止");
        closeServerSocket();
        joinThread(acceptThread);
        joinThread(relayThread);
        joinThread(captureThread);
        joinThread(heartbeatThread);
        joinThread(receiveThread);
        listener.onStatusChanged("服务已停止");
    }

    /** 中继模式：注册房间、等待控制端、断线后自动重连。 */
    private class RelayLoop implements Runnable {
        @Override
        public void run() {
            while (running.get()) {
                bindRelayStepLogger();
                try {
                    logInfo("向中继 " + relayHost + ":" + relayPort + " 注册房间 " + roomId);
                    listener.onStatusChanged("注册房间 " + roomId + " 到中继...");
                    java.net.Socket socket = RelayConnector.registerServer(
                            relayHost, relayPort, roomId, roomPassword);
                    logInfo("中继注册成功，等待控制端 JOIN");
                    listener.onStatusChanged("已注册，房间号: " + roomId + "，等待控制端连接");
                    handleNewClient(socket);
                    while (running.get() && clientConnected.get()) {
                        Thread.sleep(200L);
                    }
                    if (!running.get()) {
                        break;
                    }
                    logInfo("控制端已断开，1 秒后重新注册");
                    listener.onStatusChanged("控制端已断开，准备重新注册...");
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (running.get()) {
                        String error = AppLogger.formatNetworkError(
                                "连接中继 " + relayHost + ":" + relayPort, e);
                        logError(error);
                        listener.onError(error);
                        logWarn("3 秒后重试连接中继");
                        try {
                            Thread.sleep(3000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } finally {
                    RelayConnector.unbindStepLogger();
                }
            }
        }
    }

    private void closeServerSocket() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // 忽略
            }
            serverSocket = null;
        }
    }

    private void joinThread(Thread thread) {
        if (thread != null) {
            try {
                thread.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 直连模式：循环 accept，单客户端占用时拒绝新连接。 */
    private class AcceptLoop implements Runnable {
        @Override
        public void run() {
            while (running.get()) {
                try {
                    java.net.Socket socket = serverSocket.accept();
                    logInfo("收到入站连接：" + socket.getRemoteSocketAddress());
                    if (clientConnected.get()) {
                        try {
                            NetworkChannel rejectChannel = new NetworkChannel(socket);
                            rejectChannel.send(MessageType.DISCONNECT, new byte[0]);
                            rejectChannel.close();
                        } catch (IOException ignored) {
                            try {
                                socket.close();
                            } catch (IOException ignoredClose) {
                                // 忽略
                            }
                        }
                        listener.onStatusChanged("拒绝新连接：已有客户端占用");
                        logWarn("拒绝新连接：已有客户端占用");
                        continue;
                    }
                    handleNewClient(socket);
                } catch (IOException e) {
                    if (running.get()) {
                        String error = AppLogger.formatNetworkError("接受客户端连接失败", e);
                        logError(error);
                        listener.onError(error);
                    }
                    break;
                }
            }
        }
    }

    /**
     * 新控制端接入：握手、探测屏幕、启动截图/心跳/指令接收线程。
     */
    private void handleNewClient(java.net.Socket socket) {
        try {
            firstFrameLogged.set(false);
            clientChannel = new NetworkChannel(socket);
            clientConnected.set(true);
            frameEncoder.reset();
            captureIntervalMs.set(100);
            framesInWindow.set(0);
            displayedFps.set(0);
            fpsWindowStart.set(System.currentTimeMillis());
            listener.onClientConnected(socket.getRemoteSocketAddress().toString());
            logInfo("通道已建立 " + socket.getLocalSocketAddress() + " <-> " + socket.getRemoteSocketAddress());
            listener.onStatusChanged("客户端已连接");

            logInfo("等待控制端 HANDSHAKE...");
            NetworkChannel.ReceivedMessage handshake = clientChannel.receive();
            if (handshake.getType() != MessageType.HANDSHAKE) {
                String detail = "握手失败，收到消息类型: " + handshake.getType();
                logError(detail);
                throw new IOException(detail);
            }
            logInfo("收到控制端 HANDSHAKE");

            Robot robot = new Robot();
            screenGeometry = ScreenCaptureHelper.detect(robot);
            inputExecutor.setScreenGeometry(screenGeometry);
            byte[] response = PayloadCodec.encodeHandshakeResponse(screenGeometry);
            clientChannel.send(MessageType.HANDSHAKE, response);
            logInfo(formatScreenGeometry(screenGeometry));
            logInfo("键鼠执行器已就绪，系统 " + System.getProperty("os.name"));

            captureThread = new Thread(new CaptureLoop(), "server-capture");
            captureThread.setDaemon(true);
            captureThread.start();

            heartbeatThread = new Thread(new HeartbeatLoop(), "server-heartbeat");
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();

            receiveThread = new Thread(new InputReceiveLoop(), "server-receive");
            receiveThread.setDaemon(true);
            receiveThread.start();
            logInfo("截图/心跳/指令接收线程已启动");
        } catch (Exception e) {
            String error = AppLogger.formatNetworkError("处理客户端连接", e);
            logError(error);
            disconnectClient("连接异常: " + e.getMessage());
        }
    }

    /** 截图主循环：捕获屏幕、差分编码、自适应间隔发送。 */
    private class CaptureLoop implements Runnable {
        @Override
        public void run() {
            Robot robot;
            try {
                robot = new Robot();
            } catch (AWTException e) {
                String error = "截图初始化失败: " + e.getMessage();
                logError(error);
                listener.onError(error);
                disconnectClient("截图失败");
                return;
            }
            logInfo("截图循环已启动，画质 " + qualityLevel.name());
            while (running.get() && clientConnected.get()) {
                try {
                    BufferedImage image = ScreenCaptureHelper.capture(robot, screenGeometry);
                    DeltaFrameEncoder.EncodedFrame encoded = frameEncoder.encode(image);
                    if (!encoded.isEmpty()) {
                        sendEncodedFrame(encoded);
                        recordFps();
                        decreaseInterval();
                        framesSinceFullFrame++;
                        if (framesSinceFullFrame >= Protocol.FULL_FRAME_RESET_COUNT) {
                            frameEncoder.reset();
                            framesSinceFullFrame = 0;
                        }
                    } else {
                        increaseInterval();
                    }
                    long sleepMs = resolveSleepInterval();
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    if (NetworkChannel.isConnectionClosed(e)) {
                        logWarn("客户端连接断开");
                        disconnectClient("客户端断开");
                    } else {
                        logError(AppLogger.formatNetworkError("发送画面失败", e));
                        disconnectClient("发送画面失败: " + e.getMessage());
                    }
                    break;
                } catch (Exception e) {
                    logError("截图异常: " + e.getMessage());
                    disconnectClient("截图异常: " + e.getMessage());
                    break;
                }
            }
        }
    }

    /** 将编码结果封装为 FULL_FRAME 或 DELTA_FRAME 消息发送。 */
    private void sendEncodedFrame(DeltaFrameEncoder.EncodedFrame encoded) throws IOException {
        if (encoded.isFullFrame()) {
            if (firstFrameLogged.compareAndSet(false, true)) {
                logInfo("发送首帧 FULL_FRAME " + encoded.getWidth() + "x" + encoded.getHeight());
            }
            byte[] payload = PayloadCodec.encodeFullFrame(
                    encoded.getWidth(),
                    encoded.getHeight(),
                    encoded.getFullJpeg()
            );
            clientChannel.send(MessageType.FULL_FRAME, payload);
        } else {
            if (firstFrameLogged.compareAndSet(false, true)) {
                logInfo("发送首帧 DELTA_FRAME，块数 " + encoded.getPatches().size());
            }
            byte[] payload = PayloadCodec.encodeDeltaFrame(encoded.getPatches());
            clientChannel.send(MessageType.DELTA_FRAME, payload);
        }
    }

    /** 根据键鼠活动与画面变化决定本轮截图休眠时长。 */
    private long resolveSleepInterval() {
        if (captureRequested.getAndSet(false)) {
            return Protocol.MIN_CAPTURE_INTERVAL_MS;
        }
        long sinceInput = System.currentTimeMillis() - lastInputTime.get();
        if (sinceInput < Protocol.INPUT_BOOST_DURATION_MS) {
            return Protocol.MIN_CAPTURE_INTERVAL_MS;
        }
        return captureIntervalMs.get();
    }

    /** 键鼠活动后触发加速截图。 */
    private void notifyInputActivity() {
        lastInputTime.set(System.currentTimeMillis());
        captureRequested.set(true);
        captureIntervalMs.set(Protocol.MIN_CAPTURE_INTERVAL_MS);
    }

    private void recordFps() {
        long now = System.currentTimeMillis();
        long windowStart = fpsWindowStart.get();
        if (now - windowStart >= 1000L) {
            displayedFps.set(framesInWindow.getAndSet(1));
            fpsWindowStart.set(now);
        } else {
            framesInWindow.incrementAndGet();
        }
        lastFrameTime.set(now);
    }

    private void decreaseInterval() {
        int current = captureIntervalMs.get();
        if (current > Protocol.MIN_CAPTURE_INTERVAL_MS) {
            captureIntervalMs.set(Math.max(Protocol.MIN_CAPTURE_INTERVAL_MS, current - 20));
        }
    }

    private void increaseInterval() {
        int current = captureIntervalMs.get();
        if (current < Protocol.MAX_CAPTURE_INTERVAL_MS) {
            captureIntervalMs.set(Math.min(Protocol.MAX_CAPTURE_INTERVAL_MS, current + 50));
        }
    }

    /** 定时向控制端发送心跳，检测连接存活。 */
    private class HeartbeatLoop implements Runnable {
        @Override
        public void run() {
            while (running.get() && clientConnected.get()) {
                try {
                    Thread.sleep(Protocol.HEARTBEAT_INTERVAL_MS);
                    if (clientConnected.get() && clientChannel != null) {
                        clientChannel.send(MessageType.HEARTBEAT, new byte[0]);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    if (clientConnected.get()) {
                        logWarn("心跳失败，客户端可能已断开");
                        disconnectClient("心跳失败");
                    }
                    break;
                }
            }
        }
    }

    /** 循环接收控制端消息并分发处理。 */
    private class InputReceiveLoop implements Runnable {
        @Override
        public void run() {
            while (running.get() && clientConnected.get()) {
                try {
                    NetworkChannel.ReceivedMessage message = clientChannel.receive();
                    handleClientMessage(message);
                } catch (IOException e) {
                    if (clientConnected.get()) {
                        if (NetworkChannel.isConnectionClosed(e)) {
                            logWarn("客户端连接断开");
                            disconnectClient("客户端断开");
                        } else {
                            logError(AppLogger.formatNetworkError("接收控制指令失败", e));
                            disconnectClient("接收指令失败: " + e.getMessage());
                        }
                    }
                    break;
                }
            }
        }
    }

    /**
     * 解析并执行控制端指令：键鼠、画质切换、断开等。
     */
    private void handleClientMessage(NetworkChannel.ReceivedMessage message) throws IOException {
        MessageType type = message.getType();
        byte[] payload = message.getPayload();
        switch (type) {
            case MOUSE_MOVE:
                int[] move = PayloadCodec.decodeMouseMove(payload);
                inputExecutor.handleMouseMove(move[0], move[1]);
                break;
            case MOUSE_BUTTON:
                notifyInputActivity();
                inputExecutor.handleMouseButton(PayloadCodec.decodeMouseButton(payload));
                break;
            case MOUSE_WHEEL:
                int[] wheel = PayloadCodec.decodeMouseWheel(payload);
                notifyInputActivity();
                inputExecutor.handleMouseWheel(wheel[0], wheel[1], wheel[2]);
                break;
            case KEY_EVENT:
                notifyInputActivity();
                inputExecutor.handleKeyEvent(PayloadCodec.decodeKeyEvent(payload));
                break;
            case QUALITY:
                qualityLevel = PayloadCodec.decodeQuality(payload);
                frameEncoder.setJpegQuality(qualityLevel.getJpegQuality());
                logInfo("客户端切换画质为 " + qualityLevel.name());
                break;
            case DISCONNECT:
                logInfo("客户端主动断开");
                disconnectClient("客户端主动断开");
                break;
            case HEARTBEAT:
                break;
            default:
                break;
        }
    }

    /**
     * 清理客户端会话：停止子线程、关闭通道、重置编码器与屏幕状态。
     */
    private synchronized void disconnectClient(String reason) {
        if (!clientConnected.get() && clientChannel == null) {
            return;
        }
        clientConnected.set(false);
        joinThread(captureThread);
        captureThread = null;
        joinThread(heartbeatThread);
        heartbeatThread = null;
        joinThread(receiveThread);
        receiveThread = null;
        if (clientChannel != null) {
            try {
                clientChannel.send(MessageType.DISCONNECT, new byte[0]);
            } catch (IOException ignored) {
                // 忽略
            }
            clientChannel.close();
            clientChannel = null;
        }
        frameEncoder.reset();
        screenGeometry = null;
        if (inputExecutor != null) {
            inputExecutor.setScreenGeometry(null);
        }
        listener.onClientDisconnected(reason);
        logWarn("客户端已断开：" + reason);
        if (running.get()) {
            if (relayMode) {
                listener.onStatusChanged("控制端已断开，等待重新注册...");
            } else {
                listener.onStatusChanged("等待连接...");
            }
        }
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

    private static String formatScreenGeometry(ScreenGeometry geometry) {
        return "屏幕信息：像素 "
                + geometry.getPixelWidth() + "x" + geometry.getPixelHeight()
                + "，逻辑 " + geometry.getLogicalWidth() + "x" + geometry.getLogicalHeight()
                + "，原点 (" + geometry.getOriginX() + "," + geometry.getOriginY() + ")"
                + "，缩放 " + String.format("%.2f", geometry.getScaleX())
                + "x" + String.format("%.2f", geometry.getScaleY());
    }
}
