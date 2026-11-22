package com.xrmx.tides.platform

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import com.xrmx.tides.TidesApplication
import java.io.File

private class AndroidFileStore(private val dir: File) : FileStore {
    init { dir.mkdirs() }
    override fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()
    override fun write(name: String, text: String) {
        // Write then rename, so a crash mid-write never leaves a truncated file.
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(dir, name))
    }
    override fun delete(name: String) { File(dir, name).delete() }
    override fun list(): List<String> = dir.list()?.filterNot { it.endsWith(".tmp") }.orEmpty()
}

// noBackupFilesDir: caches can always be re-downloaded, so they stay out of cloud backups.
actual fun defaultFileStore(): FileStore = AndroidFileStore(File(TidesApplication.context.noBackupFilesDir, "data"))

/** A separate preferences file, excluded from backups in res/xml (see data_extraction_rules.xml). */
actual fun secretSettings(): Settings =
    SharedPreferencesSettings(TidesApplication.context.getSharedPreferences("tides_secrets", Context.MODE_PRIVATE))
