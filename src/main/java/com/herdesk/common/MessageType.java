package com.herdesk.common;

/**
 * TCP 协议消息类型。
 */
public enum MessageType {
    /** 握手请求/响应 */
    HANDSHAKE(1),
    /** 全帧 JPEG 画面 */
    FULL_FRAME(2),
    /** 差分区块画面 */
    DELTA_FRAME(3),
    /** 鼠标移动 */
    MOUSE_MOVE(4),
    /** 鼠标按键 */
    MOUSE_BUTTON(5),
    /** 鼠标滚轮 */
    MOUSE_WHEEL(6),
    /** 键盘事件 */
    KEY_EVENT(7),
    /** 画质档位切换 */
    QUALITY(8),
    /** 心跳保活 */
    HEARTBEAT(9),
    /** 主动断开 */
    DISCONNECT(10);

    /** 协议类型码 */
    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    /** 返回帧头中的类型码 */
    public int getCode() {
        return code;
    }

    /**
     * 按类型码解析枚举。
     *
     * @throws IllegalArgumentException 未知类型码
     */
    public static MessageType fromCode(int code) {
        for (MessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知消息类型: " + code);
    }
}
