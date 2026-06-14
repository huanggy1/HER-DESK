package com.herdesk.relay;

import com.herdesk.common.AppLogger;
import com.herdesk.common.RelayAuth;
import com.herdesk.common.RelayConnector;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 公网 TCP 中继服务。
 * <p>
 * 被控端通过 REGISTER 注册房间，控制端通过 JOIN 加入；
 * 配对成功后双向透明转发字节流。
 */
public class RelayServer {

    /** 监听端口。 */
    private final int port;
    /** 已注册、等待 JOIN 的被控端连接，key 为房间号。 */
    private final Map<String, Socket> waitingRooms = new ConcurrentHashMap<String, Socket>();
    /** 房间号对应的密码，用于 JOIN 校验。 */
    private final Map<String, String> roomPasswords = new ConcurrentHashMap<String, String>();
    /** 每连接一个处理任务的线程池。 */
    private final ExecutorService executor = Executors.newCachedThreadPool();
    /** 服务是否运行中。 */
    private volatile boolean running;

    public RelayServer(int port) {
        this.port = port;
    }

    /**
     * 启动中继：监听端口，为每个入站连接异步分发处理。
     */
    public void start() throws IOException {
        running = true;
        ServerSocket serverSocket = new ServerSocket(port);
        AppLogger.console(AppLogger.Level.INFO, "Her Desk Relay 已启动，监听端口 " + port);
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        handleConnection(socket);
                    }
                });
            } catch (IOException e) {
                if (running) {
                    AppLogger.console(AppLogger.Level.ERROR,
                            AppLogger.formatNetworkError("接受连接失败", e));
                }
            }
        }
        serverSocket.close();
    }

    /** 停止中继：关闭等待中的房间连接并回收线程池。 */
    public void stop() {
        running = false;
        for (Socket socket : waitingRooms.values()) {
            closeQuietly(socket);
        }
        waitingRooms.clear();
        roomPasswords.clear();
        executor.shutdownNow();
        AppLogger.console(AppLogger.Level.INFO, "Her Desk Relay 已停止");
    }

    /** 读取首行命令并路由到 REGISTER 或 JOIN 处理。 */
    private void handleConnection(Socket socket) {
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        try {
            InputStream input = socket.getInputStream();
            String line = RelayConnector.readLine(input);
            if (line == null || line.trim().isEmpty()) {
                AppLogger.console(AppLogger.Level.WARN, "收到空命令，来源 " + remote);
                sendAndClose(socket, "ERROR EMPTY_COMMAND");
                return;
            }
            AppLogger.console(AppLogger.Level.INFO, "收到连接 " + remote + "，命令: " + line.trim());
            String[] parts = line.trim().split(" ", 2);
            if (parts.length < 2) {
                AppLogger.console(AppLogger.Level.WARN, "无效命令格式，来源 " + remote);
                sendAndClose(socket, "ERROR INVALID_COMMAND");
                return;
            }
            String command = parts[0].toUpperCase();
            String payload = parts[1].trim();
            if ("REGISTER".equals(command)) {
                handleRegister(socket, payload);
            } else if ("JOIN".equals(command)) {
                handleJoin(socket, payload);
            } else {
                AppLogger.console(AppLogger.Level.WARN, "未知命令 " + command + "，来源 " + remote);
                sendAndClose(socket, "ERROR UNKNOWN_COMMAND");
            }
        } catch (Exception e) {
            AppLogger.console(AppLogger.Level.WARN,
                    "连接处理结束 " + remote + "：" + e.getMessage());
            closeQuietly(socket);
        }
    }

    /**
     * 被控端注册房间：校验参数后进入等待队列，回复 OK WAITING。
     */
    private void handleRegister(Socket socket, String payload) throws IOException {
        String roomId;
        String password;
        try {
            String[] roomAndPassword = RelayConnector.parseRoomAndPassword(payload);
            roomId = roomAndPassword[0];
            password = roomAndPassword[1];
            RelayConnector.validateRoomId(roomId);
            RelayAuth.validatePassword(password);
        } catch (IOException e) {
            AppLogger.console(AppLogger.Level.WARN, "REGISTER 失败：" + e.getMessage());
            sendAndClose(socket, "ERROR " + e.getMessage());
            return;
        }
        Socket previous = waitingRooms.remove(roomId);
        if (previous != null) {
            closeQuietly(previous);
            AppLogger.console(AppLogger.Level.INFO, "房间 " + roomId + " 被新注册替换");
        }
        roomPasswords.remove(roomId);
        waitingRooms.put(roomId, socket);
        roomPasswords.put(roomId, password);
        RelayConnector.sendLine(socket, "OK WAITING");
        AppLogger.console(AppLogger.Level.INFO,
                "房间已注册: " + roomId + " <- " + socket.getRemoteSocketAddress());
    }

    /**
     * 控制端加入房间：校验密码后与被控端配对，回复 OK CONNECTED 并桥接。
     */
    private void handleJoin(Socket clientSocket, String payload) throws IOException {
        String roomId;
        String password;
        try {
            String[] roomAndPassword = RelayConnector.parseRoomAndPassword(payload);
            roomId = roomAndPassword[0];
            password = roomAndPassword[1];
            RelayConnector.validateRoomId(roomId);
            RelayAuth.validatePassword(password);
        } catch (IOException e) {
            AppLogger.console(AppLogger.Level.WARN, "JOIN 失败：" + e.getMessage());
            sendAndClose(clientSocket, "ERROR " + e.getMessage());
            return;
        }
        String expectedPassword = roomPasswords.get(roomId);
        Socket serverSocket = waitingRooms.get(roomId);
        if (serverSocket == null || serverSocket.isClosed()) {
            AppLogger.console(AppLogger.Level.WARN,
                    "JOIN 失败，房间未找到: " + roomId + " <- " + clientSocket.getRemoteSocketAddress());
            sendAndClose(clientSocket, "ERROR ROOM_NOT_FOUND");
            return;
        }
        if (expectedPassword == null || !expectedPassword.equals(password)) {
            AppLogger.console(AppLogger.Level.WARN,
                    "JOIN 失败，密码错误: " + roomId + " <- " + clientSocket.getRemoteSocketAddress());
            sendAndClose(clientSocket, "ERROR INVALID_PASSWORD");
            return;
        }
        waitingRooms.remove(roomId);
        roomPasswords.remove(roomId);
        RelayConnector.sendLine(clientSocket, "OK CONNECTED");
        AppLogger.console(AppLogger.Level.INFO,
                "房间已配对: " + roomId + "，控制端 " + clientSocket.getRemoteSocketAddress()
                        + " <-> 被控端 " + serverSocket.getRemoteSocketAddress());
        bridge(serverSocket, clientSocket, roomId);
    }

    /** 启动双向转发线程，任一端结束后关闭两端套接字。 */
    private void bridge(final Socket serverSocket, final Socket clientSocket, final String roomId) {
        Thread serverToClient = new Thread(new Runnable() {
            @Override
            public void run() {
                pump(serverSocket, clientSocket);
            }
        }, "relay-pump-server-client");
        Thread clientToServer = new Thread(new Runnable() {
            @Override
            public void run() {
                pump(clientSocket, serverSocket);
            }
        }, "relay-pump-client-server");
        serverToClient.setDaemon(true);
        clientToServer.setDaemon(true);
        serverToClient.start();
        clientToServer.start();
        try {
            serverToClient.join();
            clientToServer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(serverSocket);
            closeQuietly(clientSocket);
            AppLogger.console(AppLogger.Level.INFO, "房间会话结束: " + roomId);
        }
    }

    /** 从源套接字读取并写入目标套接字，直到 EOF 或异常。 */
    private void pump(Socket from, Socket to) {
        byte[] buffer = new byte[32768];
        try {
            InputStream input = from.getInputStream();
            OutputStream output = to.getOutputStream();
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
                output.flush();
            }
        } catch (IOException ignored) {
            // 一端断开，隧道关闭
        } finally {
            AppLogger.console(AppLogger.Level.INFO,
                    "转发隧道关闭 " + from.getRemoteSocketAddress() + " -> " + to.getRemoteSocketAddress());
        }
    }

    private void sendAndClose(Socket socket, String message) {
        try {
            RelayConnector.sendLine(socket, message);
        } catch (IOException ignored) {
            // 忽略
        } finally {
            closeQuietly(socket);
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
