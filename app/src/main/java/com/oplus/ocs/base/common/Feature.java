/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.oplus.ocs.base.common;

import android.os.Parcel;
import android.os.Parcelable;

public class Feature implements Parcelable {
    public static final Parcelable.Creator<Feature> CREATOR = new Parcelable.Creator<Feature>() {
        @Override
        public Feature createFromParcel(Parcel parcel) {
            return new Feature(parcel);
        }

        @Override
        public Feature[] newArray(int i) {
            return new Feature[i];
        }
    };

    private String name;
    private long version;

    public Feature(String name, long version) {
        this.name = name;
        this.version = version;
    }

    protected Feature(Parcel parcel) {
        this.name = parcel.readString();
        this.version = parcel.readLong();
    }

    public String getName() {
        return this.name;
    }

    public long getVersion() {
        return this.version;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(this.name);
        parcel.writeLong(this.version);
    }
}
