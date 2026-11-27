/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.BuildConfig
import com.reverie.paint.R
import com.reverie.paint.core.LanguageManager
import com.reverie.paint.model.ReleaseNotesLocalizer
import com.reverie.paint.ui.components.MarkdownView
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ChangelogDialog(
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val context = LocalContext.current
    val preferChinese = ReleaseNotesLocalizer.shouldPreferChinese(LanguageManager.getLocale())
    var markdown by remember(preferChinese) { mutableStateOf<String?>(null) }

    LaunchedEffect(preferChinese) {
        val loaded = withContext(Dispatchers.IO) {
            val candidates = if (preferChinese) {
                listOf("CHANGELOG_ZH.md", "CHANGELOG.md")
            } else {
                listOf("CHANGELOG.md", "CHANGELOG_ZH.md")
            }
            var content = ""
            for (assetName in candidates) {
                val read = runCatching {
                    context.assets.open(assetName).bufferedReader().use { it.readText() }
                }.getOrNull()
                if (!read.isNullOrBlank()) {
                    content = ReleaseNotesLocalizer.stripDocumentHeader(read)
                    break
                }
            }
            content
        }
        markdown = loaded
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 520.dp)
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Morandi.panel)
                .padding(22.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Morandi.accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.NewReleases,
                                contentDescription = null,
                                tint = Morandi.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.settings_changelog_title),
                                color = colors.text,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.changelog_dialog_subtitle, BuildConfig.VERSION_NAME),
                                color = Morandi.subText,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Morandi.panelHi)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.common_cancel),
                            tint = colors.subText,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = 120.dp, max = 430.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Morandi.panelHi)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val content = markdown
                    if (content == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = colors.accent,
                            )
                        }
                    } else {
                        MarkdownView(
                            markdown = content.ifBlank { stringResource(R.string.changelog_load_empty) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ReTextButton(
                        text = stringResource(R.string.common_confirm),
                        onClick = onDismiss,
                        textColor = colors.accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
