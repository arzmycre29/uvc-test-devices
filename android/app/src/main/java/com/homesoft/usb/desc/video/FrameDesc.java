package com.homesoft.usb.desc.video;

public class FrameDesc {
    public final byte frameIndex;
    public final int width;
    public final int height;
    public final int defaultFrameInterval; // 100ns units
    public final int[] frameIntervals;

    public FrameDesc(byte frameIndex, int width, int height, int defaultFrameInterval, int[] frameIntervals) {
        this.frameIndex = frameIndex;
        this.width = width;
        this.height = height;
        this.defaultFrameInterval = defaultFrameInterval;
        this.frameIntervals = frameIntervals != null ? frameIntervals : new int[]{defaultFrameInterval};
    }

    public byte getFrameIndex() { return frameIndex; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int q() { return width; }
    public int k() { return defaultFrameInterval; }

    public float getFps() {
        if (defaultFrameInterval <= 0) return 30.0f;
        return Math.round(1.0E7f / defaultFrameInterval);
    }
}