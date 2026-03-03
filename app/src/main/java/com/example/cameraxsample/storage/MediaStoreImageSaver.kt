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
import java.io.FileOutputStream

object MediaStoreImageSaver {

    private const val TAG = "MediaStoreImageSaver"
    private const val RELATIVE_PICTURES_PATH = "Pictures/codex_app/cameraX"
    private const val LEGACY_SUB_DIRECTORY = "codex_app/cameraX"

    fun saveImage(
        context: Context,
        jpegBytes: ByteArray,
        displayName: String,
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context, jpegBytes, displayName)
        } else {
            saveLegacyImage(context, jpegBytes, displayName)
        }
    }

    private fun saveToMediaStore(context: Context, jpegBytes: ByteArray, displayName: String): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PICTURES_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to insert image into MediaStore", exception)
            null
        }

        if (uri == null) {
            Log.e(TAG, "MediaStore insert returned null uri for displayName=$displayName")
            return null
        }

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(jpegBytes)
            } ?: throw IllegalStateException("Output stream is null")


            val pendingValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, pendingValues, null, null)
            Log.d(TAG, "saveImage success uri=$uri")
            uri
        } catch (exception: Exception) {
            Log.e(TAG, "Failed while writing MediaStore image uri=$uri", exception)
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun saveLegacyImage(context: Context, jpegBytes: ByteArray, displayName: String): Uri? {
        return try {
            val picturesDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val targetDirectory = File(picturesDirectory, LEGACY_SUB_DIRECTORY)
            if (!targetDirectory.exists()) {
                targetDirectory.mkdirs()
            }

            val destination = File(targetDirectory, displayName)
            FileOutputStream(destination).use { output ->
                output.write(jpegBytes)
            }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(destination.absolutePath),
                arrayOf("image/jpeg"),
                null,
            )

            val uri = Uri.fromFile(destination)
            Log.d(TAG, "saveImage legacy success uri=$uri")
            uri
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to save legacy image displayName=$displayName", exception)
            null
        }
    }
}
