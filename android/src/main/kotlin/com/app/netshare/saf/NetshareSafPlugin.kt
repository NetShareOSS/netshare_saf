package com.app.netshare.saf

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class NetshareSafPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware,
    PluginRegistry.ActivityResultListener {
    private var channel: MethodChannel? = null
    private var context: Context? = null
    private var activity: Activity? = null
    private var pendingPickResult: Result? = null
    private val writeSessions = mutableMapOf<String, OutputStream>()
    private val readSessions = mutableMapOf<String, InputStream>()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "netshare_saf")
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        context = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        try {
            when (call.method) {
                "pickDirectory" -> pickDirectory(result)
                "hasPersistedPermission" -> result.success(
                    hasPersistedPermission(call.requiredString("treeUri"))
                )
                "listFiles" -> result.success(listFiles(call.requiredString("treeUri")))
                "readFile" -> result.success(readFile(call.requiredString("documentUri")))
                "startReadFile" -> result.success(startReadFile(call.requiredString("documentUri")))
                "readFileChunk" -> result.success(
                    readFileChunk(
                        call.requiredString("sessionId"),
                        call.argument<Int>("chunkSize") ?: DEFAULT_READ_CHUNK_SIZE,
                    )
                )
                "finishReadFile" -> {
                    finishReadFile(call.requiredString("sessionId"))
                    result.success(null)
                }
                "openFile" -> openFile(
                    call.requiredString("documentUri"),
                    call.requiredString("mimeType"),
                    result,
                )
                "startWriteFile" -> result.success(
                    startWriteFile(
                        call.requiredString("treeUri"),
                        call.requiredString("fileName"),
                        call.requiredString("mimeType"),
                    )
                )
                "writeFileChunk" -> {
                    writeFileChunk(call.requiredString("sessionId"), call.argument("bytes"))
                    result.success(null)
                }
                "finishWriteFile" -> {
                    finishWriteFile(call.requiredString("sessionId"))
                    result.success(null)
                }
                "abortWriteFile" -> {
                    abortWriteFile(call.requiredString("sessionId"))
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("netshare_saf_error", e.message, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != PICK_DIRECTORY_REQUEST) return false

        val result = pendingPickResult ?: return true
        pendingPickResult = null

        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            result.success(null)
            return true
        }

        val treeUri = data.data!!
        val takeFlags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        context?.contentResolver?.takePersistableUriPermission(treeUri, takeFlags)

        result.success(
            mapOf(
                "uri" to treeUri.toString(),
                "name" to getDisplayName(treeUri),
            )
        )
        return true
    }

    private fun pickDirectory(result: Result) {
        val currentActivity = activity
        if (currentActivity == null) {
            result.error("no_activity", "No Android activity is attached.", null)
            return
        }
        if (pendingPickResult != null) {
            result.error("pick_in_progress", "A directory picker is already open.", null)
            return
        }

        pendingPickResult = result
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        currentActivity.startActivityForResult(intent, PICK_DIRECTORY_REQUEST)
    }

    private fun hasPersistedPermission(treeUri: String): Boolean {
        val uri = Uri.parse(treeUri)
        return resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }

    private fun listFiles(treeUri: String): List<Map<String, Any?>> {
        val tree = Uri.parse(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        val output = mutableListOf<Map<String, Any?>>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.stringValue(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    ?: continue
                val name = cursor.stringValue(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    ?: continue
                val mimeType = cursor.stringValue(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    ?: "application/octet-stream"
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                output.add(
                    mapOf(
                        "uri" to documentUri.toString(),
                        "name" to name,
                        "mimeType" to mimeType,
                        "size" to cursor.longValue(DocumentsContract.Document.COLUMN_SIZE),
                        "lastModified" to cursor.longValue(
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        ),
                        "isDirectory" to (mimeType == DocumentsContract.Document.MIME_TYPE_DIR),
                    )
                )
            }
        }
        return output
    }

    private fun readFile(documentUri: String): ByteArray {
        val uri = Uri.parse(documentUri)
        return resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
    }

    private fun startReadFile(documentUri: String): Map<String, String> {
        val stream = resolver.openInputStream(Uri.parse(documentUri))
            ?: error("Could not open SAF input stream.")
        val sessionId = UUID.randomUUID().toString()
        readSessions[sessionId] = stream
        return mapOf("sessionId" to sessionId)
    }

    private fun readFileChunk(sessionId: String, chunkSize: Int): ByteArray {
        val stream = readSessions[sessionId] ?: error("Unknown SAF read session.")
        val buffer = ByteArray(chunkSize.coerceAtLeast(1))
        val bytesRead = stream.read(buffer)
        if (bytesRead <= 0) return ByteArray(0)
        return if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
    }

    private fun finishReadFile(sessionId: String) {
        readSessions.remove(sessionId)?.close()
    }

    private fun openFile(documentUri: String, mimeType: String, result: Result) {
        val currentActivity = activity
        if (currentActivity == null) {
            result.error("no_activity", "No Android activity is attached.", null)
            return
        }
        val uri = Uri.parse(documentUri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            currentActivity.startActivity(intent)
            result.success(null)
        } catch (e: ActivityNotFoundException) {
            result.error("no_viewer", "No app can open this file type.", null)
        }
    }

    private fun startWriteFile(
        treeUri: String,
        fileName: String,
        mimeType: String,
    ): Map<String, String> {
        val tree = Uri.parse(treeUri)
        findChildDocument(tree, fileName)?.let {
            DocumentsContract.deleteDocument(resolver, it)
        }

        val parentUri = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val documentUri = DocumentsContract.createDocument(
            resolver,
            parentUri,
            mimeType,
            fileName,
        ) ?: error("Could not create SAF document.")
        val stream = resolver.openOutputStream(documentUri, "w")
            ?: error("Could not open SAF output stream.")
        val sessionId = UUID.randomUUID().toString()
        writeSessions[sessionId] = stream
        return mapOf(
            "sessionId" to sessionId,
            "uri" to documentUri.toString(),
        )
    }

    private fun writeFileChunk(sessionId: String, bytes: ByteArray?) {
        val stream = writeSessions[sessionId] ?: error("Unknown SAF write session.")
        stream.write(bytes ?: ByteArray(0))
    }

    private fun finishWriteFile(sessionId: String) {
        val stream = writeSessions.remove(sessionId) ?: return
        stream.flush()
        stream.close()
    }

    private fun abortWriteFile(sessionId: String) {
        writeSessions.remove(sessionId)?.close()
    }

    private fun findChildDocument(tree: Uri, fileName: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.stringValue(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (name == fileName) {
                    val documentId = cursor.stringValue(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    ) ?: return null
                    return DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                }
            }
        }
        return null
    }

    private fun getDisplayName(treeUri: Uri): String {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        return resolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.stringValue(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            } else {
                null
            }
        } ?: "Selected folder"
    }

    private val resolver: ContentResolver
        get() = context?.contentResolver ?: error("No Android context is attached.")

    private fun MethodCall.requiredString(key: String): String {
        return argument<String>(key) ?: error("Missing required argument: $key")
    }

    private fun Cursor.stringValue(columnName: String): String? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return null
        return getString(index)
    }

    private fun Cursor.longValue(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return null
        return getLong(index)
    }

    companion object {
        private const val PICK_DIRECTORY_REQUEST = 5142
        private const val DEFAULT_READ_CHUNK_SIZE = 262144
    }
}
