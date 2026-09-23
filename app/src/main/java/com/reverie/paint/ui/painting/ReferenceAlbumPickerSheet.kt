/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.reverie.paint.R
import com.reverie.paint.core.AlbumPhoto
import com.reverie.paint.core.MAX_REFERENCE_IMAGES
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.ReferenceAlbumManager
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReferenceAlbumPickerSheet(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
    onConfirm: (List<Uri>) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val permissionName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permissionName) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    // Persisted selected URIs state in album
    val selectedUris = remember {
        mutableStateListOf<Uri>().apply {
            addAll(vm.referenceAlbumSelectedUris)
        }
    }

    var albumPhotos by remember { mutableStateOf<List<AlbumPhoto>>(emptyList()) }
    var isLoadingPhotos by remember { mutableStateOf(false) }

    // Fallback system file picker
    val systemPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            for (u in uris) {
                if (!selectedUris.contains(u)) {
                    if (selectedUris.size < MAX_REFERENCE_IMAGES) {
                        selectedUris.add(u)
                    }
                }
            }
        }
    }

    // Query album photos when permission is granted
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            isLoadingPhotos = true
            val photos = ReferenceAlbumManager.queryAlbumPhotos(context)
            albumPhotos = photos
            isLoadingPhotos = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 340.dp, max = 560.dp)
                .fillMaxWidth(0.92f)
                .heightIn(min = 400.dp, max = 680.dp)
                .fillMaxHeight(0.86f)
                .shadow(24.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(Morandi.panelHi)
                .glassBorder(RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Top Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Close (X)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = stringResource(R.string.common_close),
                            tint = Morandi.icon,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Center: Title & Selection Count
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.reference_album_title),
                            color = Morandi.text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morandi.panel)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.reference_album_selected_count,
                                    selectedUris.size
                                ),
                                color = if (selectedUris.isNotEmpty()) Morandi.accent else Morandi.subText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Right: Done Button
                    ReTextButton(
                        text = stringResource(R.string.common_confirm),
                        onClick = {
                            onConfirm(selectedUris.toList())
                        },
                        primary = selectedUris.isNotEmpty(),
                        textColor = Morandi.subText,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Body: Content or Permission Request
                if (!hasPermission) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.reference_album_permission_needed),
                                color = Morandi.subText,
                                fontSize = 13.sp
                            )
                            ReTextButton(
                                text = stringResource(R.string.reference_album_grant_permission),
                                onClick = { permissionLauncher.launch(permissionName) },
                                primary = true
                            )
                            ReTextButton(
                                text = stringResource(R.string.reference_album_system_picker),
                                onClick = { systemPickerLauncher.launch("image/*") },
                                textColor = Morandi.accent
                            )
                        }
                    }
                } else if (isLoadingPhotos) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Morandi.accent,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 82.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        // Tile 0: System File Picker shortcut
                        item {
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Morandi.panel)
                                    .border(
                                        width = 1.dp,
                                        color = Morandi.border,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        systemPickerLauncher.launch("image/*")
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_folder_plus),
                                        contentDescription = stringResource(R.string.reference_album_system_picker),
                                        tint = Morandi.accent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.reference_album_system_picker),
                                        color = Morandi.subText,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        // Album Media Items
                        items(albumPhotos, key = { it.id }) { photo ->
                            val isSelected = selectedUris.contains(photo.uri)
                            val selectionIndex = if (isSelected) selectedUris.indexOf(photo.uri) + 1 else 0

                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF141518))
                                    .border(
                                        width = if (isSelected) 2.5.dp else 0.5.dp,
                                        color = if (isSelected) Morandi.accent else Morandi.border.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        if (isSelected) {
                                            selectedUris.remove(photo.uri)
                                        } else {
                                            if (selectedUris.size < MAX_REFERENCE_IMAGES) {
                                                selectedUris.add(photo.uri)
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.reference_album_max_hint),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                            ) {
                                AlbumThumbnailItem(
                                    uri = photo.uri,
                                    modifier = Modifier.fillMaxSize()
                                )

                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Morandi.accent.copy(alpha = 0.12f))
                                    )
                                }

                                // Selection Badge (Top-Right)
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) Morandi.accent
                                            else Color(0x73000000)
                                        )
                                        .border(
                                            width = if (isSelected) 0.dp else 1.5.dp,
                                            color = if (isSelected) Color.Transparent else Color.White.copy(alpha = 0.85f),
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Text(
                                            text = selectionIndex.toString(),
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumThumbnailItem(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            val bmp = ReferenceAlbumManager.loadThumbnail(context.contentResolver, uri)
            withContext(Dispatchers.Main) {
                bitmap = bmp
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(Color(0xFF1E2024))
        )
    }
}
