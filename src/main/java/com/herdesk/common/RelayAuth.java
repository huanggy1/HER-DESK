package com.herdesk.common;

/**
 * 中继房间密码（临时写死，后续可改为配置）。
 */
public final class RelayAuth {

    public static final String DEFAULT_PASSWORD = "123456";

    private RelayAuth() {
    }

    public static void validatePassword(String password) throws java.io.IOException {
        if (password == null || password.trim().isEmpty()) {
            throw new java.io.IOException("房间密码不能为空");
        }
        if (password.length() > 64) {
            throw new java.io.IOException("房间密码最长 64 个字符");
        }
    }

    public static boolean matches(String password) {
        return DEFAULT_PASSWORD.equals(password);
    }
}
