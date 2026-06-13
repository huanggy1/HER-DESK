package com.herdesk.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 连接公网中继服务器：被控端 REGISTER，控制端 JOIN，之后透明转发 HDRD 协议。
 */
public final class RelayConnector {

    public static final int DEFAULT_RELAY_PORT = 9000;
    public static final int MAX_ROOM_ID_LENGTH = 32;

    /**
     * 可选的步骤日志，由调用方通过 {@link #bindStepLogger(StepLogger)} 注入。
     */
    public interface StepLogger {
        void log(AppLogger.Level level, String message);
    }

    private static final ThreadLocal<StepLogger> STEP_LOGGER = new ThreadLocal<StepLogger>();

    private RelayConnector() {
    }

    public static void bindStepLogger(StepLogger logger) {
        STEP_LOGGER.set(logger);
    }

    public static void unbindStepLogger() {
        STEP_LOGGER.remove();
    }

    private static void step(AppLogger.Level level, String message) {
        StepLogger logger = STEP_LOGGER.get();
        if (logger != null) {
            logger.log(level, message);
        }
    }

    /**
     * 被控端：注册房间并等待控制端接入。
     */
    public static Socket registerServer(String relayHost, int relayPort, String roomId) throws IOException {
        validateRoomId(roomId);
        step(AppLogger.Level.INFO, "校验房间号通过: " + roomId);
        Socket socket = createSocket(relayHost, relayPort);
        try {
            step(AppLogger.Level.INFO, "发送 REGISTER，房间号 " + roomId);
            sendLine(socket, "REGISTER " + roomId);
            String response = readLine(socket.getInputStream());
            step(AppLogger.Level.INFO, "收到中继响应: " + response);
            if (!"OK WAITING".equals(response)) {
                throw new IOException(formatRelayFailure("中继注册失败", response));
            }
            step(AppLogger.Level.INFO, "中继注册成功，等待控制端 JOIN");
            return socket;
        } catch (IOException e) {
            step(AppLogger.Level.ERROR, "中继注册中断: " + e.getMessage());
            closeQuietly(socket);
            throw e;
        }
    }

    /**
     * 控制端：加入房间并与被控端建立透明隧道。
     */
    public static Socket joinClient(String relayHost, int relayPort, String roomId) throws IOException {
        validateRoomId(roomId);
        step(AppLogger.Level.INFO, "校验房间号通过: " + roomId);
        Socket socket = createSocket(relayHost, relayPort);
        try {
            step(AppLogger.Level.INFO, "发送 JOIN，房间号 " + roomId);
            sendLine(socket, "JOIN " + roomId);
            String response = readLine(socket.getInputStream());
            step(AppLogger.Level.INFO, "收到中继响应: " + response);
            if (!"OK CONNECTED".equals(response)) {
                throw new IOException(formatRelayFailure("中继加入失败", response));
            }
            step(AppLogger.Level.INFO, "中继配对成功，隧道已建立");
            return socket;
        } catch (IOException e) {
            step(AppLogger.Level.ERROR, "中继加入中断: " + e.getMessage());
            closeQuietly(socket);
            throw e;
        }
    }

    public static void validateRoomId(String roomId) throws IOException {
        if (roomId == null || roomId.trim().isEmpty()) {
            throw new IOException("房间号不能为空");
        }
        String trimmed = roomId.trim();
        if (trimmed.length() > MAX_ROOM_ID_LENGTH) {
            throw new IOException("房间号最长 " + MAX_ROOM_ID_LENGTH + " 个字符");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '-' && ch != '_') {
                throw new IOException("房间号仅允许字母、数字、-、_");
            }
        }
    }

    public static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int data;
        while ((data = input.read()) != -1) {
            if (data == '\n') {
                break;
            }
            if (data != '\r') {
                buffer.write(data);
            }
        }
        if (data == -1 && buffer.size() == 0) {
            throw new IOException("中继连接已关闭（未收到任何响应数据）");
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void sendLine(Socket socket, String line) throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static Socket createSocket(String host, int port) throws IOException {
        int timeoutSec = AppLogger.CONNECT_TIMEOUT_MS / 1000;
        step(AppLogger.Level.INFO,
                "正在连接中继 TCP " + host + ":" + port + "（超时 " + timeoutSec + " 秒）");
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), AppLogger.CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            step(AppLogger.Level.ERROR, "TCP 连接失败 " + host + ":" + port + "：" + e.getMessage());
            throw e;
        }
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        step(AppLogger.Level.INFO,
                "TCP 连接成功 " + socket.getLocalSocketAddress() + " -> " + socket.getRemoteSocketAddress());
        return socket;
    }

    private static String formatRelayFailure(String prefix, String response) {
        if ("ERROR ROOM_NOT_FOUND".equals(response)) {
            return prefix + "：房间未找到（被控端可能未注册或房间号不一致）";
        }
        return prefix + "：" + response;
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
