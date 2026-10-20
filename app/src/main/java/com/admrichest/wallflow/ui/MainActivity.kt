package com.admrichest.wallflow.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.admrichest.wallflow.data.LibraryRepository
import com.admrichest.wallflow.ui.theme.WallFlowTheme
import com.admrichest.wallflow.wallpaper.WallFlowWallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WallFlowTheme {
                WallFlowRoot()
            }
        }
    }
}

/**
 * ACTION_OPEN_DOCUMENT returns grants we can hold onto, unlike the photo
 * picker, so the library keeps working after a reboot without copying files.
 */
private class OpenPersistableDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        // The parent contract always sets */* and relies on EXTRA_MIME_TYPES.
        // One UI's document picker filters poorly that way and tends to list
        // only the first type, which is why videos went missing. A single
        // concrete type is unambiguous for every picker.
        if (input.size == 1) intent.type = input[0]
        return intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
    }
}

private val IMAGE_TYPES = arrayOf("image/*")
private val VIDEO_TYPES = arrayOf("video/*")

@Composable
private fun WallFlowRoot() {
    val context = LocalContext.current
    val repo = remember { LibraryRepository.get(context) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }

    fun toast(message: String, long: Boolean = false) = Toast.makeText(
        context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
    ).show()

    fun report(result: LibraryRepository.AddResult) {
        val parts = buildList {
            if (result.added > 0) add("เพิ่ม ${result.added} ไฟล์")
            if (result.duplicates > 0) add("ข้าม ${result.duplicates} ไฟล์ที่มีอยู่แล้ว")
            if (result.unsupported > 0) add("ข้าม ${result.unsupported} ไฟล์ชนิดที่ไม่รองรับ")
            if (result.unreadable > 0) add("ข้าม ${result.unreadable} ไฟล์ที่อ่านไม่ได้")
        }
        toast(
            if (parts.isEmpty()) "ไม่ได้เลือกไฟล์ใด" else parts.joinToString(" · "),
            long = result.added == 0,
        )
    }

    val pickImages = rememberLauncherForActivityResult(OpenPersistableDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch { report(withContext(Dispatchers.IO) { repo.addDocuments(uris) }) }
    }

    val pickVideos = rememberLauncherForActivityResult(OpenPersistableDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch { report(withContext(Dispatchers.IO) { repo.addDocuments(uris) }) }
    }

    // The overlay degrades section by section, so these are asked for only
    // when the user turns the matching switch on.
    val askOverlayPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted.filterValues { it }.keys.size
        toast(
            if (ok > 0) "อนุญาตแล้ว $ok รายการ นาฬิกาจะอัปเดตในนาทีถัดไป"
            else "ยังไม่ได้รับสิทธิ์ ส่วนนั้นจะถูกซ่อนไว้"
        )
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) { repo.linkFolder(treeUri) }
            toast(
                if (added > 0) "เชื่อมโฟลเดอร์แล้ว พบสื่อใหม่ $added ไฟล์"
                else "เชื่อมโฟลเดอร์แล้ว แต่ยังไม่พบรูปหรือวิดีโอในโฟลเดอร์นี้",
                long = added == 0,
            )
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                    label = { Text("คลังภาพ") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("ตั้งค่า") },
                )
            }
        }
    ) { inner ->
        when (tab) {
            0 -> LibraryScreen(
                modifier = Modifier.padding(inner),
                repo = repo,
                onAddPhotos = { pickImages.launch(IMAGE_TYPES) },
                onAddVideos = { pickVideos.launch(VIDEO_TYPES) },
                onLinkFolder = { pickFolder.launch(null) },
                onEnableLive = { launchLiveWallpaperPicker(context) },
                onMessage = { toast(it) },
            )

            else -> SettingsScreen(
                modifier = Modifier.padding(inner),
                repo = repo,
                onEnableLive = { launchLiveWallpaperPicker(context) },
                onRequestPermissions = { askOverlayPermissions.launch(it) },
                onMessage = { toast(it) },
            )
        }
    }
}

/**
 * Opens the system preview for our engine. The user confirms once; after that
 * the app swaps content behind it without ever coming back here.
 */
fun launchLiveWallpaperPicker(context: Context) {
    val component = ComponentName(context, WallFlowWallpaperService::class.java)
    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
    val opened = runCatching { context.startActivity(intent); true }.getOrDefault(false)
    if (!opened) {
        // Some skins hide the direct preview; fall back to the picker list.
        runCatching {
            context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }.onFailure {
            Toast.makeText(
                context,
                "เปิดหน้าตั้ง Live Wallpaper ไม่ได้ ลองตั้งจากแอพ Wallpaper ของเครื่องแทน",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
