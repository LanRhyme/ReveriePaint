/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.content.res.Configuration

/**
 * Device capability and form-factor detection utilities.
 */
object DeviceUtils {

    /**
     * Determines whether the given device screen configuration corresponds to a tablet.
     * Pure logic: smallest screen width (sw) >= 600dp is the standard Android tablet definition.
     * When sw is undefined or invalid (<= 0), falls back to screenLayout size >= LARGE (3).
     */
    fun isTabletMetrics(smallestScreenWidthDp: Int, screenLayoutSize: Int = 0): Boolean {
        return if (smallestScreenWidthDp > 0) {
            smallestScreenWidthDp >= 600
        } else {
            // Configuration.SCREENLAYOUT_SIZE_LARGE is 3, SCREENLAYOUT_SIZE_XLARGE is 4
            screenLayoutSize >= Configuration.SCREENLAYOUT_SIZE_LARGE
        }
    }

    /**
     * Checks if the device is a tablet based on the given [Context]'s resources configuration.
     */
    fun isTablet(context: Context): Boolean {
        val config = context.resources.configuration
        val sw = config.smallestScreenWidthDp
        val layoutSize = config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        return isTabletMetrics(sw, layoutSize)
    }
}
