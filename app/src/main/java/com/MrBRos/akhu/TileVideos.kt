package com.MrBRos.akhu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The video a grid tile loops (its first [LOOP_MS]), as a path [CropVideoView] can play. A posted
 * video is downloaded once to the cache, a few at a time, so scrolling back doesn't fetch it again.
 */
object TileVideos {

    /** How much of each video a tile plays before starting over. */
    const val LOOP_MS = 3000

    /** Videos kept in the cache; the oldest go first past this. */
    private const val KEEP = 60

    private val downloads = Semaphore(2)

    /** What [card] loops in the grid, or null if it isn't a video (or can't be fetched). */
    suspend fun source(context: Context, card: WallpaperCard): String? {
        val post = card.post
        return when {
            post != null -> if (post.isVideo) cached(context.applicationContext, post) else null
            card.isVideo -> card.assetPath
            card.localPath?.endsWith(".mp4") == true -> card.localPath
            else -> null
        }
    }

    private suspend fun cached(app: Context, post: CloudWallpaper): String? = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "tile_videos")
        val file = File(dir, post.id + ".mp4")
        if (file.exists()) return@withContext file.absolutePath
        downloads.withPermit {
            try {
                if (!file.exists()) {
                    MotionWallpapers.download(post.downloadUrl, file)
                    trim(dir)
                }
                file.absolutePath
            } catch (e: IOException) {
                null
            }
        }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".mp4") } ?: return
        if (files.size <= KEEP) return
        files.sortedBy { it.lastModified() }.take(files.size - KEEP).forEach { it.delete() }
    }
}
