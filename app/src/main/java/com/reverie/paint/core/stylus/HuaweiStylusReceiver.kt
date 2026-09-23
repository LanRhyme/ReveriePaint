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
 * Manifest-declared static BroadcastReceiver for Huawei stylus button events.
 * Serves as a persistent fallback during Activity transitions or backgrounding.
 */
class HuaweiStylusReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ReverieHuaweiReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Static receiver caught action: $action, extras: ${intent.extras}")
        val activity = MainActivity.activityInstance
        val vm = MainActivity.currentViewModel
        if (activity != null && vm != null) {
            val driver = vm.stylusDriver ?: vm.getOrCreateStylusDriver(activity)
            val adapter = driver.getAdapter<HuaweiStylusAdapter>()
            adapter?.onBroadcastReceived(action, intent)
        }
    }
}
