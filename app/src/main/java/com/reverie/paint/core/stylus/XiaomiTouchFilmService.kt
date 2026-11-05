/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core.stylus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.Log

/**
 * 仅注册 HyperOS 触控手势服务，不依赖 APK 内并不存在的 PenEngine SDK 类。
 * IPencilEngine 的线协议及版本差异见 docs/XIAOMI-STYLUS.md。
 * 服务注册让系统向当前应用投递键码 194–197；快捷动作仍由 XiaomiStylusAdapter 处理。
 * 所有连接状态只在主线程读写，笔画 MotionEvent 路径不会调用这里。
 */
internal class XiaomiTouchFilmService(context: Context) {
    companion object {
        private const val TAG = "ReverieXiaomiTouchFilm"
        private const val PACKAGE = "com.xiaomi.touchservice"
        private const val SERVICE = "$PACKAGE.pencilengine.PencilEngineManagerService"
        private const val DESCRIPTOR = "$PACKAGE.pencilengine.IPencilEngine"
        private const val CALLBACK_DESCRIPTOR = "$PACKAGE.pencilengine.IPencilEngineCallback"
        private const val REGISTER = IBinder.FIRST_CALL_TRANSACTION
        private const val UNREGISTER = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val COMMAND = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val REGISTER_WITH_PACKAGE = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TOUCH_FILM = 2
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var connection: ServiceConnection? = null
    private var remote: IBinder? = null
    private var registered = false
    private var enabled = false
    private val reconnect = Runnable { if (active) bind() }

    // 该回调仅用于服务登记；取色回调不属于本适配器，不修改画布或 UI。
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(CALLBACK_DESCRIPTOR)
                return true
            }
            if (code == IBinder.FIRST_CALL_TRANSACTION) {
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                data.readInt()
                data.readInt()
                data.readInt()
                reply?.writeNoException()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    fun start() {
        active = true
        if (connection == null) bind()
    }

    fun stop() {
        active = false
        handler.removeCallbacks(reconnect)
        unbind()
    }

    private fun bind() {
        if (!active || connection != null) return
        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (!active || connection !== this) return
                remote = service
                try {
                    check(service.interfaceDescriptor == DESCRIPTOR) { "Unexpected service interface" }
                    val version = Settings.System.getString(
                        appContext.contentResolver, "stylus_pencil_engine_version",
                    )
                    // 0.3.2 增加带包名登记；旧版本保留原登记事务。
                    val withPackage = version == "0.3.2"
                    transact(if (withPackage) REGISTER_WITH_PACKAGE else REGISTER) {
                        writeStrongBinder(callback)
                        if (withPackage) writeString(appContext.packageName)
                    }
                    registered = true
                    val result = setEnabled(true)
                    check(result == 0) { "Enable touch-film returned $result" }
                    enabled = true
                    Log.i(TAG, "Touch-film service ready, engineVersion=$version")
                } catch (error: Exception) {
                    Log.w(TAG, "Cannot register touch-film service", error)
                    unbind()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (connection !== this) return
                remote = null
                registered = false
                enabled = false
                // 绑定仍有效，系统服务重启后会再次回调 onServiceConnected。
                Log.w(TAG, "Touch-film service disconnected")
            }

            override fun onBindingDied(name: ComponentName) {
                if (connection !== this) return
                unbind()
                if (active) handler.postDelayed(reconnect, 500L)
            }

            override fun onNullBinding(name: ComponentName) {
                if (connection !== this) return
                Log.w(TAG, "Touch-film service returned a null binding")
                unbind()
            }
        }
        connection = newConnection
        try {
            val intent = Intent().setComponent(ComponentName(PACKAGE, SERVICE))
            if (!appContext.bindService(intent, newConnection, Context.BIND_AUTO_CREATE)) {
                connection = null
                Log.w(TAG, "Touch-film service unavailable on this system")
            }
        } catch (error: Exception) {
            connection = null
            Log.w(TAG, "Cannot bind touch-film service", error)
        }
    }

    private fun unbind() {
        val oldConnection = connection
        connection = null
        if (enabled) {
            runCatching { setEnabled(false) }.onFailure { Log.w(TAG, "Cannot disable touch-film", it) }
        }
        if (registered) {
            runCatching { transact(UNREGISTER) {} }.onFailure { Log.w(TAG, "Cannot unregister touch-film", it) }
        }
        remote = null
        registered = false
        enabled = false
        if (oldConnection != null) {
            runCatching { appContext.unbindService(oldConnection) }
                .onFailure { Log.w(TAG, "Cannot unbind touch-film service", it) }
        }
    }

    private fun setEnabled(value: Boolean): Int = transact(COMMAND, readResult = true) {
        writeInt(TOUCH_FILM)
        writeInt(if (value) 1 else 0)
    }

    private fun transact(code: Int, readResult: Boolean = false, writeArguments: Parcel.() -> Unit): Int {
        val service = checkNotNull(remote)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeArguments()
            check(service.transact(code, data, reply, 0)) { "Unsupported touch-film transaction $code" }
            reply.readException()
            return if (readResult) reply.readInt() else 0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
