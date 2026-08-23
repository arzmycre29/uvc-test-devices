package com.homesoft.usb.fs.uvc;

import android.util.Log;
import com.homesoft.usb.desc.video.FormatDesc;
import com.homesoft.usb.desc.video.FrameDesc;
import com.homesoft.usb.fs.UsbFs;
import java.nio.ByteBuffer;

public final class MediaCodecUrbHandler extends VideoUrbHandler implements IFlagFrameCallback {
    private static final String TAG = "MediaCodecUrbHandler";
    public final boolean isHevc;

    public MediaCodecUrbHandler(FormatDesc formatDesc, FrameDesc frameDesc, int endpointAddress, int maxPacketSize, int packetsPerUrb, int interval, int maxPayloadTransferSize, boolean isHevc) {
        super(formatDesc, frameDesc, endpointAddress, maxPacketSize, packetsPerUrb, interval, maxPayloadTransferSize);
        this.isHevc = isHevc;
    }

    @Override
    public synchronized boolean start() throws Throwable {
        if (this.r >= 0) return true;
        if (isHevc) {
            this.r = UsbFs.mediaCodecH265StreamHandler(
                this.endpointAddress,
                this.maxPacketSize,
                this.packetsPerUrb,
                this.frameDesc.getWidth(),
                this.frameDesc.getHeight(),
                this.maxPayloadTransferSize,
                this
            );
        } else {
            this.r = UsbFs.mediaCodecH264StreamHandler(
                this.endpointAddress,
                this.maxPacketSize,
                this.packetsPerUrb,
                this.frameDesc.getWidth(),
                this.frameDesc.getHeight(),
                this.maxPayloadTransferSize,
                this
            );
        }
        Log.d(TAG, "MediaCodecUrbHandler started with handle: " + this.r);
        if (this.surface != null && this.r >= 0) {
            setSurface(this.surface);
        }
        return this.r >= 0;
    }

    @Override
    public void onFrame(ByteBuffer byteBuffer, long pts, byte flags) {
    }
}