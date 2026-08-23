package com.homesoft.usb.fs.uvc;

import android.util.Log;
import com.homesoft.usb.desc.video.FormatDesc;
import com.homesoft.usb.desc.video.FrameDesc;
import com.homesoft.usb.fs.UsbFs;
import java.nio.ByteBuffer;

public final class MjpegStreamUrbHandler extends VideoUrbHandler implements IFrameCallback {
    private static final String TAG = "MjpegStreamUrbHandler";

    public MjpegStreamUrbHandler(FormatDesc formatDesc, FrameDesc frameDesc, int endpointAddress, int maxPacketSize, int packetsPerUrb, int interval, int maxPayloadTransferSize, int maxVideoFrameSize) {
        super(formatDesc, frameDesc, endpointAddress, maxPacketSize, packetsPerUrb, interval, maxPayloadTransferSize, maxVideoFrameSize);
    }

    @Override
    public synchronized boolean start() throws Throwable {
        if (this.r >= 0) return true;
        
        int packetSize = this.packetsPerUrb == 0 ? this.maxPayloadTransferSize : this.maxPacketSize;
        int frameSize = this.maxVideoFrameSize > 0 ? this.maxVideoFrameSize : (this.frameDesc.getWidth() * this.frameDesc.getHeight() * 2);

        this.r = UsbFs.mpegStreamHandler(
            this.endpointAddress,
            packetSize,
            this.packetsPerUrb,
            this.frameDesc.getWidth(),
            this.frameDesc.getHeight(),
            842094169,
            frameSize,
            this
        );
        Log.d(TAG, "mpegStreamHandler started: ep=" + this.endpointAddress + ", pktSize=" + packetSize + ", pkts=" + this.packetsPerUrb + ", frameSize=" + frameSize + " -> handle=" + this.r);
        
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