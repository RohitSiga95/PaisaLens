package com.paisalens.app.data.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.model.BackupVerificationMetadata
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class ScheduledBackupRunResult(
    val succeeded: Boolean,
    val completedAt: Long,
    val fileName: String? = null,
    val verification: BackupVerificationMetadata? = null,
    val failureMessage: String? = null,
    val rotationWarning: String? = null,
)

/**
 * Writes an encrypted file to private cache first, verifies it, copies it through SAF,
 * re-verifies the destination, and only then rotates older PaisaLens auto-backups.
 */
class ScheduledBackupRunner(context: Context) {
    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver

    fun run(
        configuration: ScheduledBackupConfiguration,
        passphrase: CharArray,
        now: Long = System.currentTimeMillis(),
    ): ScheduledBackupRunResult {
        val safe = configuration.normalized()
        if (!safe.isReady) {
            passphrase.fill('\u0000')
            return failure(now, "Choose a backup folder before enabling scheduled backups.")
        }
        var temporary: File? = null
        return try {
            val encryptedTemporary = File.createTempFile(
                "paisalens-auto-",
                SCHEDULED_BACKUP_FILE_SUFFIX,
                applicationContext.cacheDir,
            )
            temporary = encryptedTemporary
            writeAndVerifyTemporary(encryptedTemporary, passphrase)
            val destination = writeAndVerifyDestination(
                destination = Uri.parse(requireNotNull(safe.destinationUri)),
                source = encryptedTemporary,
                passphrase = passphrase,
                retentionCount = safe.retentionCount,
                now = now,
            )
            ScheduledBackupRunResult(
                succeeded = true,
                completedAt = now,
                fileName = destination.fileName,
                verification = destination.verification,
                rotationWarning = destination.rotationWarning,
            )
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            failure(now, scheduledBackupFailureMessage(error))
        } finally {
            passphrase.fill('\u0000')
            temporary?.delete()
        }
    }

    private fun writeAndVerifyTemporary(file: File, passphrase: CharArray) {
        val database = PaisaLensDatabase(applicationContext)
        try {
            FileOutputStream(file).use { output ->
                val copy = passphrase.copyOf()
                try {
                    PaisaLensBackupCodec.write(
                        database.snapshot(),
                        copy,
                        output,
                    )
                } finally {
                    copy.fill('\u0000')
                }
            }
        } finally {
            database.close()
        }
        FileInputStream(file).use { input -> verifyWithWipedCopy(passphrase, input) }
    }

    private fun writeAndVerifyDestination(
        destination: Uri,
        source: File,
        passphrase: CharArray,
        retentionCount: Int,
        now: Long,
    ): DestinationResult {
        require(destination.scheme == ContentResolver.SCHEME_CONTENT) {
            "Scheduled backup destination must be selected with Android's folder picker"
        }
        val isTree = DocumentsContract.isTreeUri(destination)
        val finalFileName = scheduledBackupFileName(now)
        var createdDocument: Uri? = null
        var outputUri = if (isTree) {
            val rootId = DocumentsContract.getTreeDocumentId(destination)
            val rootDocument = DocumentsContract.buildDocumentUriUsingTree(destination, rootId)
            DocumentsContract.createDocument(
                resolver,
                rootDocument,
                BACKUP_MIME_TYPE,
                finalFileName + SCHEDULED_BACKUP_PARTIAL_SUFFIX,
            )?.also { createdDocument = it }
                ?: throw IOException("The selected folder could not create a backup file")
        } else {
            destination
        }

        val rollbackCopy = if (isTree) null else captureExistingDocument(outputUri)

        try {
            openTruncatingOutput(outputUri)?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: throw IOException("The selected backup file could not be opened")
            resolver.openInputStream(outputUri)?.use { input ->
                verifyWithWipedCopy(passphrase, input)
            } ?: throw IOException("The copied backup could not be reopened for verification")
            if (isTree) {
                outputUri = DocumentsContract.renameDocument(resolver, outputUri, finalFileName)
                    ?: throw IOException("The selected folder cannot atomically finish a backup file")
                createdDocument = outputUri
            }
            val verification = resolver.openInputStream(outputUri)?.use { input ->
                verifyWithWipedCopy(passphrase, input)
            } ?: throw IOException("The finished backup could not be reopened for verification")
            val fileName = queryDisplayName(outputUri) ?: finalFileName
            val rotationWarning = if (isTree) {
                rotateTreeBackups(destination, outputUri, retentionCount, passphrase).exceptionOrNull()?.let {
                    "Backup verified, but older copies could not be rotated."
                }
            } else {
                null
            }
            return DestinationResult(fileName, verification, rotationWarning)
        } catch (error: Throwable) {
            // A newly created, incomplete document is safe to remove. Existing document URIs
            // are never deleted because they may be a user-selected file with prior contents.
            createdDocument?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            rollbackCopy?.let { previous ->
                runCatching {
                    openTruncatingOutput(outputUri)?.use { output ->
                        FileInputStream(previous).use { input -> input.copyTo(output) }
                    } ?: throw IOException("The previous backup could not be restored")
                }
            }
            throw error
        } finally {
            rollbackCopy?.delete()
        }
    }

