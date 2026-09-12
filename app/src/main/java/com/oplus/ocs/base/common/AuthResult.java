/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.oplus.ocs.base.common;

import android.os.Parcel;
import android.os.Parcelable;

public class AuthResult implements Parcelable {
    public static final Parcelable.Creator<AuthResult> CREATOR = new Parcelable.Creator<AuthResult>() {
        @Override
        public AuthResult createFromParcel(Parcel parcel) {
            return new AuthResult(parcel);
        }

        @Override
        public AuthResult[] newArray(int i) {
            return new AuthResult[i];
        }
    };

    private String packageName;
    private int uid;
    private int pid;
    private int errorCode;
    private byte[] permitBits;

    public AuthResult(String packageName, int uid, int pid, int errorCode, byte[] permitBits) {
        this.packageName = packageName;
        this.uid = uid;
        this.pid = pid;
        this.errorCode = errorCode;
        this.permitBits = permitBits;
    }

    public AuthResult(Parcel parcel) {
        this.packageName = parcel.readString();
        this.uid = parcel.readInt();
        this.pid = parcel.readInt();
        this.errorCode = parcel.readInt();
        this.permitBits = parcel.createByteArray();
    }

    public int getErrrorCode() {
        return this.errorCode;
    }

    public String getPackageName() {
        return this.packageName;
    }

    public byte[] getPermitBits() {
        return this.permitBits;
    }

    public int getPid() {
        return this.pid;
    }

    public int getUid() {
        return this.uid;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(this.packageName);
        parcel.writeInt(this.uid);
        parcel.writeInt(this.pid);
        parcel.writeInt(this.errorCode);
        parcel.writeByteArray(this.permitBits);
    }
}
