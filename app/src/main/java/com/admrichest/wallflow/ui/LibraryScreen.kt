package com.admrichest.wallflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.admrichest.wallflow.data.LibraryRepository
import com.admrichest.wallflow.data.MediaItem
import com.admrichest.wallflow.wallpaper.PlaybackController
import com.admrichest.wallflow.wallpaper.WallpaperApplier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    repo: LibraryRepository,
    onAddPhotos: () -> Unit,
    onAddVideos: () -> Unit,
    onLinkFolder: () -> Unit,
    onEnableLive: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by repo.items.collectAsStateWithLifecycle()
    val folders by repo.folders.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(emptySet<String>()) }

    val liveActive = remember(items.size) { WallpaperApplier.isLiveEngineActive(context) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("WallFlow 4K", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${items.size} รายการ · หน้าหลัก ${items.count { it.onHome }} · ล็อก ${items.count { it.onLock }}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        )

        QuickActions(
            liveActive = liveActive,
            hasItems = items.isNotEmpty(),
            onAddPhotos = onAddPhotos,
            onAddVideos = onAddVideos,
            onLinkFolder = onLinkFolder,
            onEnableLive = onEnableLive,
            onApplyNow = {
                PlaybackController.reapply(context)
                onMessage("ตั้งวอลเปเปอร์ปัจจุบันแล้ว")
            },
            onNext = {
                PlaybackController.advance(context, respectDebounce = false)
                onMessage("เปลี่ยนเป็นรูปถัดไป")
            },
        )

        if (folders.isNotEmpty()) {
            LinkedFolders(
                folders = folders,
                onRescan = {
                    scope.launch {
                        val added = withContext(Dispatchers.IO) { repo.rescanAllFolders() }
                        onMessage(if (added > 0) "พบไฟล์ใหม่ $added ไฟล์" else "ไม่มีไฟล์ใหม่")
                    }
                },
                onUnlink = { repo.unlinkFolder(it) },
            )
        }

        if (selected.isNotEmpty()) {
            SelectionBar(
                count = selected.size,
                onHome = {
                    repo.setTargets(selected, onHome = true, onLock = false); selected = emptySet()
                },
                onLock = {
                    repo.setTargets(selected, onHome = false, onLock = true); selected = emptySet()
                },
                onBoth = {
                    repo.setTargets(selected, onHome = true, onLock = true); selected = emptySet()
                },
                onDelete = { repo.remove(selected); selected = emptySet() },
                onClear = { selected = emptySet() },
            )
        }

        if (items.isEmpty()) {
            EmptyState(
                onAddPhotos = onAddPhotos,
                onAddVideos = onAddVideos,
                onLinkFolder = onLinkFolder,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.id }) { item ->
                    MediaTile(
                        item = item,
                        isSelected = item.id in selected,
                        selectionActive = selected.isNotEmpty(),
                        onTap = {
                            if (selected.isNotEmpty()) {
                                selected = if (item.id in selected) selected - item.id
                                else selected + item.id
                            } else {
                                PlaybackController.jumpTo(context, item)
                                onMessage("ตั้ง ${item.displayName} แล้ว")
                            }
                        },
                        onLongPress = { selected = selected + item.id },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActions(
    liveActive: Boolean,
    hasItems: Boolean,
    onAddPhotos: () -> Unit,
    onAddVideos: () -> Unit,
    onLinkFolder: () -> Unit,
    onEnableLive: () -> Unit,
    onApplyNow: () -> Unit,
    onNext: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Photos and videos get their own button on purpose. Asking the
            // system picker for one concrete MIME type is the only way One UI
            // reliably lists videos.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAddPhotos, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.NoteAdd, null, Modifier.size(18.dp))
                    Text("  รูป")
                }
                FilledTonalButton(onClick = onAddVideos, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Movie, null, Modifier.size(18.dp))
                    Text("  วิดีโอ")
                }
                FilledTonalButton(onClick = onLinkFolder, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp))
                    Text("  โฟลเดอร์")
                }
            }
            Text(
                "ถ้าหน้าเลือกไฟล์ยังไม่โชว์วิดีโอ ให้กดเมนู ☰ มุมบนซ้าย แล้วเลือก " +
                    "ที่จัดเก็บภายใน → DCIM แทนการดูจากแท็บ ล่าสุด",
                style = MaterialTheme.typography.labelSmall,
            )

            if (!liveActive) {
                Button(onClick = onEnableLive, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Wallpaper, null, Modifier.size(18.dp))
                    Text("  เปิดใช้ Live Wallpaper (ทำครั้งเดียว)")
                }
                Text(
                    "เปิดครั้งเดียวแล้วเพิ่มรูป/วิดีโอได้ไม่จำกัด โดยไม่ต้องกลับมาตั้งใหม่",
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                Text(
                    "Live Wallpaper กำลังทำงาน · เพิ่มรูปได้เลยโดยไม่ต้องตั้งใหม่",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onApplyNow,
                    enabled = hasItems,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Text("  ตั้งตอนนี้")
                }
                OutlinedButton(
                    onClick = onNext,
                    enabled = hasItems,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.SkipNext, null, Modifier.size(18.dp))
                    Text("  รูปถัดไป")
                }
            }
        }
    }
}

