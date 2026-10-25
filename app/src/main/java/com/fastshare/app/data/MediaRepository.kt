package com.fastshare.app.data

import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

data class InstalledAppItem(
    val name: String,
    val packageName: String,
    val size: Long,
    val sourceDir: String,
    val icon: Drawable?
)

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    val dateModified: Long = 0
)

data class DayPhotoGroup(
    val dateLabel: String,
    val dayKey: Long,
    val photos: List<MediaItem>
)

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val durationMs: Long
) {
    val formattedDuration: String get() {
        val totalSec = durationMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }
}

data class AudioItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val artist: String,
    val size: Long,
    val durationMs: Long
) {
    val formattedDuration: String get() {
        val totalSec = durationMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }
}

data class ContactItem(
    val id: String,
    val name: String,
    val phone: String
)

data class StorageDrive(
    val name: String,
    val rootFile: File,
    val isSdCard: Boolean,
    val totalSpace: Long,
    val freeSpace: Long
) {
    val usedSpace: Long get() = (totalSpace - freeSpace).coerceAtLeast(0)
    val usedPercent: Float get() = if (totalSpace > 0) usedSpace.toFloat() / totalSpace.toFloat() else 0f
}

enum class CategorizedFileType(val title: String, val subtitle: String) {
    DOCUMENTS("Documents", "Includes Word, PPT, Excel, WPS, etc."),
    EBOOKS("Ebooks", "Includes .umd, .ebk, .txt, .chm, etc."),
    APKS("Apks", "Includes .apk files"),
    ARCHIVES("Archives", "Includes .7z, .rar, .zip, .iso, etc."),
    BIG_FILES("Big files", "Includes files > 50 MB")
}

data class CategorizedFileInfo(
    val file: File,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val extension: String
)

data class FileExplorerItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val itemCount: Int,
    val lastModified: Long,
    val extension: String
) {
    val formattedDate: String get() {
        return try {
            val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            sdf.format(Date(lastModified))
        } catch (e: Exception) {
            ""
        }
    }
}

class MediaRepository(private val context: Context) {

