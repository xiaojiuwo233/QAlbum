package com.xjw.qalbum.service.ocr

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class OcrModelManifest(
    val schema: Int,
    val id: String,
    val displayName: String,
    val version: String,
    val engine: String,
    val assets: List<OcrModelAsset> = emptyList(),
    val legacyDownloadUrl: String? = null,
    val legacySha256: String? = null,
    val legacySizeBytes: Long? = null,
    val detDir: String,
    val recDir: String,
    val clsDir: String? = null,
    val dictionary: String,
) {
    fun isValid(): Boolean =
        schema == SCHEMA &&
            id.isNotBlank() &&
            displayName.isNotBlank() &&
            version.isNotBlank() &&
            engine == ENGINE_PADDLE &&
            assets.isNotEmpty() &&
            assets.all { it.isValid() } &&
            detDir.isNotBlank() &&
            recDir.isNotBlank() &&
            dictionary.isNotBlank()

    companion object {
        const val SCHEMA = 1
        const val ENGINE_PADDLE = "paddle"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun parse(raw: String): OcrModelManifest = json.decodeFromString(raw)

        fun isValidSha256(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}

@Serializable
data class OcrModelAsset(
    val name: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val targetDir: String,
    val archiveType: String = ARCHIVE_TAR,
) {
    fun isValid(): Boolean =
        name.isNotBlank() &&
            url.startsWith("https://") &&
            OcrModelManifest.isValidSha256(sha256) &&
            sizeBytes > 0 &&
            targetDir.isNotBlank() &&
            archiveType in setOf(ARCHIVE_TAR, ARCHIVE_ZIP, ARCHIVE_FILE)

    companion object {
        const val ARCHIVE_TAR = "tar"
        const val ARCHIVE_ZIP = "zip"
        const val ARCHIVE_FILE = "file"
    }
}

data class OcrModelPaths(
    val rootDir: String,
    val detDir: String,
    val recDir: String,
    val clsDir: String?,
    val dictionary: String,
)

sealed class OcrModelStatus {
    data object NotInstalled : OcrModelStatus()
    data class Ready(
        val manifest: OcrModelManifest,
        val installedBytes: Long,
    ) : OcrModelStatus()

    data class Failed(val reason: String) : OcrModelStatus()
}

interface OcrModelStore {
    fun currentStatus(): OcrModelStatus
    fun modelPaths(): OcrModelPaths?
}

object OcrModelManifestJson {
    fun encode(manifest: OcrModelManifest): String = json.encodeToString(manifest)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
}
