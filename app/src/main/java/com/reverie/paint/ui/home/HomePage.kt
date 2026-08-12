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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.CanvasPresets
import com.reverie.paint.ui.theme.Morandi

@Composable
fun HomePage(vm: PaintViewModel) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Morandi.bg)
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))

        // Logo tile
        Box(
            modifier =
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Morandi.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("R", color = Morandi.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(16.dp))
        Text("ReveriePaint", color = Morandi.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Creative drawing", color = Morandi.subText, fontSize = 14.sp)

        Spacer(Modifier.height(36.dp))

        // Primary action
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.accent)
                    .clickable { vm.goCreate() },
            contentAlignment = Alignment.Center,
        ) {
            Text("新建画布", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(12.dp))

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.panel)
                    .clickable {
                        // Open the most recent project, or go create a new one
                        vm.projects.firstOrNull()?.let { vm.loadProject(it.name) } ?: vm.goCreate()
                    },
            contentAlignment = Alignment.Center,
        ) {
            Text("打开项目", color = Morandi.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(32.dp))

        // Recent projects
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("最近项目", color = Morandi.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (vm.projects.isEmpty()) "暂无" else "${vm.projects.size} 个",
                color = Morandi.subText,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        if (vm.projects.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Morandi.panel),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无项目", color = Morandi.subText, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("新建一个画布开始创作", color = Morandi.subText, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.projects) { p ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Morandi.panel)
                                .clickable { vm.loadProject(p.name) }
                                .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val thumb = remember(p.name) {
                            android.graphics.BitmapFactory.decodeFile(
                                java.io.File(vm.projectDir(), "${p.name}.png").absolutePath,
                            )
                        }
                        if (thumb != null) {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = null,
                                modifier =
                                    Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Morandi.canvasBg),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(p.name, color = Morandi.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (thumb != null) "${thumb.getWidth()}×${thumb.getHeight()}" else "${p.w}×${p.h}",
                            color = Morandi.subText,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
