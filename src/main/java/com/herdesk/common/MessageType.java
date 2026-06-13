package com.herdesk.common;

/**
 * TCP 协议消息类型。
 */
public enum MessageType {
    HANDSHAKE(1),
    FULL_FRAME(2),
    DELTA_FRAME(3),
    MOUSE_MOVE(4),
    MOUSE_BUTTON(5),
    MOUSE_WHEEL(6),
    KEY_EVENT(7),
    QUALITY(8),
    HEARTBEAT(9),
    DISCONNECT(10);

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static MessageType fromCode(int code) {
        for (MessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知消息类型: " + code);
    }
}
