package com.oplusos.vfxsdk.forecast;

public class TouchPointInfo {
    public float axisTilt;
    public float pressure;
    public long timestamp;
    public float x;
    public float y;

    public TouchPointInfo(float f, float f2, float f3, float f4, long j) {
        this.x = f;
        this.y = f2;
        this.pressure = f3;
        this.axisTilt = f4;
        this.timestamp = j;
    }

    public TouchPointInfo() {
        this.x = 0.0f;
        this.y = 0.0f;
        this.pressure = 0.0f;
        this.axisTilt = 0.0f;
        this.timestamp = 0L;
    }
}
