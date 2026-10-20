package com.admrichest.wallflow.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.admrichest.wallflow.data.LibraryRepository
import com.admrichest.wallflow.data.Quality
import com.admrichest.wallflow.data.RotationMode
import com.admrichest.wallflow.data.Settings
import com.admrichest.wallflow.wallpaper.PlaybackController
import com.admrichest.wallflow.wallpaper.RotationScheduler
import com.admrichest.wallflow.wallpaper.WallpaperApplier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    repo: LibraryRepository,
    onEnableLive: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { Settings.get(context) }
    val allItems by repo.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var synced by remember { mutableStateOf(settings.syncedScreens) }
    var mode by remember { mutableStateOf(settings.rotationMode) }
    var interval by remember { mutableIntStateOf(settings.intervalMinutes) }
    var shuffle by remember { mutableStateOf(settings.shuffle) }
    var mirrorToLock by remember { mutableStateOf(settings.mirrorToLock) }
    var adaptiveColors by remember { mutableStateOf(settings.adaptiveClockColors) }
    var kenBurns by remember { mutableStateOf(settings.kenBurns) }
    var parallax by remember { mutableStateOf(settings.parallax) }
    var batteryGuard by remember { mutableIntStateOf(settings.pauseVideoBelowBattery) }
    var quality by remember { mutableStateOf(settings.quality) }

    var appBytes by remember { mutableLongStateOf(0L) }
    var sourceBytes by remember { mutableLongStateOf(0L) }
    var storageTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(storageTick) {
        val app = withContext(Dispatchers.IO) { repo.appStorageBytes() }
        val source = withContext(Dispatchers.IO) { repo.referencedBytes() }
        appBytes = app
        sourceBytes = source
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SettingsCard("จอที่ควบคุม") {
                SwitchRow(
                    title = "ตั้งหน้าหลักและจอล็อกพร้อมกัน",
                    subtitle = "รูปเดียวกันทั้งสองจอ ตั้งในครั้งเดียว ปิดไว้ถ้าอยากใช้คนละคิว",
                    checked = synced,
                ) {
                    synced = it
                    settings.syncedScreens = it
                    PlaybackController.reapply(context)
                }
                if (!synced) {
                    val homeOnly = allItems.count { it.onHome && !it.onLock }
                    val lockOnly = allItems.count { it.onLock && !it.onHome }
                    val both = allItems.count { it.onHome && it.onLock }
                    Text(
                        "ตอนนี้แท็กไว้: หน้าหลักอย่างเดียว $homeOnly · จอล็อกอย่างเดียว $lockOnly · ทั้งสองจอ $both",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (lockOnly == 0) {
                        Text(
                            "ยังไม่มีรูปที่แท็กให้จอล็อกโดยเฉพาะ รูปที่เพิ่มเข้ามาจะถูกแท็ก " +
                                "ทั้งสองจอ เป็นค่าเริ่มต้น ให้ไปที่แท็บคลังภาพ กดค้างที่รูป " +
                                "แล้วกดปุ่ม ล็อก เพื่อแยกคิวให้ชัดเจน",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                SwitchRow(
                    title = "มิเรอร์ไปจอล็อกเมื่อใช้ Live Wallpaper",
                    subtitle = "One UI มักให้ Live Wallpaper ลงได้แค่หน้าหลัก " +
                        "เปิดไว้เพื่อให้จอล็อกเปลี่ยนตามคิวเดียวกัน (ใช้ภาพนิ่งจากเฟรมแรกของวิดีโอ)",
                    checked = mirrorToLock,
                ) {
                    mirrorToLock = it
                    settings.mirrorToLock = it
                }
            }
        }

        item {
            SettingsCard("เปลี่ยนรูปเมื่อไหร่") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RotationChip("ปิด", mode == RotationMode.OFF) {
                        mode = RotationMode.OFF
                        settings.rotationMode = mode
                        RotationScheduler.cancel(context)
                    }
                    RotationChip("ปลดล็อก", mode == RotationMode.UNLOCK) {
                        mode = RotationMode.UNLOCK
                        settings.rotationMode = mode
                        RotationScheduler.cancel(context)
                    }
                    RotationChip("ตามเวลา", mode == RotationMode.INTERVAL) {
                        mode = RotationMode.INTERVAL
                        settings.rotationMode = mode
                        RotationScheduler.reschedule(context)
                    }
                    RotationChip("แตะสองครั้ง", mode == RotationMode.TAP) {
                        mode = RotationMode.TAP
                        settings.rotationMode = mode
                        RotationScheduler.cancel(context)
                    }
                }

                if (mode == RotationMode.INTERVAL) {
                    Text(
                        "ทุก ${formatInterval(interval)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = interval.toFloat(),
                        onValueChange = { interval = it.toInt().coerceAtLeast(1) },
                        onValueChangeFinished = {
                            settings.intervalMinutes = interval
                            RotationScheduler.reschedule(context)
                        },
                        valueRange = 1f..720f,
                    )
                    Text(
                        "ระบบอาจเลื่อนเวลาเล็กน้อยตอนเครื่องหลับเพื่อประหยัดแบต",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                if (mode == RotationMode.UNLOCK || mode == RotationMode.TAP) {
                    Text(
                        "โหมดนี้ทำงานผ่าน Live Wallpaper จึงไม่กินแบตเพิ่มจากการรันเบื้องหลัง",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                SwitchRow(title = "สุ่มลำดับ", subtitle = null, checked = shuffle) {
                    shuffle = it
                    settings.shuffle = it
                }
            }
        }

        item {
            SettingsCard("ภาพและนาฬิกา") {
                SwitchRow(
                    title = "นาฬิกาปรับสีตามรูป",
                    subtitle = "ส่งค่าสีเด่นของรูปให้ระบบ (WallpaperColors) จอล็อกจึงเปลี่ยนสีนาฬิกาตามภาพ",
                    checked = adaptiveColors,
                ) {
                    adaptiveColors = it
                    settings.adaptiveClockColors = it
                    PlaybackController.reapply(context)
                }
                SwitchRow(
                    title = "เลื่อนภาพตามหน้าจอหลัก",
                    subtitle = "ภาพขยับเล็กน้อยเวลาปัดหน้าจอ",
                    checked = parallax,
                ) {
                    parallax = it
                    settings.parallax = it
                }
                SwitchRow(
                    title = "ซูมช้าแบบ Ken Burns",
                    subtitle = "สวยขึ้นกับภาพนิ่ง แต่กินแบตเพิ่ม",
                    checked = kenBurns,
                ) {
                    kenBurns = it
                    settings.kenBurns = it
                }

                Text("คุณภาพภาพนิ่งใน Live Wallpaper", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RotationChip("สมดุล (แนะนำ)", quality == Quality.BALANCED) {
                        quality = Quality.BALANCED
                        settings.quality = quality
                        PlaybackController.reapply(context)
                    }
                    RotationChip("เต็มความละเอียด", quality == Quality.FULL) {
                        quality = Quality.FULL
                        settings.quality = quality
                        PlaybackController.reapply(context)
                    }
                }
                Text(
                    "สมดุล = ถอดรหัสเท่าขนาดจอ ซึ่งคมเท่ากันบนจอ S25+ แต่ใช้แรมน้อยกว่า " +
                        "เต็มความละเอียด = เก็บรายละเอียด 4K ไว้ เหมาะเมื่อเปิด Ken Burns",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        item {
            SettingsCard("แบตเตอรี่") {
                Text(
                    if (batteryGuard == 0) "เล่นวิดีโอตลอด"
                    else "หยุดวิดีโอเมื่อแบตต่ำกว่า $batteryGuard%",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = batteryGuard.toFloat(),
                    onValueChange = { batteryGuard = it.toInt() },
                    onValueChangeFinished = { settings.pauseVideoBelowBattery = batteryGuard },
                    valueRange = 0f..50f,
                )
                Text(
                    "วิดีโอจะหยุดเองทุกครั้งที่ปิดหน้าจอหรือเปิดแอพอื่นเต็มจอ",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        item {
            SettingsCard("พื้นที่จัดเก็บ") {
                Text("แอพนี้ใช้: ${formatBytes(appBytes)}", fontWeight = FontWeight.SemiBold)
                Text(
                    "ไฟล์ต้นฉบับที่อ้างอิง: ${formatBytes(sourceBytes)} (ไม่ได้คัดลอก นับให้ดูเฉยๆ)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "แอพเก็บแค่รายการ URI กับรูปย่อ ไฟล์ 4K ยังอยู่ที่เดิมของคุณ " +
                        "จึงไม่มีสำเนาซ้ำให้เปลืองเครื่อง",
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    context.cacheDir.deleteRecursively()
                                }
                                storageTick++
                                onMessage("ล้างรูปย่อแล้ว")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("ล้างรูปย่อ") }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val removed = withContext(Dispatchers.IO) { repo.pruneUnreadable() }
                                storageTick++
                                onMessage(
                                    if (removed > 0) "ลบรายการที่เปิดไม่ได้ $removed รายการ"
                                    else "ทุกไฟล์ยังเปิดได้ปกติ"
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("ตรวจไฟล์เสีย") }
                }
            }
        }

        item {
            SettingsCard("บันทึกการทำงานของ Live Wallpaper") {
                var log by remember { mutableStateOf(settings.engineLog) }
                val videoCount = allItems.count { it.isVideo }
                val currentHome = PlaybackController.currentHome(context)

                Text(
                    "วิดีโอในคลัง $videoCount ไฟล์ · ตอนนี้หน้าจอหลักควรแสดง " +
                        (currentHome?.let { "${it.displayName} (${if (it.isVideo) "วิดีโอ" else "รูป"})" }
                            ?: "ยังไม่มี"),
                    style = MaterialTheme.typography.bodySmall,
                )

                Text(
                    if (log.isBlank()) {
                        "ยังไม่มีบันทึก แปลว่า engine ยังไม่ได้ทำงานเลย " +
                            "ให้กลับไปหน้าจอหลัก ปิดจอแล้วเปิดใหม่ จากนั้นกลับมาดูหน้านี้"
                    } else {
                        log
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { log = settings.engineLog },
                        modifier = Modifier.weight(1f),
                    ) { Text("รีเฟรช") }
                    OutlinedButton(
                        onClick = {
                            val clip = context.getSystemService(ClipboardManager::class.java)
                            clip?.setPrimaryClip(
                                ClipData.newPlainText("WallFlow engine log", settings.engineLog)
                            )
                            onMessage("คัดลอกบันทึกแล้ว")
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("คัดลอก") }
                    OutlinedButton(
                        onClick = {
                            settings.clearEngineLog()
                            log = ""
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("ล้าง") }
                }

                val firstVideo = allItems.firstOrNull { it.isVideo }
                if (firstVideo != null) {
                    Button(
                        onClick = {
                            settings.clearEngineLog()
                            PlaybackController.jumpTo(context, firstVideo)
                            onMessage("สั่งเล่น ${firstVideo.displayName} แล้ว กลับไปดูหน้าจอหลัก")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("ทดสอบเล่นวิดีโอแรกในคลัง") }
                    Text(
                        "กดปุ่มนี้ กลับไปหน้าจอหลักสักครู่ แล้วกลับมากด รีเฟรช เพื่อดูว่าเกิดอะไรขึ้น",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        item {
            SettingsCard("สถานะระบบ") {
                val liveActive = WallpaperApplier.isLiveEngineActive(context)
                val lockSet = WallpaperApplier.hasDedicatedLockWallpaper(context)
                Text(
                    if (liveActive) "Live Wallpaper: กำลังใช้งาน" else "Live Wallpaper: ยังไม่ได้ตั้ง",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (lockSet) "จอล็อก: ใช้ภาพแยกจากหน้าหลัก" else "จอล็อก: ใช้ภาพเดียวกับหน้าหลัก",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!liveActive) {
                    Button(onClick = onEnableLive, modifier = Modifier.fillMaxWidth()) {
                        Text("เปิดใช้ Live Wallpaper")
                    }
                }
                OutlinedButton(
                    onClick = {
                        WallpaperApplier.clearLock(context)
                        onMessage("คืนจอล็อกให้ตามหน้าหลักแล้ว")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("คืนค่าจอล็อกเป็นค่าเริ่มต้น") }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RotationChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

private fun formatInterval(minutes: Int): String = when {
    minutes < 60 -> "$minutes นาที"
    minutes % 60 == 0 -> "${minutes / 60} ชั่วโมง"
    else -> "${minutes / 60} ชั่วโมง ${minutes % 60} นาที"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
