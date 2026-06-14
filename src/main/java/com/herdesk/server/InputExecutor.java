package com.herdesk.server;

import com.herdesk.common.PayloadCodec;
import com.herdesk.common.Protocol;
import com.herdesk.common.ScreenGeometry;
import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * 在被控端执行远程键鼠指令。
 * <p>
 * 通过 {@link java.awt.Robot} 模拟鼠标移动/按键/滚轮与键盘事件，
 * 并将控制端像素坐标转换为本地屏幕坐标。
 */
public class InputExecutor {

    /** AWT 机器人，用于注入键鼠事件。 */
    private final Robot robot;
    /** 屏幕几何信息，用于像素→屏幕坐标映射；未握手前为 null。 */
    private volatile ScreenGeometry screenGeometry;

    public InputExecutor() throws AWTException {
        this.robot = new Robot();
        robot.setAutoDelay(0);
    }

    /** 更新屏幕几何，客户端连接/断开时由服务层调用。 */
    public void setScreenGeometry(ScreenGeometry screenGeometry) {
        this.screenGeometry = screenGeometry;
    }

    /** 移动鼠标到指定像素坐标（经屏幕几何转换）。 */
    public void handleMouseMove(int pixelX, int pixelY) {
        int[] screen = toScreenCoords(pixelX, pixelY);
        robot.mouseMove(screen[0], screen[1]);
    }

    /** 在目标位置执行鼠标按下或释放。 */
    public void handleMouseButton(PayloadCodec.MouseButtonPayload payload) {
        int[] screen = toScreenCoords(payload.getX(), payload.getY());
        robot.mouseMove(screen[0], screen[1]);
        int mask = toMouseMask(payload.getButton());
        if (payload.isPress()) {
            robot.mousePress(mask);
        } else {
            robot.mouseRelease(mask);
        }
    }

    /** 在目标位置滚动鼠标滚轮。 */
    public void handleMouseWheel(int x, int y, int rotation) {
        int[] screen = toScreenCoords(x, y);
        robot.mouseMove(screen[0], screen[1]);
        robot.mouseWheel(rotation);
    }

    /**
     * 执行键盘按下/释放，含修饰键（Ctrl/Alt/Shift/Meta）的联动处理。
     */
    public void handleKeyEvent(PayloadCodec.KeyEventPayload payload) {
        int keyCode = mapKeyCode(payload.getKeyCode());
        int modifiers = payload.getModifiers();

        if (payload.isPress()) {
            pressModifiers(modifiers);
            if (isValidKeyCode(keyCode)) {
                robot.keyPress(keyCode);
            }
        } else {
            if (isValidKeyCode(keyCode)) {
                robot.keyRelease(keyCode);
            }
            releaseModifiers(modifiers);
        }
    }

    /** 按下修饰键（Ctrl/Alt/Shift/Meta）。 */
    private void pressModifiers(int modifiers) {
        if ((modifiers & KeyEvent.CTRL_DOWN_MASK) != 0 || (modifiers & KeyEvent.CTRL_MASK) != 0) {
            robot.keyPress(KeyEvent.VK_CONTROL);
        }
        if ((modifiers & KeyEvent.ALT_DOWN_MASK) != 0 || (modifiers & KeyEvent.ALT_MASK) != 0) {
            robot.keyPress(KeyEvent.VK_ALT);
        }
        if ((modifiers & KeyEvent.SHIFT_DOWN_MASK) != 0 || (modifiers & KeyEvent.SHIFT_MASK) != 0) {
            robot.keyPress(KeyEvent.VK_SHIFT);
        }
        if ((modifiers & KeyEvent.META_DOWN_MASK) != 0 || (modifiers & KeyEvent.META_MASK) != 0) {
            robot.keyPress(KeyEvent.VK_META);
        }
    }

    /** 释放修饰键，顺序与按下相反。 */
    private void releaseModifiers(int modifiers) {
        if ((modifiers & KeyEvent.META_DOWN_MASK) != 0 || (modifiers & KeyEvent.META_MASK) != 0) {
            robot.keyRelease(KeyEvent.VK_META);
        }
        if ((modifiers & KeyEvent.SHIFT_DOWN_MASK) != 0 || (modifiers & KeyEvent.SHIFT_MASK) != 0) {
            robot.keyRelease(KeyEvent.VK_SHIFT);
        }
        if ((modifiers & KeyEvent.ALT_DOWN_MASK) != 0 || (modifiers & KeyEvent.ALT_MASK) != 0) {
            robot.keyRelease(KeyEvent.VK_ALT);
        }
        if ((modifiers & KeyEvent.CTRL_DOWN_MASK) != 0 || (modifiers & KeyEvent.CTRL_MASK) != 0) {
            robot.keyRelease(KeyEvent.VK_CONTROL);
        }
    }

    /** 将协议按钮编号转为 AWT 鼠标掩码。 */
    private int toMouseMask(int button) {
        if (button == Protocol.MOUSE_RIGHT) {
            return InputEvent.BUTTON3_DOWN_MASK;
        }
        if (button == Protocol.MOUSE_MIDDLE) {
            return InputEvent.BUTTON2_DOWN_MASK;
        }
        return InputEvent.BUTTON1_DOWN_MASK;
    }

    /**
     * Mac 控制端可能发送 Command 键，被控端为 Windows 时映射为 Control。
     */
    private int mapKeyCode(int keyCode) {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean serverIsWindows = os.contains("win");
        boolean clientSentMeta = keyCode == KeyEvent.VK_META || keyCode == KeyEvent.VK_CONTROL;
        if (serverIsWindows && keyCode == KeyEvent.VK_META) {
            return KeyEvent.VK_CONTROL;
        }
        if (!serverIsWindows && clientSentMeta && keyCode == KeyEvent.VK_CONTROL) {
            return KeyEvent.VK_META;
        }
        return keyCode;
    }

    private boolean isValidKeyCode(int keyCode) {
        return keyCode != KeyEvent.VK_UNDEFINED && keyCode != 0;
    }

    /** 将控制端像素坐标转为本地屏幕绝对坐标。 */
    private int[] toScreenCoords(int pixelX, int pixelY) {
        ScreenGeometry geometry = screenGeometry;
        if (geometry == null) {
            return new int[]{pixelX, pixelY};
        }
        return geometry.toScreenCoords(pixelX, pixelY);
    }
}
