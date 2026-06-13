package com.herdesk.client;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * 控制端帧调度：合并积压帧，仅在 EDT 上刷新最新画面，减少卡顿。
 */
public final class FrameDisplayScheduler {

    private final Consumer<BufferedImage> displayAction;
    private volatile BufferedImage latestFrame;
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    public FrameDisplayScheduler(Consumer<BufferedImage> displayAction) {
        this.displayAction = displayAction;
    }

    public void submit(BufferedImage frame) {
        if (frame == null) {
            return;
        }
        latestFrame = frame;
        if (flushScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(new FlushTask());
        }
    }

    public void reset() {
        latestFrame = null;
        flushScheduled.set(false);
    }

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
