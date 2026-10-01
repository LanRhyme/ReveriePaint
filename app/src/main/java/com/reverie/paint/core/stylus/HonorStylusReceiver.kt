/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.reverie.paint.MainActivity

/**
 * Manifest-declared static BroadcastReceiver for Honor Magic-Pencil stylus button events.
 * Serves as a persistent fallback during Activity transitions or backgrounding.
 */
class HonorStylusReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ReverieHonorReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        try {
            val action = intent?.action ?: return
            Log.i(TAG, "Static receiver caught action: $action")
            val activity = MainActivity.activityInstance
            val vm = MainActivity.currentViewModel
            if (activity != null && vm != null) {
                try {
                    intent.setExtrasClassLoader(context?.classLoader ?: activity.classLoader)
                } catch (_: Throwable) {}
                val driver = vm.stylusDriver ?: vm.getOrCreateStylusDriver(activity)
                val adapter = driver.getAdapter<HonorStylusAdapter>()
                adapter?.onBroadcastReceived(action, intent)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Safe guard caught error in onReceive: ${t.message}")
        }
    }
}
