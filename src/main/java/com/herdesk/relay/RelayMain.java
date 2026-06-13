package com.herdesk.relay;

import com.herdesk.common.AppLogger;
import com.herdesk.common.RelayConnector;

/**
 * 公网中继服务入口。
 * <p>
 * 用法：java -jar her-desk-relay.jar [端口]
 * 默认端口 9000。
 */
public class RelayMain {

    public static void main(String[] args) {
        int port = RelayConnector.DEFAULT_RELAY_PORT;
        if (args != null && args.length > 0) {
            try {
                port = Integer.parseInt(args[0].trim());
            } catch (NumberFormatException e) {
                AppLogger.console(AppLogger.Level.ERROR, "端口无效: " + args[0]);
                System.exit(1);
            }
        }
        AppLogger.console(AppLogger.Level.INFO,
                "启动 Her Desk Relay，端口 " + port + "，连接超时 " + (AppLogger.CONNECT_TIMEOUT_MS / 1000) + " 秒（客户端侧）");
        RelayServer relayServer = new RelayServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                relayServer.stop();
            }
        }));
        try {
            relayServer.start();
        } catch (Exception e) {
            AppLogger.console(AppLogger.Level.ERROR,
                    AppLogger.formatNetworkError("中继服务启动失败", e));
            System.exit(1);
        }
    }
}
