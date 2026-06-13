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
 * 被控端核心服务：监听连接、截图差分发送、接收并执行键鼠。
 */
public class RemoteDesktopServer {

    public interface ServerListener {
        void onStatusChanged(String status);

        void onClientConnected(String remoteAddress);

        void onClientDisconnected(String reason);

        void onError(String message);

        void onLog(AppLogger.Level level, String message);
    }

    private final int port;
    private final boolean relayMode;
    private final String relayHost;
    private final int relayPort;
    private final String roomId;
    private final String roomPassword;
    private final ServerListener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean clientConnected = new AtomicBoolean(false);
    private final AtomicInteger captureIntervalMs = new AtomicInteger(100);
    private final AtomicLong lastFrameTime = new AtomicLong(0);
    private final AtomicInteger framesInWindow = new AtomicInteger(0);
    private final AtomicInteger displayedFps = new AtomicInteger(0);
    private final AtomicLong fpsWindowStart = new AtomicLong(System.currentTimeMillis());

    private Thread acceptThread;
    private Thread relayThread;
    private Thread captureThread;
    private Thread heartbeatThread;
    private Thread receiveThread;
    private java.net.ServerSocket serverSocket;
    private NetworkChannel clientChannel;
    private InputExecutor inputExecutor;
    private DeltaFrameEncoder frameEncoder;
    private volatile QualityLevel qualityLevel = QualityLevel.BALANCED;
    private volatile ScreenGeometry screenGeometry;
    private final AtomicBoolean captureRequested = new AtomicBoolean(false);
    private final AtomicLong lastInputTime = new AtomicLong(0);
    private int framesSinceFullFrame = 0;
    private final AtomicBoolean firstFrameLogged = new AtomicBoolean(false);

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
