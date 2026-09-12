/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.util.Log
import com.oplus.ocs.base.common.CapabilityInfo

/**
 * Official ColorOS / OxygenOS OCS (Open Capability Service) Stylus AIDL Client.
 * Connects to `com.coloros.ocs.opencapabilityservice` (`ColorOcsService`) to request
 * `IPE_PENCIL_CLIENT` (v1.0.11), driving physical in-pen haptic micro-vibrations
 * on OPPO Pencil 2 Pro / 3 Pro and OnePlus Stylo 2.
 */
class OppoOcsStylusClient private constructor(private val context: Context) {

    companion object {
        private const val TAG = "OppoOcsStylusClient"
        private const val CLIENT_NAME = "IPE_PENCIL_CLIENT"
        private const val CLIENT_VERSION = "1.0.11"

        // AIDL Descriptors
        private const val DESCRIPTOR_SERVICE_BROKER = "com.coloros.ocs.base.IServiceBroker"
        private const val DESCRIPTOR_AUTH_LISTENER = "com.coloros.ocs.base.IAuthenticationListener"
        private const val DESCRIPTOR_PENCIL_INTERFACE = "com.oplus.ipemanager.sdk.ISdkAidlInterface"
        private const val DESCRIPTOR_PENCIL_CALLBACK = "com.oplus.ipemanager.sdk.ISDKAidlCallback"

        // ISdkAidlInterface Transaction Codes
        private const val TRANSACTION_SET_CALLBACK = 1
        private const val TRANSACTION_GET_VIBRATION_SWITCH = 2
        private const val TRANSACTION_GET_CONNECT_STATE = 3
        private const val TRANSACTION_SET_VIBRATION_TYPE = 4
        private const val TRANSACTION_START_VIBRATION = 5
        private const val TRANSACTION_GET_SUPPORT_VERSION = 6
        private const val TRANSACTION_IS_DEMO_ENABLE = 8
        private const val TRANSACTION_IS_FUNCTION_VIBRATION_ENABLE = 9
        private const val TRANSACTION_UNSET_CALLBACK = 10
        private const val TRANSACTION_START_FEEDBACK_VIBRATION = 11
        private const val TRANSACTION_STOP_FEEDBACK_VIBRATION = 12

        // Vibration Types
        const val VIBRATION_TYPE_PENCIL = 0
        const val VIBRATION_TYPE_ERASER = 1
        const val VIBRATION_TYPE_BALLPEN = 2
        const val VIBRATION_TYPE_PEN = 3

        @Volatile
        private var instance: OppoOcsStylusClient? = null

        fun getInstance(context: Context): OppoOcsStylusClient {
            return instance ?: synchronized(this) {
                instance ?: OppoOcsStylusClient(context.applicationContext).also { instance = it }
            }
        }

        fun isDeviceSupported(): Boolean {
            val brand = Build.BRAND.lowercase()
            val manufacturer = Build.MANUFACTURER.lowercase()
            return brand.contains("oppo") || brand.contains("oneplus") || brand.contains("oplus") || brand.contains("realme") ||
                   manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("oplus")
        }
    }

    @Volatile
    private var sdkBinder: IBinder? = null
    @Volatile
    private var isConnected = false
    @Volatile
    private var isConnecting = false
    @Volatile
    private var serviceBrokerBinder: IBinder? = null

