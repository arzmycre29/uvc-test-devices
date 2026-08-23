package com.homesoft.usb.fs.uvc;

import java.nio.ByteBuffer;

public interface IFlagFrameCallback {
    void onFrame(ByteBuffer byteBuffer, long pts, byte flags);
}