package com.homesoft.usb.fs.uvc;

import java.nio.ByteBuffer;

public interface IFrameCallback {
    ByteBuffer f = ByteBuffer.allocate(0);
    void onFrame(ByteBuffer byteBuffer, long pts);
}