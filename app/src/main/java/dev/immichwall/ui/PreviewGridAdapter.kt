package dev.immichwall.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import dev.immichwall.R
import dev.immichwall.api.AssetDto
import dev.immichwall.api.ImmichApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Preview grid of asset thumbnails. Downloads each asset once via
 * [ImmichApiClient.downloadAssetThumbnail] (grid-sized, tens of KB — never the
 * multi-MB originals) into a temp file under the app cacheDir, then decodes it
 * subsampled. The host fragment calls [clear] on teardown; stale files from a
 * killed session are pruned on construction.
 */
class PreviewGridAdapter(
    private val client: ImmichApiClient,
    private val scope: CoroutineScope,
    cacheDir: File,
) : RecyclerView.Adapter<PreviewGridAdapter.PhotoViewHolder>() {

    private val tempDir = File(cacheDir, "preview-thumbs").apply { mkdirs() }
    private val assets = mutableListOf<AssetDto>()
    private val bitmapCache = LruCache<String, Bitmap>(24)

    /**
     * Asset ids with a load in flight. Rebinds can request the same asset again
     * mid-download, and two writers on one tmp path corrupt the file — so dedupe
     * per asset. [scope] is main-thread (lifecycleScope), so add/remove need no
     * extra locking.
     */
    private val inFlight = mutableSetOf<String>()

    init {
        // clear() handles normal teardown; this catches leftovers from crashed or
        // process-killed sessions so the directory can't grow unbounded.
        val cutoff = System.currentTimeMillis() - STALE_THUMB_MAX_AGE_MS
        tempDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /** Deletes all downloaded thumb files; call when the grid is torn down. */
    fun clear() {
        tempDir.listFiles()?.forEach { it.delete() }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<AssetDto>) {
        assets.clear()
        assets.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_preview_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun getItemCount(): Int = assets.size

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val asset = assets[position]
        holder.boundId = asset.id

        val cached = bitmapCache.get(asset.id)
        holder.image.setImageBitmap(cached)
        if (cached == null) loadPhoto(holder, asset.id)
    }

    private fun loadPhoto(holder: PhotoViewHolder, assetId: String) {
        if (!inFlight.add(assetId)) return
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val file = File(tempDir, "$assetId.img")
                        if (!file.exists() || file.length() == 0L) {
                            client.downloadAssetThumbnail(assetId, file)
                        }
                        val bmp = decodeSampled(file, TARGET_EDGE_PX)
                        // An undecodable non-empty file would otherwise pass the
                        // exists/length guard forever — delete it so the next bind
                        // re-downloads instead of leaving the tile blank all session.
                        if (bmp == null) file.delete()
                        bmp
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        null
                    }
                } ?: return@launch
                bitmapCache.put(assetId, bitmap)
                if (holder.boundId == assetId) holder.image.setImageBitmap(bitmap)
            } finally {
                inFlight.remove(assetId)
            }
        }
    }

    class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.preview_photo)
        var boundId: String? = null
    }

    companion object {
        private const val TARGET_EDGE_PX = 360
        private const val STALE_THUMB_MAX_AGE_MS = 24L * 60 * 60 * 1000

        /**
         * Decode [file] with a power-of-2 inSampleSize so the *shorter* edge
         * stays at or above [targetMinEdge]. Returns null on undecodable files.
         */
        internal fun decodeSampled(file: File, targetMinEdge: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetMinEdge &&
                bounds.outHeight / (sample * 2) >= targetMinEdge
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeFile(file.absolutePath, opts)
        }
    }
}
