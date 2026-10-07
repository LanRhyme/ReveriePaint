/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.home

import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.clearReferenceCache
import com.reverie.paint.core.refreshReferenceCacheSize
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun ReferenceCacheSettings(vm: PaintViewModel) {
    val colors = Theme.current
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.refreshReferenceCacheSize() }
    SettingCategoryTitle(stringResource(R.string.reference_cache_title))
    SettingGroup {
        SettingNavGroupItem(
            icon = Icons.Rounded.DeleteOutline,
            title = stringResource(if (vm.referenceCacheClearing) R.string.reference_cache_clearing
                else R.string.reference_cache_clear),
            summary = stringResource(R.string.reference_cache_size,
                Formatter.formatFileSize(context, vm.referenceCacheBytes)),
            shape = settingGroupShape(0, 1),
            onClick = { if (!vm.referenceCacheClearing) confirm = true },
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = colors.bg,
            titleContentColor = colors.text,
            textContentColor = colors.subText,
            title = { Text(stringResource(R.string.reference_cache_clear)) },
            text = { Text(stringResource(R.string.reference_cache_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.clearReferenceCache() }) {
                    Text(stringResource(R.string.clear), color = colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.common_cancel), color = colors.text)
                }
            },
        )
    }
}
