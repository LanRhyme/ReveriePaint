/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.panels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.R
import com.reverie.paint.core.FillPattern
import com.reverie.paint.core.PatternLibrary
import com.reverie.paint.ui.theme.Morandi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun PatternPickerDialog(
    onSelect: (FillPattern) -> Unit,
    onDismiss: () -> Unit,
    onUseColor: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FillPattern?>(null) }
    LaunchedEffect(Unit) {
        files = withContext(Dispatchers.IO) { PatternLibrary.list(context) }
        busy = false
    }
    fun load(block: () -> FillPattern) {
        busy = true
        failed = false
        scope.launch {
            try {
                val pattern = withContext(Dispatchers.IO) { block() }
                files = withContext(Dispatchers.IO) { PatternLibrary.list(context) }
                selected = pattern
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) load { PatternLibrary.import(context, uri) }
    }
    selected?.let { pattern ->
        PatternScaleDialog(pattern, busy, failed,
            onCancel = { selected = null; failed = false },
            onApply = { percent, smooth ->
                busy = true
                failed = false
                scope.launch {
                    try {
                        val prepared = withContext(Dispatchers.IO) {
                            PatternLibrary.scaled(pattern, percent, smooth)
                        }
                        onSelect(prepared)
                        onDismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            })
        return
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(color = Morandi.panel, shape = MaterialTheme.shapes.large) {
            Column(Modifier.widthIn(max = 480.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.pattern_title), style = MaterialTheme.typography.titleLarge, color = Morandi.text)
                Text(stringResource(R.string.pattern_hint), color = Morandi.subText)
                Button(onClick = { importer.launch("image/*") }, enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Morandi.accent, contentColor = Morandi.onAccent),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pattern_import))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Morandi.accent)
                if (failed) Text(stringResource(R.string.pattern_import_failed), color = Morandi.text)
                if (files.isEmpty() && !busy) {
                    Text(stringResource(R.string.pattern_empty), color = Morandi.subText,
                        modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    LazyVerticalGrid(columns = GridCells.Adaptive(88.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(files, key = { it.absolutePath }) { file ->
                            PatternThumbnail(file, !busy) { load { PatternLibrary.load(context, file) } }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (onUseColor != null) TextButton(onClick = { onUseColor(); onDismiss() }, enabled = !busy) {
                        Text(stringResource(R.string.pattern_use_color), color = Morandi.accent)
                    }
                    TextButton(onClick = onDismiss, enabled = !busy) {
                        Text(stringResource(R.string.common_close), color = Morandi.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun PatternThumbnail(file: File, enabled: Boolean, onClick: () -> Unit) {
    val preview by produceState<ImageBitmap?>(null, file.absolutePath) {
        value = withContext(Dispatchers.IO) { runCatching { PatternLibrary.thumbnail(file)?.asImageBitmap() }.getOrNull() }
    }
    Column(Modifier.clickable(enabled = enabled, onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Morandi.panelHi), contentAlignment = Alignment.Center) {
            preview?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
        Text(file.nameWithoutExtension.take(24), color = Morandi.text, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}