@Composable
private fun LinkedFolders(
    folders: List<LibraryRepository.LinkedFolder>,
    onRescan: () -> Unit,
    onUnlink: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("โฟลเดอร์ที่เชื่อมไว้", style = MaterialTheme.typography.titleSmall)
            folders.forEach { folder ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(folder.name, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onUnlink(folder.treeUri) }) { Text("ยกเลิก") }
                }
            }
            Button(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
                Text("สแกนหาไฟล์ใหม่")
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onHome: () -> Unit,
    onLock: () -> Unit,
    onBoth: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("เลือกไว้ $count รายการ", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = onClear) { Text("ล้าง") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onHome, modifier = Modifier.weight(1f)) {
                    Text("หน้าหลัก")
                }
                FilledTonalButton(onClick = onLock, modifier = Modifier.weight(1f)) {
                    Text("ล็อก")
                }
                FilledTonalButton(onClick = onBoth, modifier = Modifier.weight(1f)) {
                    Text("ทั้งคู่")
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "ลบ", Modifier.size(18.dp))
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    item: MediaItem,
    isSelected: Boolean,
    selectionActive: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(0.62f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
    ) {
        AsyncImage(
            model = item.parsedUri,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        if (item.isVideo) {
            // A bare white icon disappears against a bright thumbnail, which
            // makes it impossible to tell which tile is the video.
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            ) {
                Icon(
                    Icons.Default.Movie,
                    contentDescription = "วิดีโอ",
                    tint = Color.White,
                    modifier = Modifier.padding(3.dp).size(16.dp),
                )
            }
        }

        Row(
            Modifier.align(Alignment.BottomStart).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (item.onHome) TargetBadge("H")
            if (item.onLock) TargetBadge("L")
        }

        if (selectionActive) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.45f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetBadge(label: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun EmptyState(
    onAddPhotos: () -> Unit,
    onAddVideos: () -> Unit,
    onLinkFolder: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text("คลังยังว่างอยู่", style = MaterialTheme.typography.titleMedium)
            Text(
                "แนะนำให้สร้างโฟลเดอร์ เช่น DCIM/Wallpapers แล้วกด เชื่อมโฟลเดอร์ " +
                    "ไฟล์ที่ใส่เพิ่มทีหลังจะเข้าคลังอัตโนมัติ และไม่มีการคัดลอกไฟล์",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onAddPhotos, label = { Text("รูป") })
                AssistChip(onClick = onAddVideos, label = { Text("วิดีโอ") })
                AssistChip(onClick = onLinkFolder, label = { Text("โฟลเดอร์") })
            }
        }
    }
}