    // -------------------------------------------------------------------------------------
    // Installed Apps
    // -------------------------------------------------------------------------------------
    suspend fun loadInstalledApps(): List<InstalledAppItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = mutableListOf<InstalledAppItem>()
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        for (appInfo in installedApps) {
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            if (!isSystem || isUpdatedSystem) {
                try {
                    val apkFile = File(appInfo.sourceDir)
                    if (apkFile.exists()) {
                        val name = pm.getApplicationLabel(appInfo).toString()
                        val icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
                        apps.add(
                            InstalledAppItem(
                                name = name,
                                packageName = appInfo.packageName,
                                size = apkFile.length(),
                                sourceDir = appInfo.sourceDir,
                                icon = icon
                            )
                        )
                    }
                } catch (e: Exception) {
                    // ignore individual app read failures
                }
            }
        }
        apps.sortedBy { it.name.lowercase() }
    }

    // -------------------------------------------------------------------------------------
    // Photos (Gallery MediaStore with fallback to disk scanning)
    // -------------------------------------------------------------------------------------
    suspend fun loadPhotos(): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "photo_$id.jpg"
                    val size = cursor.getLong(sizeCol)
                    val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                    val date = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    items.add(MediaItem(id = id, uri = uri, name = name, size = size, mimeType = mime, dateModified = date))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback: If MediaStore returned empty, scan DCIM and Pictures directories
        if (items.isEmpty()) {
            val scanDirs = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            )
            for (dir in scanDirs) {
                scanImagesFromDirectory(dir, items)
            }
        }

        items
    }

    private fun scanImagesFromDirectory(dir: File?, result: MutableList<MediaItem>) {
        if (dir == null || !dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory && !f.name.startsWith(".")) {
                scanImagesFromDirectory(f, result)
            } else if (f.isFile) {
                val ext = f.extension.lowercase()
                if (ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")) {
                    result.add(
                        MediaItem(
                            id = f.hashCode().toLong(),
                            uri = Uri.fromFile(f),
                            name = f.name,
                            size = f.length(),
                            mimeType = "image/$ext",
                            dateModified = f.lastModified()
                        )
                    )
                }
            }
        }
    }

    fun groupPhotosByDay(photos: List<MediaItem>): List<DayPhotoGroup> {
        val calNow = Calendar.getInstance()
        val todayYear = calNow.get(Calendar.YEAR)
        val todayDayOfYear = calNow.get(Calendar.DAY_OF_YEAR)

        calNow.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayYear = calNow.get(Calendar.YEAR)
        val yesterdayDayOfYear = calNow.get(Calendar.DAY_OF_YEAR)

        val itemCal = Calendar.getInstance()
        val yearSdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        val thisYearSdf = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

        val grouped = linkedMapOf<Long, MutableList<MediaItem>>()
        val labelMap = mutableMapOf<Long, String>()

        for (photo in photos) {
            val millis = if (photo.dateModified > 0L) {
                if (photo.dateModified < 10000000000L) photo.dateModified * 1000L else photo.dateModified
            } else {
                0L
            }

            if (millis <= 0L) {
                val key = 0L
                grouped.getOrPut(key) { mutableListOf() }.add(photo)
                labelMap[key] = "Earlier"
                continue
            }

            itemCal.timeInMillis = millis
            val y = itemCal.get(Calendar.YEAR)
            val doy = itemCal.get(Calendar.DAY_OF_YEAR)

            itemCal.set(Calendar.HOUR_OF_DAY, 0)
            itemCal.set(Calendar.MINUTE, 0)
            itemCal.set(Calendar.SECOND, 0)
            itemCal.set(Calendar.MILLISECOND, 0)
            val dayKey = itemCal.timeInMillis

            grouped.getOrPut(dayKey) { mutableListOf() }.add(photo)

            if (!labelMap.containsKey(dayKey)) {
                val label = when {
                    y == todayYear && doy == todayDayOfYear -> "Today"
                    y == yesterdayYear && doy == yesterdayDayOfYear -> "Yesterday"
                    y == todayYear -> thisYearSdf.format(Date(millis))
                    else -> yearSdf.format(Date(millis))
                }
                labelMap[dayKey] = label
            }
        }

        return grouped.map { (key, list) ->
            DayPhotoGroup(
                dateLabel = labelMap[key] ?: "Earlier",
                dayKey = key,
                photos = list
            )
        }
    }

    // -------------------------------------------------------------------------------------
    // Videos (Gallery MediaStore with fallback to disk scanning)
    // -------------------------------------------------------------------------------------
    suspend fun loadVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "video_$id.mp4"
                    val size = cursor.getLong(sizeCol)
                    val dur = if (durCol != -1) cursor.getLong(durCol) else 0L
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    items.add(VideoItem(id = id, uri = uri, name = name, size = size, durationMs = dur))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback: If MediaStore returned empty, scan DCIM/Camera and Movies
        if (items.isEmpty()) {
            val scanDirs = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            )
            for (dir in scanDirs) {
                scanVideosFromDirectory(dir, items)
            }
        }

        items
    }

    private fun scanVideosFromDirectory(dir: File?, result: MutableList<VideoItem>) {
        if (dir == null || !dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory && !f.name.startsWith(".")) {
                scanVideosFromDirectory(f, result)
            } else if (f.isFile) {
                val ext = f.extension.lowercase()
                if (ext in listOf("mp4", "mkv", "3gp", "webm", "avi", "mov", "ts")) {
                    result.add(
                        VideoItem(
                            id = f.hashCode().toLong(),
                            uri = Uri.fromFile(f),
                            name = f.name,
                            size = f.length(),
                            durationMs = 0L
                        )
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Audio / Music Media
    // -------------------------------------------------------------------------------------
    suspend fun loadAudios(): List<AudioItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<AudioItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION
        )
        val sortOrder = "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "audio_$id.mp3"
                    val artist = if (artistCol != -1) cursor.getString(artistCol) ?: "Unknown Artist" else "Unknown Artist"
                    val size = cursor.getLong(sizeCol)
                    val dur = if (durCol != -1) cursor.getLong(durCol) else 0L
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                    items.add(AudioItem(id = id, uri = uri, name = name, artist = artist, size = size, durationMs = dur))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (items.isEmpty()) {
            val scanDirs = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            )
            for (dir in scanDirs) {
                scanAudiosFromDirectory(dir, items)
            }
        }

        items
    }

    private fun scanAudiosFromDirectory(dir: File?, result: MutableList<AudioItem>) {
        if (dir == null || !dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory && !f.name.startsWith(".")) {
                scanAudiosFromDirectory(f, result)
            } else if (f.isFile) {
                val ext = f.extension.lowercase()
                if (ext in listOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus")) {
                    result.add(
                        AudioItem(
                            id = f.hashCode().toLong(),
                            uri = Uri.fromFile(f),
                            name = f.name,
                            artist = "Local File",
                            size = f.length(),
                            durationMs = 0L
                        )
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Built-in Storage Drives (Internal Storage & SD Card)
    // -------------------------------------------------------------------------------------
    fun getStorageDrives(): List<StorageDrive> {
        val drives = mutableListOf<StorageDrive>()

        // 1. Internal Storage
        val internalRoot = Environment.getExternalStorageDirectory()
        if (internalRoot != null && internalRoot.exists()) {
            drives.add(
                StorageDrive(
                    name = "Internal Storage",
                    rootFile = internalRoot,
                    isSdCard = false,
                    totalSpace = internalRoot.totalSpace,
                    freeSpace = internalRoot.freeSpace
                )
            )
        }

        // 2. Secondary SD Card Storage
        try {
            val externalDirs = context.getExternalFilesDirs(null)
            for (dir in externalDirs) {
                if (dir != null && Environment.isExternalStorageRemovable(dir)) {
                    var current: File? = dir
                    while (current != null && current.parentFile != null &&
                        current.parentFile?.path != "/storage" &&
                        current.parentFile?.path != "/"
                    ) {
                        current = current.parentFile
                    }
                    if (current != null && current.exists() && drives.none { it.rootFile.path == current.path }) {
                        drives.add(
                            StorageDrive(
                                name = "SD Card",
                                rootFile = current,
                                isSdCard = true,
                                totalSpace = current.totalSpace,
                                freeSpace = current.freeSpace
                            )
                        )
                    }
                }
            }

            // Also check common OEM SD Card paths under /storage/
            val storageDir = File("/storage")
            if (storageDir.exists() && storageDir.isDirectory) {
                storageDir.listFiles()?.forEach { file ->
                    if (file.isDirectory && file.canRead() &&
                        !file.name.equals("emulated", ignoreCase = true) &&
                        !file.name.equals("self", ignoreCase = true) &&
                        drives.none { it.rootFile.path == file.path }
                    ) {
                        drives.add(
                            StorageDrive(
                                name = "SD Card (${file.name})",
                                rootFile = file,
                                isSdCard = true,
                                totalSpace = file.totalSpace,
                                freeSpace = file.freeSpace
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return drives
    }

    // -------------------------------------------------------------------------------------
    // Categorized Files (Documents, Ebooks, Apks, Archives, Big Files)
    // -------------------------------------------------------------------------------------
    suspend fun loadCategorizedFiles(type: CategorizedFileType): List<CategorizedFileInfo> = withContext(Dispatchers.IO) {
        val items = mutableListOf<CategorizedFileInfo>()
        val docExtensions = setOf("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "wps", "csv", "rtf")
        val ebookExtensions = setOf("txt", "epub", "mobi", "chm", "ebk", "umd")
        val apkExtensions = setOf("apk")
        val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "iso")
        val minBigFileSize = 50 * 1024 * 1024L // 50MB

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        try {
            val uri = MediaStore.Files.getContentUri("external")
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val path = if (dataCol >= 0) cursor.getString(dataCol) else null ?: continue
                    val file = File(path)
                    if (!file.exists() || file.isDirectory) continue

                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else file.length()
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: file.name else file.name
                    val ext = file.extension.lowercase()

                    val matches = when (type) {
                        CategorizedFileType.DOCUMENTS -> ext in docExtensions
                        CategorizedFileType.EBOOKS -> ext in ebookExtensions
                        CategorizedFileType.APKS -> ext in apkExtensions
                        CategorizedFileType.ARCHIVES -> ext in archiveExtensions
                        CategorizedFileType.BIG_FILES -> size >= minBigFileSize
                    }

                    if (matches) {
                        val dateModified = if (dateCol >= 0) cursor.getLong(dateCol) * 1000 else file.lastModified()
                        items.add(CategorizedFileInfo(file, name, size, dateModified, ext))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback: also scan Download and Documents directory if items is small
        if (items.isEmpty()) {
            val dirs = listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            )
            for (dir in dirs) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && !file.name.startsWith(".")) {
                        val ext = file.extension.lowercase()
                        val matches = when (type) {
                            CategorizedFileType.DOCUMENTS -> ext in docExtensions
                            CategorizedFileType.EBOOKS -> ext in ebookExtensions
                            CategorizedFileType.APKS -> ext in apkExtensions
                            CategorizedFileType.ARCHIVES -> ext in archiveExtensions
                            CategorizedFileType.BIG_FILES -> file.length() >= minBigFileSize
                        }
                        if (matches && items.none { it.file.absolutePath == file.absolutePath }) {
                            items.add(CategorizedFileInfo(file, file.name, file.length(), file.lastModified(), ext))
                        }
                    }
                }
            }
        }

        items.sortedByDescending { it.lastModified }
    }

    suspend fun getCategorizedCounts(): Map<CategorizedFileType, Int> = withContext(Dispatchers.IO) {
        val counts = mutableMapOf<CategorizedFileType, Int>()
        CategorizedFileType.values().forEach { cat ->
            counts[cat] = loadCategorizedFiles(cat).size
        }
        counts
    }

    // -------------------------------------------------------------------------------------
    // In-App Directory Explorer
    // -------------------------------------------------------------------------------------
    suspend fun listDirectory(directory: File): List<FileExplorerItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<FileExplorerItem>()
        try {
            val files = directory.listFiles() ?: return@withContext emptyList()
            for (f in files) {
                if (f.name.startsWith(".")) continue // Skip hidden system files

                val isDir = f.isDirectory
                val subCount = if (isDir) (f.listFiles()?.size ?: 0) else 0
                val size = if (isDir) 0L else f.length()
                val ext = if (isDir) "" else f.extension.lowercase()

                items.add(
                    FileExplorerItem(
                        file = f,
                        name = f.name,
                        isDirectory = isDir,
                        size = size,
                        itemCount = subCount,
                        lastModified = f.lastModified(),
                        extension = ext
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Directories first, then alphabetical
        items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    // -------------------------------------------------------------------------------------
    // Contacts
    // -------------------------------------------------------------------------------------
    suspend fun loadContacts(): List<ContactItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<ContactItem>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)

                val seenPhones = HashSet<String>()
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idCol)
                    val name = cursor.getString(nameCol) ?: "Contact"
                    val phone = cursor.getString(numCol)?.replace(" ", "") ?: ""

                    if (phone.isNotEmpty() && seenPhones.add(phone)) {
                        items.add(ContactItem(id = id, name = name, phone = phone))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        items
    }

    suspend fun exportContactToVcf(contact: ContactItem): SharedFileItem = withContext(Dispatchers.IO) {
        val vcard = """
            BEGIN:VCARD
            VERSION:3.0
            FN:${contact.name}
            TEL;TYPE=CELL:${contact.phone}
            END:VCARD
        """.trimIndent()

        val safeName = contact.name.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val cacheDir = File(context.cacheDir, "contacts")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val vcfFile = File(cacheDir, "$safeName.vcf")

        FileOutputStream(vcfFile).use { it.write(vcard.toByteArray()) }

        SharedFileItem(
            id = UUID.randomUUID().toString(),
            uri = Uri.fromFile(vcfFile),
            name = "$safeName.vcf",
            relativePath = "$safeName.vcf",
            size = vcfFile.length(),
            mimeType = "text/x-vcard"
        )
    }

    fun convertAppToSharedFile(app: InstalledAppItem): SharedFileItem {
        val apkFile = File(app.sourceDir)
        val cleanName = "${app.name.replace(Regex("[^a-zA-Z0-9_.-]"), "_")}.apk"
        return SharedFileItem(
            id = UUID.randomUUID().toString(),
            uri = Uri.fromFile(apkFile),
            name = cleanName,
            relativePath = cleanName,
            size = apkFile.length(),
            mimeType = "application/vnd.android.package-archive"
        )
    }

    suspend fun convertDiskFileToSharedItem(file: File, relativeBasePath: String? = null): SharedFileItem = withContext(Dispatchers.IO) {
        val relPath = if (relativeBasePath != null && file.path.startsWith(relativeBasePath)) {
            file.path.removePrefix(relativeBasePath).removePrefix(File.separator)
        } else {
            file.name
        }
        SharedFileItem(
            id = UUID.randomUUID().toString(),
            uri = Uri.fromFile(file),
            name = file.name,
            relativePath = relPath,
            size = file.length(),
            mimeType = getMimeTypeFromExtension(file.extension)
        )
    }

    suspend fun convertFolderToSharedItems(folder: File): List<SharedFileItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<SharedFileItem>()
        val baseParent = folder.parentFile?.path ?: ""

        fun scan(f: File) {
            val list = f.listFiles() ?: return
            for (child in list) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    scan(child)
                } else if (child.isFile) {
                    val relPath = if (baseParent.isNotEmpty() && child.path.startsWith(baseParent)) {
                        child.path.removePrefix(baseParent).removePrefix(File.separator)
                    } else {
                        child.name
                    }
                    items.add(
                        SharedFileItem(
                            id = UUID.randomUUID().toString(),
                            uri = Uri.fromFile(child),
                            name = child.name,
                            relativePath = relPath,
                            size = child.length(),
                            mimeType = getMimeTypeFromExtension(child.extension)
                        )
                    )
                }
            }
        }

        scan(folder)
        items
    }

    private fun getMimeTypeFromExtension(ext: String): String {
        return when (ext.lowercase()) {
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "txt" -> "text/plain"
            "doc", "docx" -> "application/msword"
            else -> "application/octet-stream"
        }
    }
}
