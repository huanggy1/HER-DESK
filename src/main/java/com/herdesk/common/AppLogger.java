package com.herdesk.common;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 统一日志格式与网络错误描述。
 */
public final class AppLogger {

    /** TCP 连接超时（毫秒） */
    public static final int CONNECT_TIMEOUT_MS = 15000;

    /** 日志级别 */
    public enum Level {
        /** 一般信息 */
        INFO,
        /** 警告 */
        WARN,
        /** 错误 */
        ERROR
    }

    /** 日志时间格式 */
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

    private AppLogger() {
    }

    /** 格式化单行日志：[时间] [级别] 消息 */
    public static String formatLine(Level level, String message) {
        return "[" + TIME_FORMAT.format(new Date()) + "] [" + level.name() + "] " + message;
    }

    /** 输出到标准输出并 flush */
    public static void console(Level level, String message) {
        System.out.println(formatLine(level, message));
        System.out.flush();
    }

    /**
     * 将网络异常转为用户可读描述。
     *
     * @param context 操作上下文（可为空）
     */
    public static String formatNetworkError(String context, Throwable error) {
        String detail = classifyNetworkCause(error);
        if (context != null && !context.trim().isEmpty()) {
            return context + "：" + detail;
        }
        return detail;
    }

    /**
     * 按异常类型与消息内容分类网络错误原因。
     * <p>
     * 优先匹配业务文案（房间、中继、握手），再匹配超时/拒绝/不可达等系统错误。
     */
    private static String classifyNetworkCause(Throwable error) {
        if (error == null) {
            return "未知网络错误";
        }
        String message = error.getMessage();
        if (message != null) {
            if (message.contains("房间未找到")) {
                return message;
            }
            if (message.contains("房间密码错误")) {
                return message;
            }
            if (message.contains("中继加入失败：房间未找到")) {
                return message;
            }
            if (message.contains("中继连接已关闭")) {
                return "中继连接已关闭（端口可能未开放、防火墙拦截或中继未启动）";
            }
            if (message.contains("握手响应无效") || message.contains("握手失败")) {
                return message;
            }
            if (message.contains("协议魔数不匹配") || message.contains("非法载荷长度")) {
                return message;
            }
        }
        if (error instanceof SocketTimeoutException) {
            return timeoutMessage();
        }
        if (error instanceof ConnectException) {
            if (message != null && isTimeoutMessage(message)) {
                return timeoutMessage();
            }
            if (message != null && message.contains("Connection refused")) {
                return "连接被拒绝（目标未监听或被防火墙拦截）";
            }
            return "连接失败：" + (message != null ? message : "无法建立连接");
        }
        if (message != null) {
            if (isTimeoutMessage(message)) {
                return timeoutMessage();
            }
            if (message.contains("Connection refused")) {
                return "连接被拒绝（目标未监听或被防火墙拦截）";
            }
            if (message.contains("Network is unreachable")) {
                return "网络不可达";
            }
            if (message.contains("No route to host")) {
                return "无法路由到目标主机";
            }
            if (message.contains("Host is down")) {
                return "目标主机不可达";
            }
            return message;
        }
        return error.getClass().getSimpleName();
    }

    private static boolean isTimeoutMessage(String message) {
        return message.contains("timed out")
                || message.contains("Timeout")
                || message.contains("Operation timed out");
    }

    private static String timeoutMessage() {
        int seconds = CONNECT_TIMEOUT_MS / 1000;
        return "连接超时（等待 " + seconds + " 秒无响应，请检查端口/防火墙/中继是否启动）";
    }
}
