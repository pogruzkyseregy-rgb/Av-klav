package com.avrora.klav

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Отдаёт стикеры из assets/stickers по адресу content://com.avrora.klav.stickers/имя.png.
 * Клавиатура передаёт этот адрес в поле ввода через commitContent, а приложение получает доступ
 * только на чтение и только к этому файлу.
 */
class StickerProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw FileNotFoundException("no name")
        val ctx = context ?: throw FileNotFoundException("no context")
        val known = (ctx.assets.list(DIR) ?: emptyArray()).contains(name)
        if (!known || name.contains('/')) throw FileNotFoundException(name)

        // Копия в кеше: ParcelFileDescriptor нужен настоящий файл, а не поток из assets.
        val out = File(File(ctx.cacheDir, DIR).apply { mkdirs() }, name)
        if (!out.exists()) {
            ctx.assets.open("$DIR/$name").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.avrora.klav.stickers"
        private const val DIR = "stickers"
    }
}
