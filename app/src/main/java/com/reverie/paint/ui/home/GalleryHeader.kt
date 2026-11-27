/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.Project
import com.reverie.paint.model.UiAnimationSpeed
import com.reverie.paint.ui.components.LiquidSegmentedBar
import com.reverie.paint.ui.components.ReDropdownMenu
import com.reverie.paint.ui.components.ReDropdownMenuItem
import com.reverie.paint.ui.components.liquidHighlight
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.painting.collab.CollabStatusButton
import com.reverie.paint.ui.theme.AppColors
import com.reverie.paint.ui.theme.Motion
import com.reverie.paint.ui.theme.blendColors
import dev.chrisbanes.haze.HazeState

internal fun AppColors.isDarkTheme(): Boolean = panel.luminance() < 0.5f

internal fun studioHairlineBrush(colors: AppColors): Brush =
    if (colors.isDarkTheme()) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.16f),
                Color.White.copy(alpha = 0.04f),
            ),
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.65f),
                Color.Black.copy(alpha = 0.08f),
            ),
        )
    }

internal fun artboardHairlineBrush(colors: AppColors): Brush =
    if (colors.isDarkTheme()) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.18f),
                Color.White.copy(alpha = 0.06f),
            ),
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.09f),
                Color.Black.copy(alpha = 0.06f),
            ),
        )
    }

@Composable
internal fun Modifier.studioDialogSurface(
    colors: AppColors,
    shape: Shape = RoundedCornerShape(24.dp),
): Modifier {
    val isDark = colors.isDarkTheme()
    val enter = remember {
        Animatable(if (Motion.currentSpeed == UiAnimationSpeed.OFF) 1f else 0f)
    }
    LaunchedEffect(Unit) {
        if (Motion.currentSpeed == UiAnimationSpeed.OFF) {
            enter.snapTo(1f)
        } else {
            enter.animateTo(1f, Motion.enterSpring())
        }
    }
    return this
        .graphicsLayer {
            val p = enter.value
            val s = 0.94f + 0.06f * p
            scaleX = s
            scaleY = s
            alpha = p.coerceIn(0f, 1f)
        }
        .shadow(
            elevation = if (isDark) 24.dp else 18.dp,
            shape = shape,
            spotColor = Color.Black.copy(alpha = if (isDark) 0.48f else 0.16f),
            ambientColor = Color.Black.copy(alpha = 0.10f),
            clip = false,
        )
        .clip(shape)
        .background(colors.panel)
        .border(width = 0.75.dp, brush = studioHairlineBrush(colors), shape = shape)
}

