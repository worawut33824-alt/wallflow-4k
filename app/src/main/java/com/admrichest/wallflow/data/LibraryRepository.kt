package com.admrichest.wallflow.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "WallFlowLibrary"

/**
 * The library, persisted as a small JSON file in private storage.
 *
 * Media is referenced, never copied. Every URI that enters the library gets a
 * persistable read grant so it survives reboots.
 */
class LibraryRepository private constructor(private val appContext: Context) {

    data class LinkedFolder(val treeUri: String, val name: String)

    /**
     * Why an import added fewer items than the user picked.
     *
     * Worth reporting in detail: "nothing was added" has several very
     * different causes and guessing between them wastes the user's time.
     */
    data class AddResult(
        val added: Int = 0,
        val duplicates: Int = 0,
        val unsupported: Int = 0,
        val unreadable: Int = 0,
    ) {
        val picked: Int get() = added + duplicates + unsupported + unreadable
    }

    private val file = File(appContext.filesDir, "library.json")

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    private val _folders = MutableStateFlow<List<LinkedFolder>>(emptyList())
    val folders: StateFlow<List<LinkedFolder>> = _folders.asStateFlow()

    init {
        load()
    }

    // ---- persistence -------------------------------------------------------

    @Synchronized
    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())

            val itemArray = root.optJSONArray("items") ?: JSONArray()
            val loaded = ArrayList<MediaItem>(itemArray.length())
            for (i in 0 until itemArray.length()) {
                val o = itemArray.getJSONObject(i)
                loaded += MediaItem(
                    id = o.getString("id"),
                    uri = o.getString("uri"),
                    type = runCatching { MediaType.valueOf(o.getString("type")) }
                        .getOrDefault(MediaType.IMAGE),
                    displayName = o.optString("name", "?"),
                    addedAt = o.optLong("addedAt", 0L),
                    onHome = o.optBoolean("home", true),
                    onLock = o.optBoolean("lock", true),
                    folderKey = o.optString("folder").takeIf { it.isNotEmpty() },
                )
            }
            _items.value = loaded

            val folderArray = root.optJSONArray("folders") ?: JSONArray()
            val folderList = ArrayList<LinkedFolder>(folderArray.length())
            for (i in 0 until folderArray.length()) {
                val o = folderArray.getJSONObject(i)
                folderList += LinkedFolder(o.getString("uri"), o.optString("name", "?"))
            }
            _folders.value = folderList
        }.onFailure { Log.w(TAG, "could not read library.json", it) }
    }

    @Synchronized
    private fun save() {
        runCatching {
            val itemArray = JSONArray()
            _items.value.forEach { item ->
                itemArray.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("uri", item.uri)
                        .put("type", item.type.name)
                        .put("name", item.displayName)
                        .put("addedAt", item.addedAt)
                        .put("home", item.onHome)
                        .put("lock", item.onLock)
                        .put("folder", item.folderKey ?: "")
                )
            }
            val folderArray = JSONArray()
            _folders.value.forEach { f ->
                folderArray.put(JSONObject().put("uri", f.treeUri).put("name", f.name))
            }
            file.writeText(
                JSONObject().put("items", itemArray).put("folders", folderArray).toString()
            )
        }.onFailure { Log.w(TAG, "could not write library.json", it) }
    }

    // ---- reading -----------------------------------------------------------

    fun homeQueue(): List<MediaItem> = _items.value.filter { it.onHome }

    fun lockQueue(): List<MediaItem> = _items.value.filter { it.onLock }

    fun byId(id: String?): MediaItem? =
        id?.let { wanted -> _items.value.firstOrNull { it.id == wanted } }

    /** Bytes the app itself occupies: the JSON index plus the thumbnail cache. */
    fun appStorageBytes(): Long {
        val index = if (file.exists()) file.length() else 0L
        val cache = appContext.cacheDir.walkBottomUp()
            .filter { it.isFile }
            .sumOf { it.length() }
        return index + cache
    }

    /** Bytes of source media referenced, for display only. Nothing is duplicated. */
    fun referencedBytes(): Long = _items.value.sumOf { item ->
        runCatching { sizeOf(appContext.contentResolver, item.parsedUri) }.getOrDefault(0L)
    }

    // ---- writing -----------------------------------------------------------

    /** Adds individually picked documents, taking a persistable grant on each. */
    fun addDocuments(uris: List<Uri>): AddResult {
        val resolver = appContext.contentResolver
        val existing = _items.value.mapTo(HashSet()) { it.uri }
        val additions = ArrayList<MediaItem>()
        var duplicates = 0
        var unsupported = 0
        var unreadable = 0

        uris.forEach { uri ->
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onFailure { Log.w(TAG, "no persistable grant for $uri", it) }

            if (uri.toString() in existing) {
                duplicates++
                return@forEach
            }

            val mime = runCatching { resolver.getType(uri) }.getOrNull()
            if (mime == null) {
                Log.w(TAG, "no MIME type for $uri")
                unreadable++
                return@forEach
            }

            val type = when {
                mime.startsWith("image/") -> MediaType.IMAGE
                mime.startsWith("video/") -> MediaType.VIDEO
                else -> {
                    Log.w(TAG, "unsupported MIME $mime for $uri")
                    unsupported++
                    return@forEach
                }
            }

            additions += MediaItem(
                id = uri.toString().hashCode().toString(),
                uri = uri.toString(),
                type = type,
                displayName = displayNameOf(resolver, uri),
                addedAt = System.currentTimeMillis(),
            )
        }

        if (additions.isNotEmpty()) {
            _items.value = _items.value + additions
            save()
        }
        return AddResult(additions.size, duplicates, unsupported, unreadable)
    }

    /**
     * Links a whole folder. The tree grant covers every child, so files dropped
     * into that folder later show up after a re-scan with no re-picking.
     */
    fun linkFolder(treeUri: Uri): Int {
        val resolver = appContext.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Log.w(TAG, "no persistable tree grant", it) }

        val doc = DocumentFile.fromTreeUri(appContext, treeUri) ?: return 0
        val key = treeUri.toString()
        if (_folders.value.none { it.treeUri == key }) {
            _folders.value = _folders.value + LinkedFolder(key, doc.name ?: "folder")
            save()
        }
        return scanFolder(treeUri)
    }

    /** Re-reads a linked folder and adds anything new. */
    fun scanFolder(treeUri: Uri): Int {
        val doc = DocumentFile.fromTreeUri(appContext, treeUri) ?: return 0
        val key = treeUri.toString()
        val existing = _items.value.mapTo(HashSet()) { it.uri }
        val additions = ArrayList<MediaItem>()

        doc.listFiles().forEach { child ->
            if (!child.isFile) return@forEach
            val mime = child.type ?: return@forEach
            val type = when {
                mime.startsWith("image/") -> MediaType.IMAGE
                mime.startsWith("video/") -> MediaType.VIDEO
                else -> return@forEach
            }
            val uriString = child.uri.toString()
            if (uriString in existing) return@forEach
            additions += MediaItem(
                id = uriString.hashCode().toString(),
                uri = uriString,
                type = type,
                displayName = child.name ?: "?",
                addedAt = System.currentTimeMillis(),
                folderKey = key,
            )
        }

        if (additions.isNotEmpty()) {
            _items.value = _items.value + additions
            save()
        }
        return additions.size
    }

    fun rescanAllFolders(): Int = _folders.value.sumOf { scanFolder(Uri.parse(it.treeUri)) }

    fun unlinkFolder(treeUri: String) {
        _folders.value = _folders.value.filterNot { it.treeUri == treeUri }
        _items.value = _items.value.filterNot { it.folderKey == treeUri }
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        save()
    }

    fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        _items.value = _items.value.filterNot { it.id in ids }
        save()
    }

    fun setTargets(ids: Set<String>, onHome: Boolean?, onLock: Boolean?) {
        if (ids.isEmpty()) return
        _items.value = _items.value.map {
            if (it.id in ids) it.copy(onHome = onHome ?: it.onHome, onLock = onLock ?: it.onLock)
            else it
        }
        save()
    }

    fun move(id: String, delta: Int) {
        val list = _items.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.size - 1)
        if (to == from) return
        list.add(to, list.removeAt(from))
        _items.value = list
        save()
    }

    /** Drops entries whose URI no longer resolves, e.g. the file was deleted. */
    fun pruneUnreadable(): Int {
        val resolver = appContext.contentResolver
        val before = _items.value
        val alive = before.filter { item ->
            runCatching {
                resolver.openAssetFileDescriptor(item.parsedUri, "r")?.use { true } ?: false
            }.getOrDefault(false)
        }
        if (alive.size != before.size) {
            _items.value = alive
            save()
        }
        return before.size - alive.size
    }

    // ---- helpers -----------------------------------------------------------

    private fun displayNameOf(resolver: ContentResolver, uri: Uri): String {
        val cursor: Cursor? = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        }.getOrNull()
        cursor?.use {
            val col = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && it.moveToFirst()) return it.getString(col) ?: "?"
        }
        return uri.lastPathSegment ?: "?"
    }

    private fun sizeOf(resolver: ContentResolver, uri: Uri): Long {
        val cursor = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        }.getOrNull()
        cursor?.use {
            val col = it.getColumnIndex(OpenableColumns.SIZE)
            if (col >= 0 && it.moveToFirst()) return it.getLong(col)
        }
        return 0L
    }

    companion object {
        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository =
            instance ?: synchronized(this) {
                instance ?: LibraryRepository(context.applicationContext).also { instance = it }
            }
    }
}