    /** Keeps a bounded encrypted rollback copy before overwriting a selected document URI. */
    private fun captureExistingDocument(uri: Uri): File? {
        val reportedSize = resolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.safeLong(OpenableColumns.SIZE) else 0L
        } ?: 0L
        if (reportedSize > MAX_DOCUMENT_ROLLBACK_BYTES) {
            throw IOException("The selected document is too large to replace safely")
        }
        val rollback = File.createTempFile(
            "paisalens-previous-",
            SCHEDULED_BACKUP_FILE_SUFFIX,
            applicationContext.cacheDir,
        )
        return try {
            val input = resolver.openInputStream(uri)
            if (input == null) {
                rollback.delete()
                return null
            }
            input.use { source ->
                FileOutputStream(rollback).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_DOCUMENT_ROLLBACK_BYTES) {
                            throw IOException("The selected document is too large to replace safely")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (rollback.length() > 0L) {
                rollback
            } else {
                rollback.delete()
                null
            }
        } catch (error: Throwable) {
            rollback.delete()
            throw error
        }
    }

    private fun rotateTreeBackups(
        treeUri: Uri,
        protectedUri: Uri,
        retentionCount: Int,
        passphrase: CharArray,
    ): Result<Unit> = runCatching {
        val documents = queryTreeDocuments(treeUri)
        documents.filter { document ->
            document.displayName.isScheduledBackupPartialFileName() &&
                document.lastModifiedAt in 1 until (System.currentTimeMillis() - STALE_PARTIAL_AGE_MILLIS)
        }.forEach { document ->
            runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(document.uri)) }
        }
        val verifiedBackups = documents.filter { document ->
            document.uri == protectedUri.toString() || (
                document.displayName.isScheduledBackupFileName() &&
                    runCatching {
                        resolver.openInputStream(Uri.parse(document.uri))?.use { input ->
                            verifyWithWipedCopy(passphrase, input)
                        } ?: error("Backup could not be opened")
                    }.isSuccess
                )
        }
        rotatingBackupsToDelete(verifiedBackups, retentionCount, protectedUri.toString()).forEach { document ->
            check(DocumentsContract.deleteDocument(resolver, Uri.parse(document.uri))) {
                "The provider did not remove ${document.displayName}"
            }
        }
    }

    private fun queryTreeDocuments(treeUri: Uri): List<ScheduledBackupDocument> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return resolver.query(children, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val documentId = cursor.safeString(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        ?: continue
                    val name = cursor.safeString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        ?: continue
                    add(
                        ScheduledBackupDocument(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString(),
                            displayName = name,
                            lastModifiedAt = cursor.safeLong(
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                            ),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun queryDisplayName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.safeString(OpenableColumns.DISPLAY_NAME) else null
    }

    private fun openTruncatingOutput(uri: Uri): OutputStream? {
        val readWrite = try {
            resolver.openOutputStream(uri, "rwt")
        } catch (_: Exception) {
            null
        }
        return readWrite ?: resolver.openOutputStream(uri, "wt")
    }

    private fun verifyWithWipedCopy(
        passphrase: CharArray,
        input: java.io.InputStream,
    ): BackupVerificationMetadata {
        val copy = passphrase.copyOf()
        return try {
            PaisaLensBackupCodec.verify(copy, input)
        } finally {
            copy.fill('\u0000')
        }
    }

    private fun Cursor.safeString(columnName: String): String? =
        getColumnIndex(columnName).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.safeLong(columnName: String): Long =
        getColumnIndex(columnName).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L

    private data class DestinationResult(
        val fileName: String,
        val verification: BackupVerificationMetadata,
        val rotationWarning: String?,
    )
}

fun takeScheduledBackupDestinationPermission(
    context: Context,
    uri: Uri,
    grantFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
): Result<Unit> = runCatching {
    require(uri.scheme == ContentResolver.SCHEME_CONTENT) {
        "Select a folder or document with Android's system picker"
    }
    context.contentResolver.takePersistableUriPermission(
        uri,
        grantFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
    )
    check(hasScheduledBackupDestinationPermission(context, uri)) {
        "The selected provider did not grant persistent read and write access"
    }
}

fun hasScheduledBackupDestinationPermission(context: Context, uri: Uri): Boolean =
    context.contentResolver.persistedUriPermissions.any { permission ->
        permission.uri == uri && permission.isReadPermission && permission.isWritePermission
    }

fun releaseScheduledBackupDestinationPermission(context: Context, uri: Uri): Result<Unit> = runCatching {
    context.contentResolver.releasePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
}

private fun scheduledBackupFileName(now: Long): String =
    SCHEDULED_BACKUP_FILE_PREFIX + BACKUP_NAME_FORMATTER.format(Instant.ofEpochMilli(now)) +
        SCHEDULED_BACKUP_FILE_SUFFIX

private fun String.isScheduledBackupPartialFileName(): Boolean =
    startsWith(SCHEDULED_BACKUP_FILE_PREFIX) && endsWith(SCHEDULED_BACKUP_PARTIAL_SUFFIX)

private fun scheduledBackupFailureMessage(error: Throwable): String = when (error) {
    is SecurityException -> "Backup folder access expired. Choose the folder again in Settings."
    is java.io.FileNotFoundException -> "The backup folder is unavailable. Choose it again in Settings."
    is IOException -> "Android could not safely write or verify the scheduled backup."
    is IllegalArgumentException -> error.message?.take(160) ?: "The backup configuration is invalid."
    else -> "The scheduled backup could not be completed. Check the destination and try again."
}

private fun failure(now: Long, message: String) = ScheduledBackupRunResult(
    succeeded = false,
    completedAt = now,
    failureMessage = message,
)

private val BACKUP_NAME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC)
private const val BACKUP_MIME_TYPE = "application/octet-stream"
private const val MAX_DOCUMENT_ROLLBACK_BYTES = 70L * 1024L * 1024L
private const val SCHEDULED_BACKUP_PARTIAL_SUFFIX = ".partial"
private const val STALE_PARTIAL_AGE_MILLIS = 24L * 60L * 60L * 1_000L