@Composable
internal fun GalleryMicroBadge(
    text: String,
    @DrawableRes iconRes: Int? = null,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(containerColor)
            .border(0.5.dp, borderColor, CircleShape)
            .padding(horizontal = 6.5.dp, vertical = 2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(10.dp),
            )
        }
        Text(
            text = text,
            color = contentColor,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
internal fun GalleryMenuDivider(colors: AppColors) {
    val dividerTint = (if (colors.isDarkTheme()) Color.White else Color.Black).copy(alpha = 0.08f)
    HorizontalDivider(
        thickness = 0.5.dp,
        color = dividerTint,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
    )
}

@Composable
internal fun GalleryHeaderIconButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    colors: AppColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = colors.icon,
) {
    val interaction = remember { MutableInteractionSource() }
    val highlightColor = if (colors.isDarkTheme()) Color.White else colors.accent
    Box(
        modifier = modifier
            .size(34.dp)
            .pressScale(interaction, pressedScale = 0.86f)
            .clip(CircleShape)
            .liquidHighlight(interaction, highlightColor, radius = 20.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun GalleryFloatingHeader(
    vm: PaintViewModel,
    colors: AppColors,
    hazeState: HazeState?,
    currentFolder: Project?,
    itemCount: Int,
    isSelectMode: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    isSearchActive: Boolean,
    searchQuery: String,
    isDragging: Boolean,
    isHoveringExitFolder: Boolean,
    isScrolled: Boolean,
    hasCustomBg: Boolean,
    showMoreMenu: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onExitFolder: () -> Unit,
    onFolderHeaderBounds: (Rect) -> Unit,
    onToggleSelectAll: () -> Unit,
    onDoneSelect: () -> Unit,
    onToggleMoreMenu: (Boolean) -> Unit,
    onImportClick: () -> Unit,
    onEnterSelectMode: () -> Unit,
    onNewStackClick: () -> Unit,
    onAutoSaveHistoryClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onSelectSortMode: (String) -> Unit,
    onSelectCardSize: (String) -> Unit,
    onToggleShowCardMeta: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWide = LocalConfiguration.current.screenWidthDp >= 600
    val horizontalPad = 16.dp
    val scrimTopAlpha by animateFloatAsState(
        targetValue = when {
            hasCustomBg && isScrolled -> 0.24f
            hasCustomBg -> 0f
            isScrolled -> 0.52f
            else -> 0f
        },
        animationSpec = tween(220),
        label = "GalleryTopScrimTop",
    )
    val scrimMidAlpha by animateFloatAsState(
        targetValue = when {
            hasCustomBg && isScrolled -> 0.08f
            hasCustomBg -> 0f
            isScrolled -> 0.18f
            else -> 0f
        },
        animationSpec = tween(220),
        label = "GalleryTopScrimMid",
    )
    val scrimColor = if (hasCustomBg) Color.Black else colors.bg

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        scrimColor.copy(alpha = scrimTopAlpha),
                        scrimColor.copy(alpha = scrimMidAlpha),
                        Color.Transparent,
                    ),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = horizontalPad, vertical = 10.dp),
        ) {
            AnimatedContent(
                targetState = isSearchActive && !isSelectMode,
                transitionSpec = {
                    when (Motion.currentSpeed) {
                        UiAnimationSpeed.OFF -> EnterTransition.None togetherWith ExitTransition.None
                        UiAnimationSpeed.FAST -> {
                            (fadeIn(tween(90)) + scaleIn(tween(90), initialScale = 0.95f, transformOrigin = TransformOrigin(0.92f, 0.5f)))
                                .togetherWith(fadeOut(tween(70)) + scaleOut(tween(70), targetScale = 0.95f, transformOrigin = TransformOrigin(0.92f, 0.5f)))
                        }
                        UiAnimationSpeed.NORMAL -> {
                            (fadeIn(tween(180)) + scaleIn(Motion.enterSpring(), initialScale = 0.92f, transformOrigin = TransformOrigin(0.92f, 0.5f)))
                                .togetherWith(fadeOut(Motion.exitTween(110)) + scaleOut(Motion.exitTween(130), targetScale = 0.94f, transformOrigin = TransformOrigin(0.92f, 0.5f)))
                        }
                    }
                },
                label = "GalleryHeaderSearchSwitch",
            ) { searching ->
                if (searching) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        GalleryFloatingSearchBar(
                            query = searchQuery,
                            resultCount = itemCount,
                            onQueryChange = onSearchQueryChange,
                            onClose = onCloseSearch,
                            colors = colors,
                            hazeState = hazeState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 520.dp),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            AnimatedContent(
                                targetState = when {
                                    isSelectMode -> "SELECT"
                                    currentFolder != null -> "FOLDER"
                                    else -> "GALLERY"
                                },
                                transitionSpec = {
                                    when (Motion.currentSpeed) {
                                        UiAnimationSpeed.OFF -> EnterTransition.None togetherWith ExitTransition.None
                                        UiAnimationSpeed.FAST -> {
                                            if (targetState == "FOLDER") {
                                                (slideInHorizontally(tween(90)) { it / 4 } + fadeIn(tween(90)))
                                                    .togetherWith(slideOutHorizontally(tween(70)) { -it / 4 } + fadeOut(tween(70)))
                                            } else if (initialState == "FOLDER") {
                                                (slideInHorizontally(tween(90)) { -it / 4 } + fadeIn(tween(90)))
                                                    .togetherWith(slideOutHorizontally(tween(70)) { it / 4 } + fadeOut(tween(70)))
                                            } else {
                                                fadeIn(tween(90)).togetherWith(fadeOut(tween(70)))
                                            }
                                        }
                                        UiAnimationSpeed.NORMAL -> {
                                            if (targetState == "FOLDER") {
                                                (slideInHorizontally(Motion.enterSpring()) { it / 3 } + fadeIn(tween(200)))
                                                    .togetherWith(
                                                        slideOutHorizontally(tween(170, easing = FastOutSlowInEasing)) { -it / 3 } +
                                                            fadeOut(tween(150)),
                                                    )
                                            } else if (initialState == "FOLDER") {
                                                (slideInHorizontally(Motion.enterSpring()) { -it / 3 } + fadeIn(tween(200)))
                                                    .togetherWith(
                                                        slideOutHorizontally(tween(170, easing = FastOutSlowInEasing)) { it / 3 } +
                                                            fadeOut(tween(150)),
                                                    )
                                            } else {
                                                fadeIn(tween(180)).togetherWith(fadeOut(tween(140)))
                                            }
                                        }
                                    }
                                },
                                label = "GalleryHeaderTitleState",
                            ) { state ->
                            when (state) {
                                "FOLDER" -> {
                                    val folder = currentFolder
                                    if (folder != null) {
                                        FolderBackPill(
                                            folderName = folder.name,
                                            itemCount = itemCount,
                                            isDragging = isDragging,
                                            isExitDrop = isHoveringExitFolder,
                                            colors = colors,
                                            hazeState = hazeState,
                                            onExitFolder = onExitFolder,
                                            onBounds = onFolderHeaderBounds,
                                        )
                                    }
                                }

                                "SELECT" -> {
                                    Row(
                                        modifier = Modifier
                                            .height(42.dp)
                                            .homeLiquidGlassSurface(
                                                colors = colors,
                                                shape = CircleShape,
                                                hazeState = hazeState,
                                            )
                                            .padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(colors.accent),
                                        )
                                        AnimatedContent(
                                            targetState = selectedCount,
                                            transitionSpec = {
                                                if (targetState > initialState) {
                                                    (slideInVertically(tween(170)) { -it / 2 } + fadeIn(tween(170)))
                                                        .togetherWith(slideOutVertically(tween(130)) { it / 2 } + fadeOut(tween(130)))
                                                } else {
                                                    (slideInVertically(tween(170)) { it / 2 } + fadeIn(tween(170)))
                                                        .togetherWith(slideOutVertically(tween(130)) { -it / 2 } + fadeOut(tween(130)))
                                                }
                                            },
                                            label = "GallerySelectedCountAnim",
                                        ) { count ->
                                            Text(
                                                text = stringResource(R.string.gallery_selected_count, count),
                                                color = colors.text,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                style = TextStyle(fontFeatureSettings = "tnum"),
                                            )
                                        }
                                    }
                                }

                                else -> {
                                    Row(
                                        modifier = Modifier
                                            .height(42.dp)
                                            .homeLiquidGlassSurface(
                                                colors = colors,
                                                shape = CircleShape,
                                                hazeState = hazeState,
                                            )
                                            .padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_reverie_logo),
                                            contentDescription = null,
                                            tint = colors.accent,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            text = stringResource(R.string.gallery_title),
                                            color = colors.text,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = (-0.2).sp,
                                            maxLines = 1,
                                        )
                                        AnimatedContent(
                                            targetState = itemCount,
                                            transitionSpec = {
                                                fadeIn(tween(200, easing = FastOutSlowInEasing))
                                                    .togetherWith(fadeOut(tween(130)))
                                            },
                                            label = "GalleryItemCountAnim",
                                        ) { count ->
                                            Box(
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(colors.panelHi.copy(alpha = 0.82f))
                                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                                            ) {
                                                Text(
                                                    text = "$count",
                                                    color = colors.subText,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    style = TextStyle(fontFeatureSettings = "tnum"),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        }

                        Spacer(Modifier.width(12.dp))

                        AnimatedContent(
                            targetState = isSelectMode,
                            transitionSpec = {
                                when (Motion.currentSpeed) {
                                    UiAnimationSpeed.OFF -> EnterTransition.None togetherWith ExitTransition.None
                                    UiAnimationSpeed.FAST -> {
                                        (fadeIn(tween(90)) + scaleIn(tween(90), initialScale = 0.94f, transformOrigin = TransformOrigin(1f, 0.5f)))
                                            .togetherWith(fadeOut(tween(70)) + scaleOut(tween(70), targetScale = 0.94f, transformOrigin = TransformOrigin(1f, 0.5f)))
                                    }
                                    UiAnimationSpeed.NORMAL -> {
                                        (fadeIn(tween(180)) + scaleIn(Motion.enterSpring(), initialScale = 0.90f, transformOrigin = TransformOrigin(1f, 0.5f)))
                                            .togetherWith(fadeOut(Motion.exitTween(120)) + scaleOut(Motion.exitTween(140), targetScale = 0.92f, transformOrigin = TransformOrigin(1f, 0.5f)))
                                    }
                                }
                            },
                            label = "GalleryHeaderRightActionSwitch",
                        ) { selecting ->
                            if (selecting) {
                                SelectModeHeaderCapsule(
                                    colors = colors,
                                    hazeState = hazeState,
                                    allSelected = allSelected,
                                    onToggleSelectAll = onToggleSelectAll,
                                    onDone = onDoneSelect,
                                )
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (isWide) {
                                        val selectPillInteraction = remember { MutableInteractionSource() }
                                        Row(
                                            modifier = Modifier
                                                .height(42.dp)
                                                .pressScale(selectPillInteraction, pressedScale = 0.94f)
                                                .homeLiquidGlassSurface(
                                                    colors = colors,
                                                    shape = CircleShape,
                                                    hazeState = hazeState,
                                                )
                                                .liquidHighlight(
                                                    selectPillInteraction,
                                                    if (colors.isDarkTheme()) Color.White else colors.accent,
                                                    radius = 30.dp,
                                                )
                                                .clickable(
                                                    interactionSource = selectPillInteraction,
                                                    indication = null,
                                                    onClick = onEnterSelectMode,
                                                )
                                                .padding(horizontal = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_circle_check),
                                                contentDescription = null,
                                                tint = colors.icon,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Text(
                                                text = stringResource(R.string.gallery_select),
                                                color = colors.text,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .height(42.dp)
                                            .homeLiquidGlassSurface(
                                                colors = colors,
                                                shape = CircleShape,
                                                hazeState = hazeState,
                                            )
                                            .padding(horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CollabStatusButton(
                                            vm = vm,
                                            compact = !vm.collab.isActive,
                                        )

                                        GalleryHeaderIconButton(
                                            iconRes = R.drawable.ic_search,
                                            contentDescription = stringResource(R.string.common_search),
                                            colors = colors,
                                            onClick = onOpenSearch,
                                        )

                                        Box {
                                            GalleryHeaderIconButton(
                                                iconRes = R.drawable.ic_dots_vertical,
                                                contentDescription = stringResource(R.string.common_more),
                                                colors = colors,
                                                onClick = { onToggleMoreMenu(true) },
                                            )

                                            ReDropdownMenu(
                                                expanded = showMoreMenu,
                                                onDismissRequest = { onToggleMoreMenu(false) },
                                            ) {
                                                ReDropdownMenuItem(
                                                    text = stringResource(R.string.common_import),
                                                    icon = R.drawable.ic_import,
                                                    onClick = {
                                                        onToggleMoreMenu(false)
                                                        onImportClick()
                                                    },
                                                )
                                                ReDropdownMenuItem(
                                                    text = stringResource(R.string.gallery_select),
                                                    icon = R.drawable.ic_circle_check,
                                                    onClick = {
                                                        onToggleMoreMenu(false)
                                                        onEnterSelectMode()
                                                    },
                                                )
                                                ReDropdownMenuItem(
                                                    text = stringResource(R.string.gallery_new_stack),
                                                    icon = R.drawable.ic_folder_plus,
                                                    onClick = {
                                                        onToggleMoreMenu(false)
                                                        onNewStackClick()
                                                    },
                                                )

                                                GalleryMenuDivider(colors)

                                                Text(
                                                    text = stringResource(R.string.gallery_card_size_title),
                                                    color = colors.subText,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                                )

                                                GalleryCardSizeSelector(
                                                    selectedSize = vm.galleryCardSize,
                                                    colors = colors,
                                                    onSelectSize = onSelectCardSize,
                                                )

                                                ReDropdownMenuItem(
                                                    text = stringResource(R.string.gallery_show_card_meta),
                                                    icon = R.drawable.ic_info_circle,
                                                    selected = vm.galleryShowCardMeta,
                                                    trailingIcon = if (vm.galleryShowCardMeta) {
                                                        {
                                                            Icon(
                                                                painter = painterResource(R.drawable.ic_check),
                                                                contentDescription = null,
                                                                tint = colors.accent,
                                                                modifier = Modifier.size(15.dp),
                                                            )
                                                        }
                                                    } else null,
                                                    onClick = { onToggleShowCardMeta(!vm.galleryShowCardMeta) },
                                                )

                                                GalleryMenuDivider(colors)

                                                Text(
                                                    text = stringResource(R.string.gallery_sort_title),
                                                    color = colors.subText,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                                )

                                                SortMenuOption(
                                                    label = stringResource(R.string.gallery_sort_modified),
                                                    selected = vm.gallerySortMode == "MODIFIED",
                                                    colors = colors,
                                                    onClick = {
                                                        onToggleMoreMenu(false)
                                                        onSelectSortMode("MODIFIED")
                                                    },
                                                )
                                                SortMenuOption(
                                                    label = stringResource(R.string.gallery_sort_name),
                                                    selected = vm.gallerySortMode == "NAME",
                                                    colors = colors,
                                                    onClick = {
                                                        onToggleMoreMenu(false)
                                                        onSelectSortMode("NAME")
                                                    },
                                                )
                                                SortMenuOption(
                                                    label = stringResource(R.string.gallery_sort_strokes),
                                                    selected = vm.gallerySortMode == "STROKES",
                                                    colors = colors,
                                                    onClick = {
                                                        onToggleMoreMenu(false)
                                                        onSelectSortMode("STROKES")
                                                    },
                                                )

                                                GalleryMenuDivider(colors)

                                                ReDropdownMenuItem(
                                                    text = stringResource(R.string.auto_save_history_title),
                                                    icon = R.drawable.ic_clock,
                                                    onClick = {
                                                        onToggleMoreMenu(false)
                                                        onAutoSaveHistoryClick()
                                                    },
                                                )
                                                ReDropdownMenuItem(
                                                    text = stringResource(R.string.gallery_refresh),
                                                    icon = R.drawable.ic_refresh,
                                                    onClick = {
                                                        onToggleMoreMenu(false)
                                                        onRefreshClick()
                                                    },
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
    }
}

@Composable
private fun SortMenuOption(
    label: String,
    selected: Boolean,
    colors: AppColors,
    onClick: () -> Unit,
) {
    ReDropdownMenuItem(
        text = label,
        selected = selected,
        trailingIcon = if (selected) {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(15.dp),
                )
            }
        } else null,
        onClick = onClick,
    )
}

@Composable
private fun GalleryCardSizeSelector(
    selectedSize: String,
    colors: AppColors,
    onSelectSize: (String) -> Unit,
) {
    val options = listOf(
        "COMPACT" to stringResource(R.string.gallery_card_size_compact),
        "STANDARD" to stringResource(R.string.gallery_card_size_standard),
        "LARGE" to stringResource(R.string.gallery_card_size_large),
    )
    val selectedIndex = options.indexOfFirst { it.first == selectedSize }.coerceAtLeast(0)
    LiquidSegmentedBar(
        count = options.size,
        selectedIndex = selectedIndex,
        onSelect = { index ->
            options.getOrNull(index)?.let { onSelectSize(it.first) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(min = 168.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .height(34.dp),
        trackColor = colors.panel.copy(alpha = 0.72f),
        pillColor = colors.accent,
        trackCornerRadius = 10.dp,
        pillCornerRadius = 7.dp,
        trackPadding = 3.dp,
    ) { index, isActive ->
        Text(
            text = options[index].second,
            color = if (isActive) colors.onAccent else colors.subText,
            fontSize = 11.5.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun FolderBackPill(
    folderName: String,
    itemCount: Int,
    isDragging: Boolean,
    isExitDrop: Boolean,
    colors: AppColors,
    hazeState: HazeState?,
    onExitFolder: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pillScale by animateFloatAsState(
        targetValue = if (isExitDrop) 1.04f else 1f,
        animationSpec = Motion.snapBouncy,
        label = "FolderPillDropScale",
    )
    val dragHintAlpha = if (Motion.currentSpeed == UiAnimationSpeed.OFF) {
        0.85f
    } else {
        val pulseTransition = rememberInfiniteTransition(label = "FolderExitHintPulse")
        val animated by pulseTransition.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.92f,
            animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "FolderExitHintAlpha",
        )
        animated
    }

    val pillShape = CircleShape
    val surfaceModifier = if (isExitDrop) {
        Modifier
            .shadow(10.dp, pillShape, spotColor = colors.accent.copy(alpha = 0.40f), clip = false)
            .clip(pillShape)
            .background(colors.accent)
            .border(1.5.dp, colors.onAccent.copy(alpha = 0.55f), pillShape)
    } else {
        Modifier
            .homeLiquidGlassSurface(colors = colors, shape = pillShape, hazeState = hazeState)
            .then(
                if (isDragging) {
                    Modifier.border(1.5.dp, colors.accent.copy(alpha = dragHintAlpha), pillShape)
                } else {
                    Modifier
                },
            )
    }

    val fgColor by animateColorAsState(
        targetValue = if (isExitDrop) colors.onAccent else colors.text,
        animationSpec = tween(150),
        label = "FolderPillFg",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .graphicsLayer {
                scaleX = pillScale
                scaleY = pillScale
            }
            .height(42.dp)
            .pressScale(interaction, pressedScale = 0.95f)
            .then(surfaceModifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onExitFolder,
            )
            .padding(horizontal = 12.dp),
    ) {
        Icon(
            painter = painterResource(if (isExitDrop || isDragging) R.drawable.ic_external_link else R.drawable.ic_arrow_left),
            contentDescription = stringResource(R.string.common_back),
            tint = if (isDragging && !isExitDrop) colors.accent else fgColor,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                isExitDrop -> stringResource(R.string.gallery_drag_move_out)
                isDragging -> stringResource(R.string.gallery_drag_exit_hint)
                else -> folderName
            },
            color = if (isDragging && !isExitDrop) colors.accent else fgColor,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        if (!isExitDrop && !isDragging) {
            Spacer(Modifier.width(7.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.panelHi.copy(alpha = 0.85f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "$itemCount",
                    color = colors.subText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
            }
        }
    }
}

@Composable
private fun SelectModeHeaderCapsule(
    colors: AppColors,
    hazeState: HazeState?,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onDone: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val selectAllInteraction = remember { MutableInteractionSource() }
    val doneInteraction = remember { MutableInteractionSource() }
    val dividerColor = (if (colors.isDarkTheme()) Color.White else Color.Black).copy(alpha = 0.10f)

    Row(
        modifier = Modifier
            .height(42.dp)
            .homeLiquidGlassSurface(
                colors = colors,
                shape = CircleShape,
                hazeState = hazeState,
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .pressScale(selectAllInteraction, pressedScale = 0.94f)
                .clip(CircleShape)
                .clickable(
                    interactionSource = selectAllInteraction,
                    indication = null,
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleSelectAll()
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (allSelected) stringResource(R.string.gallery_deselect_all) else stringResource(R.string.gallery_select_all),
                color = colors.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Box(
            modifier = Modifier
                .width(0.5.dp)
                .height(16.dp)
                .background(dividerColor),
        )

        Box(
            modifier = Modifier
                .pressScale(doneInteraction, pressedScale = 0.94f)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.14f))
                .clickable(
                    interactionSource = doneInteraction,
                    indication = null,
                    onClick = onDone,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = stringResource(R.string.common_done),
                color = colors.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun GalleryFloatingSearchBar(
    query: String,
    resultCount: Int,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    colors: AppColors,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Row(
        modifier = modifier
            .height(44.dp)
            .homeLiquidGlassSurface(
                colors = colors,
                shape = CircleShape,
                hazeState = hazeState,
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        GalleryHeaderIconButton(
            iconRes = R.drawable.ic_arrow_left,
            contentDescription = stringResource(R.string.common_back),
            colors = colors,
            tint = colors.text,
            onClick = {
                focusManager.clearFocus()
                onClose()
            },
        )

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.gallery_search_hint),
                    color = colors.subText.copy(alpha = 0.72f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                textStyle = TextStyle(
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                ),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }

        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.panelHi)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "$resultCount",
                    color = colors.subText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
            }

            GalleryHeaderIconButton(
                iconRes = R.drawable.ic_x,
                contentDescription = stringResource(R.string.gallery_empty_clear_search),
                colors = colors,
                tint = colors.subText,
                onClick = { onQueryChange("") },
            )
        }
    }
}

@Composable
internal fun GalleryEmptyState(
    colors: AppColors,
    isSearching: Boolean,
    isInFolder: Boolean,
    onCreateNew: () -> Unit,
    onImport: () -> Unit,
    onClearSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = colors.isDarkTheme()
    val enterAnim = remember(isSearching, isInFolder) {
        Animatable(if (Motion.currentSpeed == UiAnimationSpeed.OFF) 1f else 0f)
    }
    LaunchedEffect(isSearching, isInFolder) {
        if (Motion.currentSpeed == UiAnimationSpeed.OFF) {
            enterAnim.snapTo(1f)
        } else {
            enterAnim.animateTo(1f, Motion.enterSpring())
        }
    }

    val iconRes = when {
        isSearching -> R.drawable.ic_search
        isInFolder -> R.drawable.ic_folders
        else -> R.drawable.ic_canvas_tab
    }
    val titleText = when {
        isSearching -> stringResource(R.string.gallery_empty_search_title)
        isInFolder -> stringResource(R.string.gallery_empty_stack_title)
        else -> stringResource(R.string.gallery_empty_main_title)
    }
    val descText = when {
        isSearching -> stringResource(R.string.gallery_empty_search_desc)
        isInFolder -> stringResource(R.string.gallery_empty_stack_desc)
        else -> stringResource(R.string.gallery_empty_main_desc)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = enterAnim.value
                translationY = (1f - enterAnim.value) * 18.dp.toPx()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            // Layered studio artboard emblem
            Box(
                modifier = Modifier.size(116.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    colors.accent.copy(alpha = if (isDark) 0.16f else 0.12f),
                                    Color.Transparent,
                                ),
                            ),
                            shape = CircleShape,
                        ),
                )

                val backCardColor = if (isDark) blendColors(colors.panelHi, colors.bg, 0.35f) else blendColors(colors.panel, Color.Black, 0.04f)
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .graphicsLayer {
                            rotationZ = -11f
                            translationX = (-14).dp.toPx()
                            translationY = 4.dp.toPx()
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .background(backCardColor)
                        .border(0.75.dp, artboardHairlineBrush(colors), RoundedCornerShape(14.dp)),
                )

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .graphicsLayer {
                            rotationZ = 9f
                            translationX = 14.dp.toPx()
                            translationY = (-3).dp.toPx()
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.panelHi)
                        .border(0.75.dp, artboardHairlineBrush(colors), RoundedCornerShape(14.dp)),
                )

                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .shadow(
                            elevation = if (isDark) 14.dp else 10.dp,
                            shape = RoundedCornerShape(18.dp),
                            spotColor = Color.Black.copy(alpha = if (isDark) 0.45f else 0.14f),
                            clip = false,
                        )
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.panel)
                        .border(0.75.dp, studioHairlineBrush(colors), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = titleText,
                color = colors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = descText,
                color = colors.subText,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 280.dp),
            )

            if (!isInFolder) {
                Spacer(Modifier.height(22.dp))
                if (isSearching) {
                    EmptyStatePillButton(
                        text = stringResource(R.string.gallery_empty_clear_search),
                        iconRes = R.drawable.ic_x,
                        primary = false,
                        colors = colors,
                        onClick = onClearSearch,
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EmptyStatePillButton(
                            text = stringResource(R.string.home_new_canvas),
                            iconRes = R.drawable.ic_plus,
                            primary = true,
                            colors = colors,
                            onClick = onCreateNew,
                        )
                        EmptyStatePillButton(
                            text = stringResource(R.string.common_import),
                            iconRes = R.drawable.ic_import,
                            primary = false,
                            colors = colors,
                            onClick = onImport,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStatePillButton(
    text: String,
    @DrawableRes iconRes: Int,
    primary: Boolean,
    colors: AppColors,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val bgColor = if (primary) colors.accent else colors.panelHi
    val fgColor = if (primary) colors.onAccent else colors.text
    val highlight = if (primary) Color.White else colors.accent

    Row(
        modifier = Modifier
            .pressScale(interaction, pressedScale = 0.94f)
            .then(
                if (primary) {
                    Modifier.shadow(
                        elevation = 10.dp,
                        shape = CircleShape,
                        spotColor = colors.accent.copy(alpha = 0.35f),
                        clip = false,
                    )
                } else {
                    Modifier
                },
            )
            .clip(CircleShape)
            .background(bgColor)
            .then(
                if (!primary) {
                    Modifier.border(0.75.dp, studioHairlineBrush(colors), CircleShape)
                } else {
                    Modifier
                },
            )
            .liquidHighlight(interaction, highlight, radius = 36.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = fgColor,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = text,
            color = fgColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
