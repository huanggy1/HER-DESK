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

    /** 默认中继 TCP 端口 */
    public static final int DEFAULT_RELAY_PORT = 9000;
    /** 房间号最大长度 */
    public static final int MAX_ROOM_ID_LENGTH = 32;

    /**
     * 可选的步骤日志，由调用方通过 {@link #bindStepLogger(StepLogger)} 注入。
     */
    public interface StepLogger {
        void log(AppLogger.Level level, String message);
    }

    /** 当前线程的步骤日志回调 */
    private static final ThreadLocal<StepLogger> STEP_LOGGER = new ThreadLocal<StepLogger>();

    private RelayConnector() {
    }

    /** 绑定当前线程的步骤日志器 */
    public static void bindStepLogger(StepLogger logger) {
        STEP_LOGGER.set(logger);
    }

    /** 解除当前线程的步骤日志器 */
    public static void unbindStepLogger() {
        STEP_LOGGER.remove();
    }

    /** 若已绑定则输出步骤日志 */
    private static void step(AppLogger.Level level, String message) {
        StepLogger logger = STEP_LOGGER.get();
        if (logger != null) {
            logger.log(level, message);
        }
    }

    /**
     * 被控端：注册房间并等待控制端接入。
     * <p>
     * 期望响应 {@code OK WAITING}；否则抛出带友好文案的 IOException。
     */
    public static Socket registerServer(String relayHost, int relayPort, String roomId, String password)
            throws IOException {
        validateRoomId(roomId);
        RelayAuth.validatePassword(password);
        step(AppLogger.Level.INFO, "校验房间号通过: " + roomId);
        Socket socket = createSocket(relayHost, relayPort);
        try {
            step(AppLogger.Level.INFO, "发送 REGISTER，房间号 " + roomId);
            sendLine(socket, "REGISTER " + roomId + " " + password);
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
     * <p>
     * 期望响应 {@code OK CONNECTED}；否则抛出带友好文案的 IOException。
     */
    public static Socket joinClient(String relayHost, int relayPort, String roomId, String password)
            throws IOException {
        validateRoomId(roomId);
        RelayAuth.validatePassword(password);
        step(AppLogger.Level.INFO, "校验房间号通过: " + roomId);
        Socket socket = createSocket(relayHost, relayPort);
        try {
            step(AppLogger.Level.INFO, "发送 JOIN，房间号 " + roomId);
            sendLine(socket, "JOIN " + roomId + " " + password);
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

    /**
     * 校验房间号：非空、长度、仅允许字母数字及 {@code -_}。
     */
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

    /**
     * 从中继输入流读取一行（UTF-8，忽略 {@code \r}）。
     * <p>
     * 连接关闭且无数据时抛出 IOException。
     */
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

    /** 向中继发送一行命令（末尾追加 {@code \n}） */
    public static void sendLine(Socket socket, String line) throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /** 建立 TCP 连接，超时见 {@link AppLogger#CONNECT_TIMEOUT_MS} */
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

    /** 将中继错误响应转为用户可读文案 */
    private static String formatRelayFailure(String prefix, String response) {
        if ("ERROR ROOM_NOT_FOUND".equals(response)) {
            return prefix + "：房间未找到（被控端可能未注册或房间号不一致）";
        }
        if ("ERROR INVALID_PASSWORD".equals(response)) {
            return prefix + "：房间密码错误";
        }
        return prefix + "：" + response;
    }

    /**
     * 解析 REGISTER/JOIN 命令参数：房间号 + 密码。
     *
     * @return [房间号, 密码]
     */
    public static String[] parseRoomAndPassword(String commandLine) throws IOException {
        if (commandLine == null || commandLine.trim().isEmpty()) {
            throw new IOException("命令不能为空");
        }
        String[] parts = commandLine.trim().split("\\s+");
        if (parts.length < 2) {
            throw new IOException("命令格式无效，需要：房间号 密码");
        }
        return new String[]{parts[0], parts[1]};
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
