package com.herdesk.common;

import java.security.SecureRandom;

/**
 * 随机房间号生成器：小写字母 + 数字。
 */
public final class RoomIdGenerator {

    /** 可用字符集 */
    private static final char[] CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    /** 默认生成长度 */
    private static final int DEFAULT_LENGTH = 8;
    /** 安全随机源 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private RoomIdGenerator() {
    }

    /** 生成默认长度（8）的房间号 */
    public static String generate() {
        return generate(DEFAULT_LENGTH);
    }

    /**
     * 生成指定长度的房间号。
     * <p>
     * 长度限制在 4 ~ {@link RelayConnector#MAX_ROOM_ID_LENGTH} 之间。
     */
    public static String generate(int length) {
        int safeLength = Math.max(4, Math.min(length, RelayConnector.MAX_ROOM_ID_LENGTH));
        StringBuilder builder = new StringBuilder(safeLength);
        for (int i = 0; i < safeLength; i++) {
            builder.append(CHARS[RANDOM.nextInt(CHARS.length)]);
        }
        return builder.toString();
    }
}
