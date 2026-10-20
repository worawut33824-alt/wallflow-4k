package com.admrichest.wallflow.data

import android.net.Uri

enum class MediaType { IMAGE, VIDEO }

/**
 * One entry in the library.
 *
 * Only the URI is stored. The file itself stays where the user put it, so a 4K
 * library of any size costs a few hundred bytes of app storage per item.
 */
data class MediaItem(
    val id: String,
    val uri: String,
    val type: MediaType,
    val displayName: String,
    val addedAt: Long,
    /** Include this item in the home-screen rotation. */
    val onHome: Boolean = true,
    /** Include this item in the lock-screen rotation. */
    val onLock: Boolean = true,
    /** Set when the item came from a linked folder, so a re-scan can refresh it. */
    val folderKey: String? = null,
) {
    val parsedUri: Uri get() = Uri.parse(uri)
    val isVideo: Boolean get() = type == MediaType.VIDEO
}
