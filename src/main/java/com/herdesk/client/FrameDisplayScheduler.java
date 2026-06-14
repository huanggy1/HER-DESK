package com.herdesk.client;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * 控制端帧调度器：合并积压帧，仅在 EDT 上刷新最新画面，避免 UI 线程被逐帧淹没。
 */
public final class FrameDisplayScheduler {

    /** 实际刷新 UI 的回调（通常为 viewPanel.updateFrame）。 */
    private final Consumer<BufferedImage> displayAction;
    /** 最新待显示帧，由接收线程写入。 */
    private volatile BufferedImage latestFrame;
    /** 是否已安排 EDT 刷新任务，防止重复 invokeLater。 */
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    public FrameDisplayScheduler(Consumer<BufferedImage> displayAction) {
        this.displayAction = displayAction;
    }

    /** 提交新帧，由调度器合并后在 EDT 刷新。 */
    public void submit(BufferedImage frame) {
        if (frame == null) {
            return;
        }
        latestFrame = frame;
        if (flushScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(new FlushTask());
        }
    }

    /** 断开连接时清空待显示帧与调度状态。 */
    public void reset() {
        latestFrame = null;
        flushScheduled.set(false);
    }

    /**
     * 提交新帧；若期间又有新帧到达则循环刷新，最终只显示最新一帧。
     */
    private final class FlushTask implements Runnable {
        @Override
        public void run() {
            while (true) {
                BufferedImage frame = latestFrame;
                if (frame != null) {
                    displayAction.accept(copyForDisplay(frame));
                }
                flushScheduled.set(false);
                if (latestFrame == frame) {
                    break;
                }
                flushScheduled.set(true);
            }
        }
    }

    /** 复制为 INT_RGB 格式，避免 EDT 绘制时与解码线程共享可变图像。 */
    private static BufferedImage copyForDisplay(BufferedImage source) {
        BufferedImage copy = new BufferedImage(
                source.getWidth(),
                source.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }
}
