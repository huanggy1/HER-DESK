package com.herdesk.common;

/**
 * 中继房间密码校验与匹配（默认密码临时写死，后续可改为配置）。
 */
public final class RelayAuth {

    /** 默认房间密码 */
    public static final String DEFAULT_PASSWORD = "123456";

    private RelayAuth() {
    }

    /**
     * 校验密码非空且长度不超过 64。
     *
     * @throws java.io.IOException 校验失败
     */
    public static void validatePassword(String password) throws java.io.IOException {
        if (password == null || password.trim().isEmpty()) {
            throw new java.io.IOException("房间密码不能为空");
        }
        if (password.length() > 64) {
            throw new java.io.IOException("房间密码最长 64 个字符");
        }
    }

    /** 判断密码是否与默认密码一致 */
    public static boolean matches(String password) {
        return DEFAULT_PASSWORD.equals(password);
    }
}
