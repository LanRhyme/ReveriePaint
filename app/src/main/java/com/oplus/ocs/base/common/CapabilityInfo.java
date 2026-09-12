/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.oplus.ocs.base.common;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.List;

public class CapabilityInfo implements Parcelable {
    public static final Parcelable.Creator<CapabilityInfo> CREATOR = new Parcelable.Creator<CapabilityInfo>() {
        @Override
        public CapabilityInfo createFromParcel(Parcel parcel) {
            return new CapabilityInfo(parcel);
        }

        @Override
        public CapabilityInfo[] newArray(int i) {
            return new CapabilityInfo[i];
        }
    };

    private List<Feature> features;
    private int version;
    private AuthResult authResult;
    private IBinder binder;

    public CapabilityInfo(List<Feature> features, int version, AuthResult authResult, IBinder binder) {
        this.features = features;
        this.version = version;
        this.authResult = authResult;
        this.binder = binder;
    }

    public CapabilityInfo(Parcel parcel) {
        this.features = parcel.readArrayList(Feature.class.getClassLoader());
        this.version = parcel.readInt();
        String authClassName = parcel.readString();
        if (authClassName != null) {
            this.authResult = new AuthResult(parcel);
        }
        this.binder = parcel.readStrongBinder();
    }

    public AuthResult getAuthResult() {
        return this.authResult;
    }

    public IBinder getBinder() {
        return this.binder;
    }

    public List<Feature> getFeatures() {
        return this.features;
    }

    public int getVersion() {
        return this.version;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeList(this.features);
        parcel.writeInt(this.version);
        if (this.authResult != null) {
            parcel.writeString(AuthResult.class.getName());
            this.authResult.writeToParcel(parcel, 0);
        } else {
            parcel.writeString(null);
        }
        parcel.writeStrongBinder(this.binder);
    }
}
