/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

enum class ParamUnit(val suffix: String, val isPercent: Boolean = false) {
    PERCENT("%", isPercent = true),
    PIXEL("px"),
    DEGREE("°"),
    RAW(""),
}

/**
 * 莫兰迪平整卡片容器，用于笔刷设置的分组展示
 */
@Composable
fun BrushSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Morandi.panelHi)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 3.dp, height = 12.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Morandi.accent),
                )
                Text(
                    text = title,
                    color = Morandi.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (trailing != null) {
                trailing()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.6.dp)
                .background(Morandi.border.copy(alpha = 0.2f)),
        )

        content()
    }
}

/**
 * 点按滑块数值弹出的高精度输入与步进微调弹窗
 */
@Composable
fun BrushParamInputDialog(
    title: String,
    currentValue: Double,
    min: Double,
    max: Double,
    unit: ParamUnit = ParamUnit.PERCENT,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    val initialDisplayVal = if (unit.isPercent) {
        (currentValue * 100.0).roundToInt().toString()
    } else if (unit == ParamUnit.DEGREE || unit == ParamUnit.PIXEL) {
        currentValue.roundToInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", currentValue)
    }

    var textInput by remember { mutableStateOf(initialDisplayVal) }

    val displayMin = if (unit.isPercent) (min * 100.0).roundToInt() else min.roundToInt()
    val displayMax = if (unit.isPercent) (max * 100.0).roundToInt() else max.roundToInt()

    fun updateValue(delta: Double) {
        val curNum = textInput.toDoubleOrNull() ?: if (unit.isPercent) (currentValue * 100.0) else currentValue
        val next = (curNum + delta).coerceIn(displayMin.toDouble(), displayMax.toDouble())
        textInput = if (unit.isPercent || unit == ParamUnit.DEGREE || unit == ParamUnit.PIXEL) {
            next.roundToInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", next)
        }
    }

    fun applyPreset(presetVal: Double) {
        val v = presetVal.coerceIn(displayMin.toDouble(), displayMax.toDouble())
        textInput = if (unit.isPercent || unit == ParamUnit.DEGREE || unit == ParamUnit.PIXEL) {
            v.roundToInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", v)
        }
    }

    fun submit() {
        val num = textInput.toDoubleOrNull()
        if (num != null) {
            val target = if (unit.isPercent) {
                (num / 100.0).coerceIn(min, max)
            } else {
                num.coerceIn(min, max)
            }
            onConfirm(target)
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "${stringResource(R.string.param_input_title)} · $title",
                color = Morandi.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Number input with minus / plus buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panel)
                            .clickable {
                                val step = if (unit.isPercent) 5.0 else if (unit == ParamUnit.DEGREE) 15.0 else 1.0
                                updateValue(-step)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("-", color = Morandi.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panel)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        BasicTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Morandi.text,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            ),
                            cursorBrush = SolidColor(Morandi.accent),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier.weight(1f),
                        )
                        if (unit.suffix.isNotEmpty()) {
                            Text(
                                text = unit.suffix,
                                color = Morandi.subText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Morandi.panel)
                            .clickable {
                                val step = if (unit.isPercent) 5.0 else if (unit == ParamUnit.DEGREE) 15.0 else 1.0
                                updateValue(step)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+", color = Morandi.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Fast Presets Row
                val presets: List<Double> = when (unit) {
                    ParamUnit.PERCENT -> listOf(0.0, 25.0, 50.0, 75.0, 100.0)
                    ParamUnit.DEGREE -> listOf(0.0, 45.0, 90.0, 180.0, 270.0, 360.0)
                    ParamUnit.PIXEL -> {
                        val pList = listOf(5.0, 15.0, 30.0, 60.0, 120.0, 250.0)
                        pList.filter { it in min..max }
                    }
                    ParamUnit.RAW -> listOf(0.0, 0.25, 0.5, 0.75, 1.0)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    presets.forEach { p ->
                        val label = if (unit.isPercent) "${p.toInt()}%" else if (unit == ParamUnit.DEGREE) "${p.toInt()}°" else "${p.toInt()}"
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morandi.panel)
                                .clickable { applyPreset(p) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = Morandi.subText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            ReTextButton(
                text = stringResource(R.string.common_confirm),
                onClick = { submit() },
                primary = true,
                textColor = Morandi.accent,
            )
        },
        dismissButton = {
            ReTextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                textColor = Morandi.subText,
            )
        },
        containerColor = Morandi.panelHi,
    )
}

/**
 * 现代化莫兰迪笔刷参数滑动条，带标题、点按精调胶囊与丝滑滑块
 */
@Composable
fun ModernParamSlider(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    unit: ParamUnit = ParamUnit.PERCENT,
    modifier: Modifier = Modifier,
    onChange: (Double) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    val formattedValue = when (unit) {
        ParamUnit.PERCENT -> "${(value * 100.0).roundToInt()}%"
        ParamUnit.PIXEL -> "${value.roundToInt()} px"
        ParamUnit.DEGREE -> "${value.roundToInt()}°"
        ParamUnit.RAW -> String.format(java.util.Locale.US, "%.2f", value)
    }

    val fraction = if (max > min) {
        ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                color = Morandi.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
            )

            // Value pill, clickable to open fine-tuning dialog
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Morandi.panel)
                    .noRippleClickable { showDialog = true }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = formattedValue,
                    color = Morandi.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        ReSlider(
            value = fraction,
            onValue = { f ->
                val next = f.toDouble() * (max - min) + min
                onChange(next)
            },
            height = 14,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showDialog) {
        BrushParamInputDialog(
            title = label,
            currentValue = value,
            min = min,
            max = max,
            unit = unit,
            onDismiss = { showDialog = false },
            onConfirm = { onChange(it) },
        )
    }
}
