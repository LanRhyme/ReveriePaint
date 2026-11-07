/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.create3DLayer
import com.reverie.paint.core.create3DLayerFromFile
import com.reverie.paint.model.BuiltinModel3D
import com.reverie.paint.ui.theme.Morandi

/**
 * 专为手机与平板优化的 3D 参考模型选择底栏
 */
@Composable
fun Model3DPickerSheet(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val isPhone = config.screenWidthDp < 600 || config.screenWidthDp < config.screenHeightDp

    val objPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            var fileName = "model.glb"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex) ?: "model.glb"
                }
            }
            vm.create3DLayerFromFile(uri, fileName)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
            contentAlignment = if (isPhone) Alignment.BottomCenter else Alignment.Center,
        ) {
            val sheetShape = if (isPhone) {
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            } else {
                RoundedCornerShape(20.dp)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .then(
                        if (isPhone) {
                            Modifier
                                .fillMaxWidth()
                                .wrapContentHeight()
                        } else {
                            Modifier
                                .widthIn(max = 440.dp)
                                .wrapContentHeight()
                        }
                    )
                    .clickable(enabled = false) {} // 拦截点击穿透
                    .clip(sheetShape)
                    .background(Morandi.panel)
                    .border(1.dp, Morandi.border.copy(alpha = 0.4f), sheetShape)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .navigationBarsPadding(),
            ) {
                // 顶部胶囊抓手 (移动端抽屉语言)
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Morandi.icon.copy(alpha = 0.35f))
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 标题栏与副标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.layer_3d_picker_title),
                            color = Morandi.text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.layer_3d_picker_sub),
                            color = Morandi.subText,
                            fontSize = 11.sp,
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Morandi.panelHi.copy(alpha = 0.5f))
                            .clickable(onClick = onDismiss),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = stringResource(R.string.common_close),
                            tint = Morandi.subText,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 预设模型卡片
                val builtins = listOf(
                    Triple(BuiltinModel3D.ANIME_BOY, R.string.layer_3d_model_boy, R.string.layer_3d_model_boy_sub),
                    Triple(BuiltinModel3D.ANIME_GIRL, R.string.layer_3d_model_girl, R.string.layer_3d_model_girl_sub),
                    Triple(BuiltinModel3D.CAT, R.string.layer_3d_model_cat, R.string.layer_3d_model_cat_sub),
                    Triple(BuiltinModel3D.HEAD_MANNEQUIN, R.string.layer_3d_model_head, R.string.layer_3d_model_head_sub),
                    Triple(BuiltinModel3D.CUBE, R.string.layer_3d_model_cube, R.string.layer_3d_model_cube_sub),
                    Triple(BuiltinModel3D.SPHERE, R.string.layer_3d_model_sphere, R.string.layer_3d_model_sphere_sub),
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                ) {
                    items(builtins) { (model, titleRes, subRes) ->
                        val iconRes = when (model) {
                            BuiltinModel3D.ANIME_BOY -> R.drawable.ic_author
                            BuiltinModel3D.ANIME_GIRL -> R.drawable.ic_author
                            BuiltinModel3D.CAT -> R.drawable.ic_heart
                            BuiltinModel3D.HEAD_MANNEQUIN -> R.drawable.ic_focus_view
                            BuiltinModel3D.CUBE -> R.drawable.ic_rect
                            BuiltinModel3D.SPHERE -> R.drawable.ic_circle
                            BuiltinModel3D.CYLINDER -> R.drawable.ic_sliders
                            BuiltinModel3D.BODY_MANNEQUIN -> R.drawable.ic_author
                            BuiltinModel3D.BODY_SIT -> R.drawable.ic_author
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(82.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Morandi.panelHi)
                                .border(1.dp, Morandi.border.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                .clickable {
                                    vm.create3DLayer(model)
                                    onDismiss()
                                }
                                .padding(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Morandi.accent.copy(alpha = 0.15f)),
                                ) {
                                    Icon(
                                        painter = painterResource(iconRes),
                                        contentDescription = null,
                                        tint = Morandi.accent,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }

                                Column(
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = stringResource(titleRes),
                                        color = Morandi.text,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(subRes),
                                        color = Morandi.subText,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 导入外部 OBJ 模型按钮
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Morandi.accent.copy(alpha = 0.14f))
                        .border(1.dp, Morandi.accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .clickable {
                            objPickerLauncher.launch(arrayOf("*/*"))
                        }
                        .padding(horizontal = 16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_import),
                            contentDescription = stringResource(R.string.layer_3d_import_obj),
                            tint = Morandi.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.layer_3d_import_obj),
                            color = Morandi.accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 内置模型 CC0 开源来源标注
                Text(
                    text = stringResource(R.string.layer_3d_credits_tip),
                    color = Morandi.subText.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
    }
}
