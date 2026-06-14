package com.herdesk.common;

/**
 * 通信协议常量与工具方法。
 * <p>
 * 帧格式：魔数(4) + 类型(1) + 长度(4) + 载荷(N)，大端序。
 */
public final class Protocol {

    /** 协议魔数，ASCII "HDRD" */
    public static final int MAGIC = 0x48445244; // "HDRD"
    /** 帧头固定长度（字节） */
    public static final int HEADER_SIZE = 9;
    /** 默认 TCP 监听端口 */
    public static final int DEFAULT_PORT = 5900;
    /** 差分编码区块边长（像素） */
    public static final int BLOCK_SIZE = 96;

    /** 鼠标左键标识 */
    public static final int MOUSE_LEFT = 1;
    /** 鼠标右键标识 */
    public static final int MOUSE_RIGHT = 2;
    /** 鼠标中键标识 */
    public static final int MOUSE_MIDDLE = 3;

    /** 最快截图间隔（约 50fps） */
    public static final int MIN_CAPTURE_INTERVAL_MS = 20;
    /** 静止画面最长截图间隔 */
    public static final int MAX_CAPTURE_INTERVAL_MS = 200;
    /** 键鼠操作后维持高帧率的时长 */
    public static final int INPUT_BOOST_DURATION_MS = 8000;
    /** 强制全帧刷新前的差分帧数 */
    public static final int FULL_FRAME_RESET_COUNT = 300;
    /** 心跳发送间隔 */
    public static final int HEARTBEAT_INTERVAL_MS = 3000;

    private Protocol() {
    }
}
