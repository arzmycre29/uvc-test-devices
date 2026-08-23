package com.homesoft.usb.fs.uvc;

import java.nio.ByteBuffer;

public interface IYuv420Recorder {
    void onYuv420Frame(ByteBuffer byteBuffer, long pts);
}