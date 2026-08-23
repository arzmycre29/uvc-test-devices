package com.uvctester.app.media;

import android.content.Context;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;
import com.homesoft.usb.fs.UsbFs;
import com.homesoft.usb.fs.uvc.IYuv420Recorder;
import com.homesoft.usb.fs.uvc.VideoUrbHandler;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class UvcVideoRecorder implements IYuv420Recorder {
    private static final String TAG = "UvcVideoRecorder";

    private final Context context;
    private final VideoUrbHandler videoHandler;
    private final int width;
    private final int height;
    private final int fps;

    private MediaCodec mediaCodec;
    private MediaMuxer mediaMuxer;
    private int videoTrackIndex = -1;
    private boolean muxerStarted = false;
    private MediaSaver.VideoTarget videoTarget;

    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private Thread drainThread;
    private long startTimeUs = -1;
    private long lastFrameTimeUs = -1;
    private int frameCount = 0;
    private int currentInputIndex = -1;

    public interface RecordCallback {
        void onStarted(String filePath, String fileName);
        void onFinished(String filePath, String fileName, long durationMs, int frameCount);
        void onError(String error);
    }

    public UvcVideoRecorder(Context context, VideoUrbHandler videoHandler, int width, int height, int fps) {
        this.context = context;
        this.videoHandler = videoHandler;
        this.width = width;
        this.height = height;
        this.fps = fps > 0 ? fps : 30;
    }

    public synchronized void start(final RecordCallback callback) {
        if (isRecording.get()) {
            if (callback != null) callback.onError("Already recording");
            return;
        }

        try {
            this.videoTarget = MediaSaver.createVideoTarget(context);

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            int bitRate = Math.min(width * height * 4, 8000000);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            this.mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            this.mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            this.mediaCodec.start();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && videoTarget.pfd != null) {
                this.mediaMuxer = new MediaMuxer(videoTarget.pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else if (videoTarget.legacyFile != null) {
                this.mediaMuxer = new MediaMuxer(videoTarget.legacyFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else {
                File tempFile = new File(context.getCacheDir(), videoTarget.fileName);
                this.mediaMuxer = new MediaMuxer(tempFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }

            this.isRecording.set(true);
            this.frameCount = 0;
            this.startTimeUs = -1;
            this.muxerStarted = false;
            this.videoTrackIndex = -1;

            // Start draining output thread
            this.drainThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    drainEncoder();
                }
            }, "UvcRecorderDrain");
            this.drainThread.start();

            // Bind recorder to native JNI C++ engine
            synchronized (videoHandler) {
                UsbFs.setRecorder(videoHandler.r, this);
            }

            Log.d(TAG, "UvcVideoRecorder started successfully: " + videoTarget.relativePath);
            if (callback != null) {
                callback.onStarted(videoTarget.relativePath, videoTarget.fileName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start UvcVideoRecorder", e);
            cleanup();
            if (callback != null) {
                callback.onError("Start recording error: " + e.getMessage());
            }
        }
    }

    @Override
    public Image nextImage() {
        if (!isRecording.get() || mediaCodec == null) return null;
        try {
            int inputIndex = mediaCodec.dequeueInputBuffer(10000); // 10ms timeout
            this.currentInputIndex = inputIndex;
            if (inputIndex >= 0) {
                return mediaCodec.getInputImage(inputIndex);
            }
        } catch (Exception e) {
            Log.w(TAG, "nextImage error: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void queueImage(long timestampNs, int status) {
        if (!isRecording.get() || mediaCodec == null) return;
        int inputIndex = this.currentInputIndex;
        this.currentInputIndex = -1;

        if (inputIndex < 0) return;

        try {
            if (status >= 0) {
                long nowUs = System.nanoTime() / 1000;
                if (startTimeUs < 0) {
                    startTimeUs = nowUs;
                }
                long ptsUs = nowUs - startTimeUs;
                if (ptsUs <= lastFrameTimeUs) {
                    ptsUs = lastFrameTimeUs + (1000000 / fps);
                }
                lastFrameTimeUs = ptsUs;

                Image img = mediaCodec.getInputImage(inputIndex);
                if (img != null) {
                    // Correct YUV Chroma (Swap U and V to eliminate bluish/cyan tint)
                    swapUVPlanes(img);
                }

                int size = (width * height * 3) / 2;
                mediaCodec.queueInputBuffer(inputIndex, 0, size, ptsUs, 0);
                frameCount++;
            } else {
                mediaCodec.queueInputBuffer(inputIndex, 0, 0, 0, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "queueImage error: " + e.getMessage());
        }
    }

    /**
     * Swaps U and V (Cb and Cr) chroma planes so colors match natural reality
     * instead of inverted blue/cyan tint.
     */
    private void swapUVPlanes(Image img) {
        try {
            Image.Plane[] planes = img.getPlanes();
            if (planes == null || planes.length < 3) return;

            ByteBuffer uBuf = planes[1].getBuffer();
            ByteBuffer vBuf = planes[2].getBuffer();
            if (uBuf == null || vBuf == null) return;

            int pixelStride = planes[1].getPixelStride();
            int uRem = uBuf.remaining();
            int vRem = vBuf.remaining();
            int len = Math.min(uRem, vRem);

            if (pixelStride == 1) {
                // Planar format (I420 vs YV12)
                for (int i = 0; i < len; i++) {
                    byte u = uBuf.get(i);
                    byte v = vBuf.get(i);
                    uBuf.put(i, v);
                    vBuf.put(i, u);
                }
            } else {
                // Semi-planar interleaved format (NV12 vs NV21)
                for (int i = 0; i < len; i += pixelStride) {
                    byte u = uBuf.get(i);
                    byte v = vBuf.get(i);
                    uBuf.put(i, v);
                    vBuf.put(i, u);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "swapUVPlanes error: " + e.getMessage());
        }
    }

    private void drainEncoder() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (isRecording.get() || mediaCodec != null) {
            if (mediaCodec == null) break;
            try {
                int outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!isRecording.get()) break;
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        Log.e(TAG, "Format changed after muxer started");
                    } else {
                        MediaFormat newFormat = mediaCodec.getOutputFormat();
                        videoTrackIndex = mediaMuxer.addTrack(newFormat);
                        mediaMuxer.start();
                        muxerStarted = true;
                        Log.d(TAG, "MediaMuxer started with track index: " + videoTrackIndex);
                    }
                } else if (outputIndex >= 0) {
                    ByteBuffer encodedData = mediaCodec.getOutputBuffer(outputIndex);
                    if (encodedData != null && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size != 0) {
                        if (muxerStarted && videoTrackIndex >= 0) {
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);
                            mediaMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                        }
                    }
                    mediaCodec.releaseOutputBuffer(outputIndex, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "drainEncoder error: " + e.getMessage());
                break;
            }
        }
    }

    public synchronized void stop(final RecordCallback callback) {
        if (!isRecording.get()) {
            if (callback != null) callback.onError("Not recording");
            return;
        }

        isRecording.set(false);

        // Unbind recorder from native kernel
        if (videoHandler != null) {
            synchronized (videoHandler) {
                try { UsbFs.setRecorder(videoHandler.r, null); } catch (Exception ignored) {}
            }
        }

        // Send EOS
        if (mediaCodec != null) {
            try {
                int inputIndex = mediaCodec.dequeueInputBuffer(50000);
                if (inputIndex >= 0) {
                    mediaCodec.queueInputBuffer(inputIndex, 0, 0, lastFrameTimeUs + 33333, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            } catch (Exception ignored) {}
        }

        // Wait drain thread
        if (drainThread != null) {
            try { drainThread.join(1000); } catch (Exception ignored) {}
            drainThread = null;
        }

        cleanup();

        long durationMs = lastFrameTimeUs > 0 ? (lastFrameTimeUs / 1000) : 0;
        Log.d(TAG, "Recording finished: " + (videoTarget != null ? videoTarget.relativePath : "null") + ", frames=" + frameCount + ", duration=" + durationMs + "ms");

        if (callback != null) {
            if (videoTarget != null) {
                callback.onFinished(videoTarget.relativePath, videoTarget.fileName, durationMs, frameCount);
            } else {
                callback.onFinished("", "", durationMs, frameCount);
            }
        }
    }

    private void cleanup() {
        try {
            if (mediaCodec != null) {
                try { mediaCodec.stop(); } catch (Exception ignored) {}
                try { mediaCodec.release(); } catch (Exception ignored) {}
                mediaCodec = null;
            }
        } catch (Exception ignored) {}

        try {
            if (mediaMuxer != null) {
                try {
                    if (muxerStarted) mediaMuxer.stop();
                } catch (Exception ignored) {}
                try { mediaMuxer.release(); } catch (Exception ignored) {}
                mediaMuxer = null;
            }
        } catch (Exception ignored) {}

        if (videoTarget != null) {
            MediaSaver.finishVideoTarget(context, videoTarget);
        }
    }

    public boolean isRecording() {
        return isRecording.get();
    }

    public int getFrameCount() {
        return frameCount;
    }
}