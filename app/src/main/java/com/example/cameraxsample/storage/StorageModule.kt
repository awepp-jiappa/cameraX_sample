package com.example.cameraxsample.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
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

    data class CaptureTempRequest(
        val outputOptions: ImageCapture.OutputFileOptions,
        val tempUri: Uri? = null,
        val tempFile: File? = null,
        val fileName: String,
    )

    fun createTemporaryCaptureRequest(context: Context): CaptureTempRequest {
        val fileName = createPhotoFileName()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DOWNLOAD_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val tempUri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues,
            ) ?: throw IllegalStateException("Failed to create pending MediaStore item")

            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                tempUri,
                ContentValues(),
            ).build()

            CaptureTempRequest(
                outputOptions = outputOptions,
                tempUri = tempUri,
                tempFile = null,
                fileName = fileName,
            )
        } else {
            val tempDirectory = File(context.cacheDir, "capture_temp")
            if (!tempDirectory.exists()) {
                tempDirectory.mkdirs()
            }

            val tempFile = File(tempDirectory, fileName)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

            CaptureTempRequest(
                outputOptions = outputOptions,
                tempUri = Uri.fromFile(tempFile),
                tempFile = tempFile,
                fileName = fileName,
            )
        }
    }

    fun commitPendingCapture(context: Context, pendingUri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val updated = context.contentResolver.update(pendingUri, values, null, null)
            Log.d(TAG, "commitPendingCapture uri=$pendingUri updated=$updated")
            updated > 0
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to commit pending capture: uri=$pendingUri", exception)
            false
        }
    }

    fun saveLegacyTempCapture(context: Context, tempFile: File, fileName: String): File? {
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

    fun discardTemporaryCapture(context: Context, uri: Uri? = null, file: File? = null): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null -> {
                try {
                    val deleted = context.contentResolver.delete(uri, null, null)
                    Log.d(TAG, "Discarded pending MediaStore item uri=$uri deleted=$deleted")
                    deleted > 0
                } catch (exception: Exception) {
                    Log.e(TAG, "Failed to delete pending MediaStore item uri=$uri", exception)
                    false
                }
            }

            file != null -> {
                val deleted = file.delete()
                Log.d(TAG, "Discarded legacy temp file path=${file.absolutePath} deleted=$deleted")
                deleted
            }

            else -> false
        }
    }

    fun cleanupPendingCaptures(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH}=? AND ${MediaStore.Images.Media.IS_PENDING}=1"
        val selectionArgs = arrayOf("$RELATIVE_DOWNLOAD_PATH/")

        try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val deleted = context.contentResolver.delete(uri, null, null)
                    Log.d(TAG, "cleanupPendingCaptures deleted uri=$uri result=$deleted")
                }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to cleanup pending captures", exception)
        }
    }

    fun scanLegacyFile(context: Context, file: File) {
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

    private fun createPhotoFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = formatter.format(Date())
        return "IMG_${timestamp}.jpg"
    }
}
