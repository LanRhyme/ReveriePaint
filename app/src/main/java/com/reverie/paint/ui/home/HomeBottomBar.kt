package com.reverie.paint.ui.home

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.Project
import com.reverie.paint.ui.theme.AppColors
import com.reverie.paint.ui.theme.Theme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
@Composable
internal fun HomeBottomBar(
    colors: AppColors,
    vm: PaintViewModel,
    selectedTab: Int,
) {
    // Floating Morandi Bottom Navigation Bar with Spring Animations
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        val isGallery = selectedTab == 0
        val isSettings = selectedTab == 1

        val createSource = remember { MutableInteractionSource() }
        val isCreatePressed by createSource.collectIsPressedAsState()
        val createScale by animateFloatAsState(
            targetValue = if (isCreatePressed) 0.88f else 1.0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "CreateBtnScale"
        )

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(32.dp))
                .background(colors.panel.copy(alpha = 0.95f))
                .border(1.dp, colors.border, RoundedCornerShape(32.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Gallery Tab Button with Animated Pill Container
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (isGallery) colors.panelHi else Color.Transparent)
                    .clickable { vm.homeSelectedTab = 0 }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_brush),
                    contentDescription = "画廊",
                    tint = if (isGallery) colors.accent else colors.subText,
                    modifier = Modifier.size(20.dp)
                )
                AnimatedVisibility(
                    visible = isGallery,
                    enter = fadeIn(tween(200)) + expandHorizontally(expandFrom = Alignment.Start),
                    exit = fadeOut(tween(150)) + shrinkHorizontally(shrinkTowards = Alignment.Start)
                ) {
                    Row {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "画廊",
                            color = colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Create Action Button (Pulsing / Press-responsive Accent Circle)
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .scale(createScale)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .clickable(interactionSource = createSource, indication = null) { vm.goCreate() },
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(R.drawable.ic_plus), contentDescription = "新建", tint = colors.onAccent, modifier = Modifier.size(28.dp))
            }

            // Settings Tab Button with Animated Pill Container
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (isSettings) colors.panelHi else Color.Transparent)
                    .clickable { vm.homeSelectedTab = 1 }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = "设置",
                    tint = if (isSettings) colors.accent else colors.subText,
                    modifier = Modifier.size(20.dp)
                )
                AnimatedVisibility(
                    visible = isSettings,
                    enter = fadeIn(tween(200)) + expandHorizontally(expandFrom = Alignment.Start),
                    exit = fadeOut(tween(150)) + shrinkHorizontally(shrinkTowards = Alignment.Start)
                ) {
                    Row {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "设置",
                            color = colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
