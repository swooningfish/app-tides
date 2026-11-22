package com.xrmx.tides.platform

import com.russhwolf.settings.Settings

/** Small text files kept on the device (station lists, calibrated models). Not backed up. */
interface FileStore {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun delete(name: String)
    fun list(): List<String>
}

/** In-memory store, for tests and previews. */
class MemoryFileStore : FileStore {
    private val files = mutableMapOf<String, String>()
    override fun read(name: String) = files[name]
    override fun write(name: String, text: String) { files[name] = text }
    override fun delete(name: String) { files.remove(name) }
    override fun list() = files.keys.toList()
}

expect fun defaultFileStore(): FileStore

/** Where API keys live: excluded from backups on Android, the Keychain on iOS. */
expect fun secretSettings(): Settings
