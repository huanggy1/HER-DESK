package com.herdesk.common;

/**
 * 画质档位：高清晰 / 均衡 / 流畅。
 */
public enum QualityLevel {
    /** 高清晰，JPEG 质量 0.85 */
    HIGH(0, 0.85f, "高清晰"),
    /** 均衡，JPEG 质量 0.70 */
    BALANCED(1, 0.70f, "均衡"),
    /** 流畅，JPEG 质量 0.50 */
    SMOOTH(2, 0.50f, "流畅");

    /** 协议档位码 */
    private final int code;
    /** JPEG 压缩质量（0~1） */
    private final float jpegQuality;
    /** UI 展示文案 */
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

    /**
     * 按档位码解析；未知码回退为 {@link #BALANCED}。
     */
    public static QualityLevel fromCode(int code) {
        for (QualityLevel level : values()) {
            if (level.code == code) {
                return level;
            }
        }
        return BALANCED;
    }
}
