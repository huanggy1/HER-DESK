package com.herdesk.common;

/**
 * 连接模式：内网直连或公网中继。
 */
public enum ConnectionMode {
    DIRECT("内网直连"),
    RELAY("公网中继");

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
