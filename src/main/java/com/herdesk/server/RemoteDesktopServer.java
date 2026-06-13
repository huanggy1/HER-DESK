package com.herdesk.server;

import com.herdesk.common.DeltaFrameEncoder;
import com.herdesk.common.MessageType;
import com.herdesk.common.NetworkChannel;
import com.herdesk.common.PayloadCodec;
import com.herdesk.common.Protocol;
import com.herdesk.common.QualityLevel;
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
    }

    private final int port;
    private final ServerListener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean clientConnected = new AtomicBoolean(false);
    private final AtomicInteger captureIntervalMs = new AtomicInteger(100);
    private final AtomicLong lastFrameTime = new AtomicLong(0);
    private final AtomicInteger framesInWindow = new AtomicInteger(0);
    private final AtomicInteger displayedFps = new AtomicInteger(0);
    private final AtomicLong fpsWindowStart = new AtomicLong(System.currentTimeMillis());

    private Thread acceptThread;
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

    public RemoteDesktopServer(int port, ServerListener listener) {
        this.port = port;
        this.listener = listener;
        this.frameEncoder = new DeltaFrameEncoder(Protocol.BLOCK_SIZE);
        this.frameEncoder.setJpegQuality(qualityLevel.getJpegQuality());
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
            serverSocket = new java.net.ServerSocket(port);
            running.set(true);
            listener.onStatusChanged("服务已启动，等待连接...");
            acceptThread = new Thread(new AcceptLoop(), "server-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (Exception e) {
            running.set(false);
            listener.onError("启动失败: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (!running.get()) {
            return;
        }
        running.set(false);
        disconnectClient("服务已停止");
        closeServerSocket();
        joinThread(acceptThread);
        joinThread(captureThread);
        joinThread(heartbeatThread);
        joinThread(receiveThread);
        listener.onStatusChanged("服务已停止");
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
                        continue;
                    }
                    handleNewClient(socket);
                } catch (IOException e) {
                    if (running.get()) {
                        listener.onError("接受连接异常: " + e.getMessage());
                    }
                    break;
                }
            }
        }
    }

    private void handleNewClient(java.net.Socket socket) {
        try {
            clientChannel = new NetworkChannel(socket);
            clientConnected.set(true);
            frameEncoder.reset();
            captureIntervalMs.set(100);
            framesInWindow.set(0);
            displayedFps.set(0);
            fpsWindowStart.set(System.currentTimeMillis());
            listener.onClientConnected(socket.getRemoteSocketAddress().toString());
            listener.onStatusChanged("客户端已连接");

            NetworkChannel.ReceivedMessage handshake = clientChannel.receive();
            if (handshake.getType() != MessageType.HANDSHAKE) {
                throw new IOException("握手失败，收到: " + handshake.getType());
            }

            Robot robot = new Robot();
            screenGeometry = ScreenCaptureHelper.detect(robot);
            inputExecutor.setScreenGeometry(screenGeometry);
            byte[] response = PayloadCodec.encodeHandshakeResponse(screenGeometry);
            clientChannel.send(MessageType.HANDSHAKE, response);

            captureThread = new Thread(new CaptureLoop(), "server-capture");
            captureThread.setDaemon(true);
            captureThread.start();

            heartbeatThread = new Thread(new HeartbeatLoop(), "server-heartbeat");
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();

            receiveThread = new Thread(new InputReceiveLoop(), "server-receive");
            receiveThread.setDaemon(true);
            receiveThread.start();
        } catch (Exception e) {
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
                listener.onError("截图初始化失败: " + e.getMessage());
                disconnectClient("截图失败");
                return;
            }
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
                        disconnectClient("客户端断开");
                    } else {
                        disconnectClient("发送画面失败: " + e.getMessage());
                    }
                    break;
                } catch (Exception e) {
                    disconnectClient("截图异常: " + e.getMessage());
                    break;
                }
            }
        }
    }

    private void sendEncodedFrame(DeltaFrameEncoder.EncodedFrame encoded) throws IOException {
        if (encoded.isFullFrame()) {
            byte[] payload = PayloadCodec.encodeFullFrame(
                    encoded.getWidth(),
                    encoded.getHeight(),
                    encoded.getFullJpeg()
            );
            clientChannel.send(MessageType.FULL_FRAME, payload);
        } else {
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
                            disconnectClient("客户端断开");
                        } else {
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
                break;
            case DISCONNECT:
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
        if (running.get()) {
            listener.onStatusChanged("等待连接...");
        }
    }
}
