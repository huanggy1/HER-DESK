package com.herdesk.common;

/**
 * 连接模式：内网直连或公网中继。
 */
public enum ConnectionMode {
    /** 同一局域网内 TCP 直连 */
    DIRECT("内网直连"),
    /** 经公网中继服务器转发 */
    RELAY("公网中继");

    /** UI 展示文案 */
    private final String label;

    ConnectionMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
