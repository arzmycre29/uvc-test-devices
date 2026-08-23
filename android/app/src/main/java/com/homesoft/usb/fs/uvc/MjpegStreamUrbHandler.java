package com.homesoft.usb.fs.uvc;

import android.util.Log;
import com.homesoft.usb.desc.video.FormatDesc;
import com.homesoft.usb.desc.video.FrameDesc;
import com.homesoft.usb.fs.UsbFs;
import java.nio.ByteBuffer;

public final class MjpegStreamUrbHandler extends VideoUrbHandler implements IFrameCallback {
    private static final String TAG = "MjpegStreamUrbHandler";

    public MjpegStreamUrbHandler(FormatDesc formatDesc, FrameDesc frameDesc, int endpointAddress, int maxPacketSize, int packetsPerUrb, int interval, int maxPayloadTransferSize) {
        super(formatDesc, frameDesc, endpointAddress, maxPacketSize, packetsPerUrb, interval, maxPayloadTransferSize);
    }

    @Override
    public synchronized boolean start() throws Throwable {
        if (this.r >= 0) return true;
        this.r = UsbFs.mpegStreamHandler(
            this.endpointAddress,
            this.maxPacketSize,
            this.packetsPerUrb,
            this.frameDesc.getWidth(),
            this.frameDesc.getHeight(),
            842094169,
            this.maxPayloadTransferSize,
            this
        );
        Log.d(TAG, "MjpegStreamUrbHandler started with handle: " + this.r);
        if (this.surface != null && this.r >= 0) {
            setSurface(this.surface);
        }
        return this.r >= 0;
    }

    @Override
    public void onFrame(ByteBuffer byteBuffer, long pts) {
        if (byteBuffer == null || !byteBuffer.hasRemaining()) return;
        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        if (frameListener != null) {
            frameListener.onFrameCaptured(bytes);
        }
    }
}