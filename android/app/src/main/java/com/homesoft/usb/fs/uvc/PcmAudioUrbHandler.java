package com.homesoft.usb.fs.uvc;

import com.homesoft.usb.fs.UsbFs;

public final class PcmAudioUrbHandler {
    public int r = -1;
    public synchronized void stop() {
        if (this.r >= 0) {
            UsbFs.cleanupHandler(this.r);
            this.r = -1;
        }
    }
}