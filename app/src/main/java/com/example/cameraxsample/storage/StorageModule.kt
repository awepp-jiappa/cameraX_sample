package com.example.cameraxsample.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StorageModule {

    private const val TAG = "StorageModule"
    private const val RELATIVE_DOWNLOAD_PATH = "Download/codex_app/cameraX"
    private const val LEGACY_SUB_DIRECTORY = "codex_app/cameraX"
    private const val CACHE_SUB_DIRECTORY = "capture_temp"

    data class SavedCaptureResult(
        val savedUri: Uri? = null,
        val savedFilePath: String? = null,
    )

    fun createPhotoFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = formatter.format(Date())
        return "IMG_${timestamp}.jpg"
    }

    fun createTemporaryCaptureFile(context: Context, fileName: String): File {
        val tempDirectory = File(context.cacheDir, CACHE_SUB_DIRECTORY)
        if (!tempDirectory.exists()) {
            tempDirectory.mkdirs()
        }
        return File(tempDirectory, fileName)
    }

    fun saveCapturedPhoto(context: Context, tempFile: File, fileName: String): SavedCaptureResult? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context, tempFile, fileName)?.let { SavedCaptureResult(savedUri = it) }
        } else {
            saveLegacyTempCapture(context, tempFile, fileName)
                ?.let { SavedCaptureResult(savedFilePath = it.absolutePath) }
        }
    }

    fun deleteTempCapture(file: File?): Boolean {
        if (file == null || !file.exists()) return false
        return try {
            val deleted = file.delete()
            Log.d(TAG, "deleteTempCapture path=${file.absolutePath} deleted=$deleted")
            deleted
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to delete temp capture path=${file.absolutePath}", exception)
            false
        }
    }

    fun cleanupTemporaryCaptures(context: Context) {
        val tempDirectory = File(context.cacheDir, CACHE_SUB_DIRECTORY)
        if (!tempDirectory.exists() || !tempDirectory.isDirectory) return

        tempDirectory.listFiles()?.forEach { file ->
            if (file.isFile) {
                val deleted = file.delete()
                Log.d(TAG, "cleanupTemporaryCaptures path=${file.absolutePath} deleted=$deleted")
            }
        }
    }

    private fun saveToMediaStore(context: Context, sourceFile: File, fileName: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DOWNLOAD_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val itemUri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to insert MediaStore item", exception)
            null
        } ?: return null

        return try {
            resolver.openOutputStream(itemUri)?.use { output ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Output stream is null")

            val pendingValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(itemUri, pendingValues, null, null)
            Log.d(TAG, "saveToMediaStore uri=$itemUri")
            itemUri
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to save to MediaStore uri=$itemUri", exception)
            resolver.delete(itemUri, null, null)
            null
        }
    }

    private fun saveLegacyTempCapture(context: Context, tempFile: File, fileName: String): File? {
        return try {
            val downloadsDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDirectory = File(downloadsDirectory, LEGACY_SUB_DIRECTORY)
            if (!targetDirectory.exists()) {
                targetDirectory.mkdirs()
            }

            val destination = File(targetDirectory, fileName)
            copyFile(tempFile, destination)
            scanLegacyFile(context, destination)
            Log.d(TAG, "saveLegacyTempCapture destination=${destination.absolutePath}")
            destination
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to save legacy temp capture from=${tempFile.absolutePath}", exception)
            null
        }
    }

    private fun scanLegacyFile(context: Context, file: File) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("image/jpeg"),
            null,
        )
    }

    private fun copyFile(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }
}
