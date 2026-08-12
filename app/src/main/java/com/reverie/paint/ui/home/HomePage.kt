package com.reverie.paint.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Theme

@Composable
fun HomePage(vm: PaintViewModel) {
    val colors = Theme.current
    var selectedTab by remember { androidx.compose.runtime.mutableStateOf(0) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.bg)
    ) {
        if (selectedTab == 0) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Person, contentDescription = "Profile", tint = colors.text, modifier = Modifier.size(24.dp))
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.Search, contentDescription = "Search", tint = colors.icon, modifier = Modifier.padding(horizontal = 8.dp).size(24.dp))
                Icon(Icons.Default.Cloud, contentDescription = "Cloud", tint = colors.icon, modifier = Modifier.padding(horizontal = 8.dp).size(24.dp))
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = colors.icon, modifier = Modifier.padding(start = 8.dp).size(24.dp))
            }

            // Project Grid
            if (vm.projects.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无画布", color = colors.subText, fontSize = 16.sp)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(vm.projects) { p ->
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable { vm.loadProject(p.name) }
                        ) {
                            val thumb = remember(p.name) {
                                val file = java.io.File(vm.projectDir(), "${p.name}.png")
                                if (file.exists()) android.graphics.BitmapFactory.decodeFile(file.absolutePath) else null
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.canvasBg)
                            ) {
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    // Video icon placeholder
                                    Box(modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha=0.4f)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(p.name, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("2026-08-12", color = colors.subText, fontSize = 12.sp)
                        }
                    }
                }
            }
        } else {
            // Settings Tab
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SettingsPageContent(vm)
            }
        }

        // Bottom Nav Bar
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).background(colors.panel),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val isGallery = selectedTab == 0
            val isSettings = selectedTab == 1
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { selectedTab = 0 }
            ) {
                Icon(Icons.Rounded.Brush, contentDescription = "创作", tint = if (isGallery) colors.accent else colors.subText, modifier = Modifier.size(24.dp))
                Text("创作", color = if (isGallery) colors.accent else colors.subText, fontSize = 10.sp)
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .clickable { vm.goCreate() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建", tint = colors.onAccent, modifier = Modifier.size(28.dp))
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { selectedTab = 1 }
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = "设置", tint = if (isSettings) colors.accent else colors.subText, modifier = Modifier.size(24.dp))
                Text("设置", color = if (isSettings) colors.accent else colors.subText, fontSize = 10.sp)
            }
        }
    }
}
