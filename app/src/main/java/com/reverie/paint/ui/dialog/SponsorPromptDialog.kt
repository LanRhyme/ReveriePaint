/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.dialog

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReButton
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Theme

@Composable
fun SponsorPromptDialog(
    vm: PaintViewModel,
    onViewSponsorList: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val launchCount = vm.launchCount
    val projectCount = maxOf(vm.createdCanvasCount, vm.exportCount, vm.projects.size)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .widthIn(max = 480.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.panelHi)
                .border(1.dp, colors.border, RoundedCornerShape(24.dp))
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Heart badge
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.16f))
                        .border(1.5.dp, colors.accent.copy(alpha = 0.40f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_heart),
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Title
                Text(
                    text = stringResource(R.string.sponsor_prompt_title),
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(8.dp))

                // Milestone stat tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.panel)
                        .border(0.8.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.sponsor_prompt_milestone, launchCount, projectCount),
                        color = colors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Body text
                Text(
                    text = stringResource(R.string.sponsor_prompt_body),
                    color = colors.subText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(20.dp))

                // Action buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ReButton(
                        text = stringResource(R.string.sponsor_prompt_action_afdian),
                        primary = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            try {
                                uriHandler.openUri("https://afdian.com/a/LanRhyme")
                            } catch (_: Exception) {}
                            vm.suppressSponsorPrompt()
                            Toast.makeText(context, R.string.sponsor_prompt_toast_suppressed, Toast.LENGTH_SHORT).show()
                        },
                    )

                    ReButton(
                        text = stringResource(R.string.sponsor_prompt_action_view_list),
                        primary = false,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onDismiss()
                            onViewSponsorList()
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Bottom dismiss actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReTextButton(
                        text = stringResource(R.string.sponsor_prompt_next_time),
                        textColor = colors.subText,
                        fontSize = 12.sp,
                        onClick = {
                            vm.postponeSponsorPrompt()
                            Toast.makeText(context, R.string.sponsor_prompt_toast_postponed, Toast.LENGTH_SHORT).show()
                        },
                    )

                    ReTextButton(
                        text = stringResource(R.string.sponsor_prompt_never),
                        textColor = colors.subText.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        onClick = {
                            vm.suppressSponsorPrompt()
                            Toast.makeText(context, R.string.sponsor_prompt_toast_suppressed, Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }
}
