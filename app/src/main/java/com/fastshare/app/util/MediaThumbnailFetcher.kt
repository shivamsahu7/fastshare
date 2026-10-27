package com.fastshare.app.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.Dimension
import coil.size.Size
import java.io.IOException

/**
 * Loads MediaStore images/videos through the system thumbnail cache instead of decoding the full
 * file. Much cheaper per grid cell, and it is what makes video thumbnails work at all (Coil can't
 * decode video frames without an extra decoder).
 */
class MediaThumbnailFetcher(
    private val uri: Uri,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val context = options.context
        val width = (options.size.width as? Dimension.Pixels)?.px ?: DEFAULT_PX
        val height = (options.size.height as? Dimension.Pixels)?.px ?: DEFAULT_PX
        val bitmap = loadThumbnail(context, width, height)
            ?: throw IOException("No thumbnail for $uri")
        return DrawableResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    private fun loadThumbnail(context: Context, width: Int, height: Int): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return resolver.loadThumbnail(uri, android.util.Size(width, height), null)
        }
        val id = ContentUris.parseId(uri)
        @Suppress("DEPRECATION")
        return if (uri.pathSegments.contains("video")) {
            MediaStore.Video.Thumbnails.getThumbnail(resolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(resolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "content" || data.authority != MediaStore.AUTHORITY) return null
            if (options.size == Size.ORIGINAL) return null
            val segments = data.pathSegments
            if ("images" !in segments && "video" !in segments) return null
            return MediaThumbnailFetcher(data, options)
        }
    }

    private companion object {
        const val DEFAULT_PX = 256
    }
}
