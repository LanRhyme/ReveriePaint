/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint

import android.app.Application
import com.reverie.paint.core.CrashHandler

class ReverieApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
        com.reverie.paint.core.FontManager.appContext = this
    }
}
