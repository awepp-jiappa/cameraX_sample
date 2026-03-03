package com.example.cameraxsample.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StorageModule {

    private const val TAG = "StorageModule"
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
        val savedUri = MediaStoreImageSaver.saveImage(
            context = context,
            sourceFile = tempFile,
            displayName = fileName,
        )

        return savedUri?.let {
            if (it.scheme == "file") {
                SavedCaptureResult(savedFilePath = File(it.path.orEmpty()).absolutePath)
            } else {
                SavedCaptureResult(savedUri = it)
            }
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

}
