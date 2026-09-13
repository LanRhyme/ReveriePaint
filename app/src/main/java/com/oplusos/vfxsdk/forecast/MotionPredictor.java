package com.oplusos.vfxsdk.forecast;

public class MotionPredictor {
    private long mNativeHandle = 0;
    private long mPreviousTime = 0;
    private boolean mValid = false;

    public MotionPredictor() {
        create();
    }

    private void create() {
        try {
            this.mNativeHandle = NativeForecast.create(this.mNativeHandle);
            this.mValid = (this.mNativeHandle != 0);
        } catch (Throwable t) {
            this.mValid = false;
        }
    }

    public boolean isValid() {
        return mValid;
    }

    public void destroy() {
        if (mValid && mNativeHandle != 0) {
            try {
                NativeForecast.destroy(this.mNativeHandle);
            } catch (Throwable ignored) {}
            this.mNativeHandle = 0;
            this.mValid = false;
        }
    }

    public TouchPointInfo predictTouchPoint() {
        if (!mValid || mNativeHandle == 0) return null;
        try {
            TouchPointInfo touchPointInfo = NativeForecast.predictTouchPoint(this.mNativeHandle);
            if (touchPointInfo != null) {
                touchPointInfo.timestamp += this.mPreviousTime;
            }
            return touchPointInfo;
        } catch (Throwable t) {
            return null;
        }
    }

    public void pushTouchPoint(TouchPointInfo touchPointInfo) {
        if (!mValid || mNativeHandle == 0 || touchPointInfo == null) return;
        try {
            touchPointInfo.timestamp = touchPointInfo.timestamp - this.mPreviousTime;
            NativeForecast.pushTouchPoint(this.mNativeHandle, touchPointInfo);
        } catch (Throwable ignored) {}
    }

    public void reset() {
        this.mPreviousTime = System.nanoTime() / 1000000;
        if (!mValid || mNativeHandle == 0) return;
        try {
            NativeForecast.reset(this.mNativeHandle);
        } catch (Throwable ignored) {}
    }

    public void setDpi(float f, float f2) {
        if (!mValid || mNativeHandle == 0) return;
        try {
            NativeForecast.setDpi(this.mNativeHandle, f, f2);
        } catch (Throwable ignored) {}
    }

    public void setRefreshRate(float f) {
        if (!mValid || mNativeHandle == 0) return;
        try {
            NativeForecast.setRefreshRate(this.mNativeHandle, f);
        } catch (Throwable ignored) {}
    }
}