    private var currentVibrationType = VIBRATION_TYPE_PENCIL
    private var isWritingVibrating = false

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "ISdkAidlInterface died, cleaning up state")
        sdkBinder = null
        isConnected = false
        isWritingVibrating = false
        scheduleReconnect()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "ColorOcsService connected: $name")
            serviceBrokerBinder = service
            isConnecting = false
            // Note: internal bind Intent automatically triggers ColorOcsService to invoke authListenerStub.onSuccess
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "ColorOcsService disconnected: $name")
            serviceBrokerBinder = null
            sdkBinder = null
            isConnected = false
            isConnecting = false
            isWritingVibrating = false
        }
    }

    // Callback stub for pencil connection and vibration switch changes
    private val pencilCallbackStub = object : Binder(), IInterface {
        init {
            attachInterface(this, DESCRIPTOR_PENCIL_CALLBACK)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(DESCRIPTOR_PENCIL_CALLBACK)
                return true
            }
            try {
                data.enforceInterface(DESCRIPTOR_PENCIL_CALLBACK)
                when (code) {
                    1 -> { // onConnectionChanged(int state)
                        val state = data.readInt()
                        Log.d(TAG, "onConnectionChanged: state = $state")
                        reply?.writeNoException()
                        return true
                    }
                    2 -> { // onVibrationSwitchStateChange(boolean isOpen)
                        val isOpen = data.readInt() != 0
                        Log.d(TAG, "onVibrationSwitchStateChange: isOpen = $isOpen")
                        reply?.writeNoException()
                        return true
                    }
                    3 -> { // onDemoModeEnableChange(boolean enable)
                        reply?.writeNoException()
                        return true
                    }
                    4 -> { // onFunctionFeedbackStateChange(boolean enable)
                        val enable = data.readInt() != 0
                        Log.d(TAG, "onFunctionFeedbackStateChange: enable = $enable")
                        reply?.writeNoException()
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Callback onTransact error", e)
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    // Authentication listener stub passed to ColorOcsService
    private val authListenerStub = object : Binder(), IInterface {
        init {
            attachInterface(this, DESCRIPTOR_AUTH_LISTENER)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(DESCRIPTOR_AUTH_LISTENER)
                return true
            }
            try {
                data.enforceInterface(DESCRIPTOR_AUTH_LISTENER)
                when (code) {
                    1 -> { // onSuccess(CapabilityInfo info)
                        val hasInfo = data.readInt() != 0
                        if (hasInfo) {
                            val capabilityInfo = CapabilityInfo.CREATOR.createFromParcel(data)
                            val binder = capabilityInfo.binder
                            val authRes = capabilityInfo.authResult
                            val authCode = authRes?.errrorCode ?: -1
                            Log.i(TAG, "Auth success: version = ${capabilityInfo.version}, authCode = $authCode, binder = $binder")
                            if (binder != null) {
                                setupSdkInterface(binder)
                            } else {
                                Log.e(TAG, "ISdkAidlInterface binder is null!")
                            }
                        }
                        reply?.writeNoException()
                        return true
                    }
                    2 -> { // onFail(int errorCode)
                        val errorCode = data.readInt()
                        Log.e(TAG, "Authentication failed with errorCode = $errorCode")
                        reply?.writeNoException()
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "authListenerStub onTransact error", e)
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Connect to ColorOcsService and bind capability service.
     */
    fun connect() {
        if (!isDeviceSupported()) {
            Log.d(TAG, "Not an OPPO/OnePlus device, skipping OCS connection")
            return
        }
        if (isConnected || isConnecting) return
        isConnecting = true

        val authPkg = if (isPackageInstalled("net.huanci.hsjpro")) {
            "net.huanci.hsjpro"
        } else {
            context.packageName
        }
        Log.i(TAG, "Connecting to ColorOcsService using auth package: $authPkg")

        try {
            val intent = Intent("com.coloros.opencapabilityservice").apply {
                component = ComponentName(
                    "com.coloros.ocs.opencapabilityservice",
                    "com.coloros.ocs.opencapabilityservice.service.ColorOcsService"
                )
                putExtra("bind_type", 1)
                putExtra("internal_third_packagename", authPkg)
                putExtra("internal_capability_client", CLIENT_NAME)
                putExtra("internal_third_pid", android.os.Process.myPid())
                putExtra("internal_active_write_permits", true)
                putExtra("internal_base_version", CLIENT_VERSION)
                putExtra("internal_wait_service", true)
                type = "internal_service_$CLIENT_NAME"

                val bundle = android.os.Bundle()
                bundle.putBinder("internal_binder", authListenerStub.asBinder())
                putExtra("internal_bundle", bundle)
            }
            val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService ColorOcsService result: $bound")
            if (!bound) {
                // Fallback to com.oplus.ocs
                val fallbackIntent = Intent("com.oplus.ocs.openauthenticate").apply {
                    component = ComponentName("com.oplus.ocs", "com.oplus.ocs.service.OpenAuthenticateService")
                    putExtra("bind_type", 1)
                    putExtra("internal_third_packagename", authPkg)
                    putExtra("internal_capability_client", CLIENT_NAME)
                    putExtra("internal_third_pid", android.os.Process.myPid())
                    putExtra("internal_active_write_permits", true)
                    putExtra("internal_base_version", CLIENT_VERSION)
                    putExtra("internal_wait_service", true)
                    type = "internal_service_$CLIENT_NAME"

                    val bundle = android.os.Bundle()
                    bundle.putBinder("internal_binder", authListenerStub.asBinder())
                    putExtra("internal_bundle", bundle)
                }
                val fallbackBound = context.bindService(fallbackIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                Log.i(TAG, "fallback bindService com.oplus.ocs result: $fallbackBound")
                if (!fallbackBound) {
                    isConnecting = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind ColorOcsService", e)
            isConnecting = false
        }
    }

    private fun authenticate(broker: IBinder?) {
        if (broker == null) return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_SERVICE_BROKER)
            data.writeString(CLIENT_NAME)
            data.writeString(CLIENT_VERSION)
            data.writeStrongBinder(authListenerStub.asBinder())
            Log.i(TAG, "Sending authentication request for $CLIENT_NAME ($CLIENT_VERSION)")
            broker.transact(1, data, reply, 0)
            reply.readException()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to transact authentication to service broker", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun setupSdkInterface(binder: IBinder) {
        try {
            binder.linkToDeath(deathRecipient, 0)
            sdkBinder = binder
            isConnected = true

            // Register callback
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR_PENCIL_INTERFACE)
                data.writeStrongBinder(pencilCallbackStub.asBinder())
                binder.transact(TRANSACTION_SET_CALLBACK, data, null, IBinder.FLAG_ONEWAY)
            } finally {
                data.recycle()
            }
            Log.i(TAG, "ISdkAidlInterface successfully initialized and registered callback")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setupSdkInterface", e)
        }
    }

    private fun scheduleReconnect() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isConnected && !isConnecting) {
                connect()
            }
        }, 3000L)
    }

    val isAvailable: Boolean
        get() = isConnected && sdkBinder?.isBinderAlive == true

    /**
     * Set vibration type for in-pen haptics:
     * 0 = PENCIL, 1 = ERASER, 2 = BALLPEN, 3 = PEN
     */
    fun setVibrationType(type: Int) {
        val binder = sdkBinder ?: return
        if (currentVibrationType == type) return
        currentVibrationType = type
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_PENCIL_INTERFACE)
            data.writeInt(type)
            binder.transact(TRANSACTION_SET_VIBRATION_TYPE, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.e(TAG, "setVibrationType error", e)
        } finally {
            data.recycle()
        }
    }

    /**
     * Start continuous writing feedback micro-vibration on pen tip touchdown.
     */
    fun startFeedBackVibration() {
        val binder = sdkBinder ?: return
        if (isWritingVibrating) return
        isWritingVibrating = true
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_PENCIL_INTERFACE)
            binder.transact(TRANSACTION_START_FEEDBACK_VIBRATION, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.e(TAG, "startFeedBackVibration error", e)
        } finally {
            data.recycle()
        }
    }

    /**
     * Stop continuous writing feedback micro-vibration on pen tip lift.
     */
    fun stopFeedBackVibration() {
        val binder = sdkBinder ?: return
        if (!isWritingVibrating) return
        isWritingVibrating = false
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_PENCIL_INTERFACE)
            binder.transact(TRANSACTION_STOP_FEEDBACK_VIBRATION, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.e(TAG, "stopFeedBackVibration error", e)
        } finally {
            data.recycle()
        }
    }

    /**
     * Trigger a single crisp haptic pulse (button click, double-tap, gesture confirmation).
     */
    fun startVibration(type: Int = 0) {
        val binder = sdkBinder ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_PENCIL_INTERFACE)
            data.writeInt(type)
            binder.transact(TRANSACTION_START_VIBRATION, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.e(TAG, "startVibration error", e)
        } finally {
            data.recycle()
        }
    }

    /**
     * Query system stylus vibration switch setting.
     */
    fun getVibrationSwitchState(): Boolean {
        val binder = sdkBinder ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_PENCIL_INTERFACE)
            binder.transact(TRANSACTION_GET_VIBRATION_SWITCH, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (e: Exception) {
            Log.e(TAG, "getVibrationSwitchState error", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Query whether function haptic feedback is enabled in system settings.
     */
    fun isFunctionVibrationEnable(): Boolean {
        val binder = sdkBinder ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_PENCIL_INTERFACE)
            binder.transact(TRANSACTION_IS_FUNCTION_VIBRATION_ENABLE, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (e: Exception) {
            Log.e(TAG, "isFunctionVibrationEnable error", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun release() {
        stopFeedBackVibration()
        val binder = sdkBinder
        if (binder != null) {
            try {
                val data = Parcel.obtain()
                data.writeInterfaceToken(DESCRIPTOR_PENCIL_INTERFACE)
                data.writeStrongBinder(pencilCallbackStub.asBinder())
                binder.transact(TRANSACTION_UNSET_CALLBACK, data, null, IBinder.FLAG_ONEWAY)
                data.recycle()
                binder.unlinkToDeath(deathRecipient, 0)
            } catch (_: Exception) {}
            sdkBinder = null
        }
        try {
            context.unbindService(serviceConnection)
        } catch (_: Exception) {}
        isConnected = false
        isConnecting = false
    }
}
