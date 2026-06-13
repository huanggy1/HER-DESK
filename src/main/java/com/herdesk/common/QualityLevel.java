package com.herdesk.common;

/**
 * 画质档位：高清晰 / 均衡 / 流畅。
 */
public enum QualityLevel {
    HIGH(0, 0.85f, "高清晰"),
    BALANCED(1, 0.70f, "均衡"),
    SMOOTH(2, 0.50f, "流畅");

    private final int code;
    private final float jpegQuality;
    private final String label;

    QualityLevel(int code, float jpegQuality, String label) {
        this.code = code;
        this.jpegQuality = jpegQuality;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public float getJpegQuality() {
        return jpegQuality;
    }

    public String getLabel() {
        return label;
    }

    public static QualityLevel fromCode(int code) {
        for (QualityLevel level : values()) {
            if (level.code == code) {
                return level;
            }
        }
        return BALANCED;
    }
}
