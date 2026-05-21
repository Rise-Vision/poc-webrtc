package org.webrtc.audio;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Invokes package-private native capture on {@link WebRtcAudioRecord} from app code.
 */
public final class WebRtcNativeAudioBridge {

    private WebRtcNativeAudioBridge() {}

    public static boolean feedPcmFrame(Object webRtcAudioRecord, int frameBytes) {
        if (webRtcAudioRecord == null || frameBytes <= 0) {
            return false;
        }
        try {
            Field nativeField = WebRtcAudioRecord.class.getDeclaredField("nativeAudioRecord");
            nativeField.setAccessible(true);
            long nativePtr = nativeField.getLong(webRtcAudioRecord);
            if (nativePtr == 0L) {
                return false;
            }
            Method nativeDataIsRecorded = WebRtcAudioRecord.class.getDeclaredMethod(
                    "nativeDataIsRecorded",
                    long.class,
                    int.class,
                    long.class);
            nativeDataIsRecorded.setAccessible(true);
            nativeDataIsRecorded.invoke(webRtcAudioRecord, nativePtr, frameBytes, 0L);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public static long getNativeAudioRecordPointer(Object webRtcAudioRecord) {
        if (webRtcAudioRecord == null) {
            return 0L;
        }
        try {
            Field nativeField = WebRtcAudioRecord.class.getDeclaredField("nativeAudioRecord");
            nativeField.setAccessible(true);
            return nativeField.getLong(webRtcAudioRecord);
        } catch (ReflectiveOperationException e) {
            return 0L;
        }
    }

    public static ByteBuffer getCaptureByteBuffer(Object webRtcAudioRecord) {
        if (webRtcAudioRecord == null) {
            return null;
        }
        try {
            Field field = WebRtcAudioRecord.class.getDeclaredField("byteBuffer");
            field.setAccessible(true);
            return (ByteBuffer) field.get(webRtcAudioRecord);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static boolean stopStockCaptureThread(Object webRtcAudioRecord) {
        if (webRtcAudioRecord == null) {
            return false;
        }
        try {
            Field threadField = WebRtcAudioRecord.class.getDeclaredField("audioThread");
            threadField.setAccessible(true);
            Object audioThread = threadField.get(webRtcAudioRecord);
            if (audioThread == null) {
                return false;
            }
            Method stopThread = audioThread.getClass().getDeclaredMethod("stopThread");
            stopThread.setAccessible(true);
            stopThread.invoke(audioThread);
            if (audioThread instanceof Thread) {
                try {
                    ((Thread) audioThread).join(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public static void setMicrophoneMuteField(Object webRtcAudioRecord, boolean mute) {
        if (webRtcAudioRecord == null) {
            return;
        }
        try {
            Field field = WebRtcAudioRecord.class.getDeclaredField("microphoneMute");
            field.setAccessible(true);
            field.setBoolean(webRtcAudioRecord, mute);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
