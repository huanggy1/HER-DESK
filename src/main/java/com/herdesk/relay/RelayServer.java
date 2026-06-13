package com.herdesk.relay;

import com.herdesk.common.AppLogger;
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
 * 公网 TCP 中继：配对 REGISTER / JOIN 连接并透明转发。
 */
public class RelayServer {

    private final int port;
    private final Map<String, Socket> waitingRooms = new ConcurrentHashMap<String, Socket>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean running;

    public RelayServer(int port) {
        this.port = port;
    }

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

    public void stop() {
        running = false;
        for (Socket socket : waitingRooms.values()) {
            closeQuietly(socket);
        }
        waitingRooms.clear();
        executor.shutdownNow();
        AppLogger.console(AppLogger.Level.INFO, "Her Desk Relay 已停止");
    }

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
            String roomId = parts[1].trim();
            if ("REGISTER".equals(command)) {
                handleRegister(socket, roomId);
            } else if ("JOIN".equals(command)) {
                handleJoin(socket, roomId);
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

    private void handleRegister(Socket socket, String roomId) throws IOException {
        try {
            RelayConnector.validateRoomId(roomId);
        } catch (IOException e) {
            AppLogger.console(AppLogger.Level.WARN, "REGISTER 失败，房间 " + roomId + "：" + e.getMessage());
            sendAndClose(socket, "ERROR " + e.getMessage());
            return;
        }
        Socket previous = waitingRooms.remove(roomId);
        if (previous != null) {
            closeQuietly(previous);
            AppLogger.console(AppLogger.Level.INFO, "房间 " + roomId + " 被新注册替换");
        }
        waitingRooms.put(roomId, socket);
        RelayConnector.sendLine(socket, "OK WAITING");
        AppLogger.console(AppLogger.Level.INFO,
                "房间已注册: " + roomId + " <- " + socket.getRemoteSocketAddress());
    }

    private void handleJoin(Socket clientSocket, String roomId) throws IOException {
        try {
            RelayConnector.validateRoomId(roomId);
        } catch (IOException e) {
            AppLogger.console(AppLogger.Level.WARN, "JOIN 失败，房间 " + roomId + "：" + e.getMessage());
            sendAndClose(clientSocket, "ERROR " + e.getMessage());
            return;
        }
        Socket serverSocket = waitingRooms.remove(roomId);
        if (serverSocket == null || serverSocket.isClosed()) {
            AppLogger.console(AppLogger.Level.WARN,
                    "JOIN 失败，房间未找到: " + roomId + " <- " + clientSocket.getRemoteSocketAddress());
            sendAndClose(clientSocket, "ERROR ROOM_NOT_FOUND");
            return;
        }
        RelayConnector.sendLine(clientSocket, "OK CONNECTED");
        AppLogger.console(AppLogger.Level.INFO,
                "房间已配对: " + roomId + "，控制端 " + clientSocket.getRemoteSocketAddress()
                        + " <-> 被控端 " + serverSocket.getRemoteSocketAddress());
        bridge(serverSocket, clientSocket, roomId);
    }

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
