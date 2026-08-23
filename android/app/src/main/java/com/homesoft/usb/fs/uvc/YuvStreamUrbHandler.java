package com.homesoft.usb.fs.uvc;

import android.graphics.Rect;
import android.graphics.YuvImage;
import android.util.Log;
import com.homesoft.usb.desc.video.FormatDesc;
import com.homesoft.usb.desc.video.FrameDesc;
import com.homesoft.usb.fs.UsbFs;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

public final class YuvStreamUrbHandler extends VideoUrbHandler implements IFrameCallback {
    private static final String TAG = "YuvStreamUrbHandler";
    public final int imageFormat; // 20 = YUY2, 33 = NV12

    public YuvStreamUrbHandler(FormatDesc formatDesc, FrameDesc frameDesc, int endpointAddress, int maxPacketSize, int packetsPerUrb, int interval, int maxPayloadTransferSize, int imageFormat) {
        super(formatDesc, frameDesc, endpointAddress, maxPacketSize, packetsPerUrb, interval, maxPayloadTransferSize);
        this.imageFormat = imageFormat;
    }

    @Override
    public synchronized boolean start() throws Throwable {
        if (this.r >= 0) return true;
        this.r = UsbFs.yuvStreamHandler(
            this.endpointAddress,
            this.maxPacketSize,
            this.packetsPerUrb,
            this.imageFormat,
            this.frameDesc.getWidth(),
            this.frameDesc.getHeight(),
            842094169,
            this.maxPayloadTransferSize,
            this
        );
        Log.d(TAG, "YuvStreamUrbHandler started with handle: " + this.r);
        if (this.surface != null && this.r >= 0) {
            setSurface(this.surface);
        }
        return this.r >= 0;
    }

    @Override
    public void onFrame(ByteBuffer byteBuffer, long pts) {
        if (byteBuffer == null || !byteBuffer.hasRemaining()) return;
        try {
            int w = getWidth();
            int h = getHeight();
            byte[] bArr = new byte[byteBuffer.remaining()];
            byteBuffer.get(bArr);

            YuvImage yuvImage;
            if (this.imageFormat == 20) {
                yuvImage = new YuvImage(bArr, 20, w, h, null);
            } else if (this.imageFormat == 33) {
                UsbFs.swapUVPlane(bArr, w, h);
                yuvImage = new YuvImage(bArr, 17, w, h, null);
            } else {
                int i4 = w * h;
                int i5 = (i4 * 3) / 2;
                byte[] bArrCopy = Arrays.copyOf(bArr, i5);
                Arrays.fill(bArrCopy, i4, i5, (byte) -128);
                yuvImage = new YuvImage(bArrCopy, 17, w, h, null);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, w, h), 95, out);
            byte[] jpegBytes = out.toByteArray();

            if (frameListener != null) {
                frameListener.onFrameCaptured(jpegBytes);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to compress YUV frame to JPEG", e);
        }
    }
}