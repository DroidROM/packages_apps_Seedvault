@file:Suppress("EXPERIMENTAL_API_USAGE", "BlockingMethodInNonBlockingContext")

package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.content.pm.PackageInfo
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.FileUtils.closeQuietly
import android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID
import android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
import android.provider.DocumentsContract.Document.MIME_TYPE_DIR
import android.provider.DocumentsContract.EXTRA_LOADING
import android.provider.DocumentsContract.buildChildDocumentsUriUsingTree
import android.provider.DocumentsContract.buildDocumentUriUsingTree
import android.provider.DocumentsContract.buildTreeDocumentUri
import android.provider.DocumentsContract.getDocumentId
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.settings.Storage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume

const val DIRECTORY_ROOT = ".SeedVaultAndroidBackup"
const val DIRECTORY_FULL_BACKUP = "full"
const val DIRECTORY_KEY_VALUE_BACKUP = "kv"
const val FILE_BACKUP_METADATA = ".backup.metadata"
const val FILE_NO_MEDIA = ".nomedia"
private const val MIME_TYPE = "application/octet-stream"

private val TAG = DocumentsStorage::class.java.simpleName

internal class DocumentsStorage(
    private val context: Context,
    private val metadataManager: MetadataManager,
    private val settingsManager: SettingsManager
) {

    private val contentResolver = context.contentResolver

    internal var storage: Storage? = null
        get() {
            if (field == null) field = settingsManager.getStorage()
            return field
        }

    internal var rootBackupDir: DocumentFile? = null
        get() = runBlocking {
            if (field == null) {
                val parent = storage?.getDocumentFile(context)
                    ?: return@runBlocking null
                field = try {
                    parent.createOrGetDirectory(context, DIRECTORY_ROOT).apply {
                        // create .nomedia file to prevent Android's MediaScanner
                        // from trying to index the backup
                        createOrGetFile(context, FILE_NO_MEDIA)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error creating root backup dir.", e)
                    null
                }
            }
            field
        }

    private var currentToken: Long = 0L
        get() {
            if (field == 0L) field = metadataManager.getBackupToken()
            return field
        }

    private var currentSetDir: DocumentFile? = null
        get() = runBlocking {
            if (field == null) {
                if (currentToken == 0L) return@runBlocking null
                field = try {
                    rootBackupDir?.createOrGetDirectory(context, currentToken.toString())
                } catch (e: IOException) {
                    Log.e(TAG, "Error creating current restore set dir.", e)
                    null
                }
            }
            field
        }

    var currentFullBackupDir: DocumentFile? = null
        get() = runBlocking {
            if (field == null) {
                field = try {
                    currentSetDir?.createOrGetDirectory(context, DIRECTORY_FULL_BACKUP)
                } catch (e: IOException) {
                    Log.e(TAG, "Error creating full backup dir.", e)
                    null
                }
            }
            field
        }

    var currentKvBackupDir: DocumentFile? = null
        get() = runBlocking {
            if (field == null) {
                field = try {
                    currentSetDir?.createOrGetDirectory(context, DIRECTORY_KEY_VALUE_BACKUP)
                } catch (e: IOException) {
                    Log.e(TAG, "Error creating K/V backup dir.", e)
                    null
                }
            }
            field
        }

    fun isInitialized(): Boolean {
        if (settingsManager.getAndResetIsStorageChanging()) return false // storage location has changed
        val kvEmpty = currentKvBackupDir?.listFiles()?.isEmpty() ?: false
        val fullEmpty = currentFullBackupDir?.listFiles()?.isEmpty() ?: false
        return kvEmpty && fullEmpty
    }

    fun reset(newToken: Long) {
        storage = null
        currentToken = newToken
        rootBackupDir = null
        currentSetDir = null
        currentKvBackupDir = null
        currentFullBackupDir = null
    }

    fun getAuthority(): String? = storage?.uri?.authority

    @Throws(IOException::class)
    suspend fun getSetDir(token: Long = currentToken): DocumentFile? {
        if (token == currentToken) return currentSetDir
        return rootBackupDir?.findFileBlocking(context, token.toString())
    }

    @Throws(IOException::class)
    suspend fun getKVBackupDir(token: Long = currentToken): DocumentFile? {
        if (token == currentToken) return currentKvBackupDir ?: throw IOException()
        return getSetDir(token)?.findFileBlocking(context, DIRECTORY_KEY_VALUE_BACKUP)
    }

    @Throws(IOException::class)
    suspend fun getOrCreateKVBackupDir(token: Long = currentToken): DocumentFile {
        if (token == currentToken) return currentKvBackupDir ?: throw IOException()
        val setDir = getSetDir(token) ?: throw IOException()
        return setDir.createOrGetDirectory(context, DIRECTORY_KEY_VALUE_BACKUP)
    }

    @Throws(IOException::class)
    suspend fun getFullBackupDir(token: Long = currentToken): DocumentFile? {
        if (token == currentToken) return currentFullBackupDir ?: throw IOException()
        return getSetDir(token)?.findFileBlocking(context, DIRECTORY_FULL_BACKUP)
    }

    @Throws(IOException::class)
    fun getInputStream(file: DocumentFile): InputStream {
        return contentResolver.openInputStream(file.uri) ?: throw IOException()
    }

    @Throws(IOException::class)
    fun getOutputStream(file: DocumentFile): OutputStream {
        return contentResolver.openOutputStream(file.uri, "wt") ?: throw IOException()
    }

}

/**
 * Checks if a file exists and if not, creates it.
 *
 * If we were trying to create it right away, some providers create "filename (1)".
 */
@Throws(IOException::class)
internal suspend fun DocumentFile.createOrGetFile(
    context: Context,
    name: String,
    mimeType: String = MIME_TYPE
): DocumentFile {
    return findFileBlocking(context, name) ?: createFile(mimeType, name)?.apply {
        check(this.name == name) { "File named ${this.name}, but should be $name" }
    } ?: throw IOException()
}

/**
 * Checks if a directory already exists and if not, creates it.
 */
@Throws(IOException::class)
suspend fun DocumentFile.createOrGetDirectory(context: Context, name: String): DocumentFile {
    return findFileBlocking(context, name) ?: createDirectory(name) ?: throw IOException()
}

@Throws(IOException::class)
fun DocumentFile.deleteContents() {
    for (file in listFiles()) file.delete()
}

fun DocumentFile.assertRightFile(packageInfo: PackageInfo) {
    if (name != packageInfo.packageName) throw AssertionError()
}

/**
 * Works like [DocumentFile.listFiles] except that it waits until the DocumentProvider has a result.
 * This prevents getting an empty list even though there are children to be listed.
 */
@Throws(IOException::class)
suspend fun DocumentFile.listFilesBlocking(context: Context): ArrayList<DocumentFile> {
    val resolver = context.contentResolver
    val childrenUri = buildChildDocumentsUriUsingTree(uri, getDocumentId(uri))
    val projection = arrayOf(COLUMN_DOCUMENT_ID, COLUMN_MIME_TYPE)
    val result = ArrayList<DocumentFile>()

    try {
        getLoadedCursor {
            resolver.query(childrenUri, projection, null, null, null)
        }
    } catch (e: TimeoutCancellationException) {
        throw IOException(e)
    }.use { cursor ->
        while (cursor.moveToNext()) {
            val documentId = cursor.getString(0)
            val isDirectory = cursor.getString(1) == MIME_TYPE_DIR
            val file = if (isDirectory) {
                val treeUri = buildTreeDocumentUri(uri.authority, documentId)
                DocumentFile.fromTreeUri(context, treeUri)!!
            } else {
                val documentUri = buildDocumentUriUsingTree(uri, documentId)
                DocumentFile.fromSingleUri(context, documentUri)!!
            }
            result.add(file)
        }
    }
    return result
}

/**
 * Same as [DocumentFile.findFile] only that it re-queries when the first result was stale.
 *
 * Most documents providers including Nextcloud are listing the full directory content
 * when querying for a specific file in a directory,
 * so there is no point in trying to optimize the query by not listing all children.
 */
suspend fun DocumentFile.findFileBlocking(context: Context, displayName: String): DocumentFile? {
    val files = try {
        listFilesBlocking(context)
    } catch (e: IOException) {
        Log.e(TAG, "Error finding file blocking", e)
        return null
    }
    for (doc in files) {
        if (displayName == doc.name) return doc
    }
    return null
}

/**
 * Returns a cursor for the given query while ensuring that the cursor was loaded.
 *
 * When the SAF backend is a cloud storage provider (e.g. Nextcloud),
 * it can happen that the query returns an outdated (e.g. empty) cursor
 * which will only be updated in response to this query.
 *
 * See: https://commonsware.com/blog/2019/12/14/scoped-storage-stories-listfiles-woe.html
 *
 * This method uses a [suspendCancellableCoroutine] to wait for the result of a [ContentObserver]
 * registered on the cursor in case the cursor is still loading ([EXTRA_LOADING]).
 * If the cursor is not loading, it will be returned right away.
 *
 * @param timeout an optional time-out in milliseconds
 * @throws TimeoutCancellationException if there was no result before the time-out
 * @throws IOException if the query returns null
 */
@VisibleForTesting
@Throws(IOException::class, TimeoutCancellationException::class)
internal suspend fun getLoadedCursor(timeout: Long = 15_000, query: () -> Cursor?) =
    withTimeout(timeout) {
        suspendCancellableCoroutine<Cursor> { cont ->
            val cursor = query() ?: throw IOException()
            cont.invokeOnCancellation { closeQuietly(cursor) }
            val loading = cursor.extras.getBoolean(EXTRA_LOADING, false)
            if (loading) {
                Log.d(TAG, "Wait for children to get loaded...")
                cursor.registerContentObserver(object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        Log.d(TAG, "Children loaded. Continue...")
                        closeQuietly(cursor)
                        val newCursor = query()
                        if (newCursor == null) cont.cancel(IOException("query returned no results"))
                        else cont.resume(newCursor)
                    }
                })
            } else {
                // not loading, return cursor right away
                cont.resume(cursor)
            }
        }
    }
