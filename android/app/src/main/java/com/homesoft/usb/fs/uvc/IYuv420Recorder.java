package com.homesoft.usb.fs.uvc;

import android.media.Image;

public interface IYuv420Recorder {
    Image nextImage();
    void queueImage(long j, int i);
}