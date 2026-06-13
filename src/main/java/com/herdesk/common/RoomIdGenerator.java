package com.herdesk.common;

import java.security.SecureRandom;

/**
 * 随机房间号生成器。
 */
public final class RoomIdGenerator {

    private static final char[] CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int DEFAULT_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RoomIdGenerator() {
    }

    public static String generate() {
        return generate(DEFAULT_LENGTH);
    }

    public static String generate(int length) {
        int safeLength = Math.max(4, Math.min(length, RelayConnector.MAX_ROOM_ID_LENGTH));
        StringBuilder builder = new StringBuilder(safeLength);
        for (int i = 0; i < safeLength; i++) {
            builder.append(CHARS[RANDOM.nextInt(CHARS.length)]);
        }
        return builder.toString();
    }
}
