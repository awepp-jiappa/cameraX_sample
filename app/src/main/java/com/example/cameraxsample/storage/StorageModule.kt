package com.example.cameraxsample.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StorageModule {

    private const val RELATIVE_DOWNLOAD_PATH = "Download/codex_app/cameraX"

    data class CaptureSaveRequest(
        val outputOptions: ImageCapture.OutputFileOptions,
        val userVisiblePath: String,
        val legacyFile: File? = null,
        val legacyUri: Uri? = null,
    )

    fun createCaptureSaveRequest(context: Context): CaptureSaveRequest {
        val fileName = createPhotoFileName()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DOWNLOAD_PATH)
            }

            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues,
            ).build()

            CaptureSaveRequest(
                outputOptions = outputOptions,
                userVisiblePath = "$RELATIVE_DOWNLOAD_PATH/$fileName",
            )
        } else {
            val downloadsDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDirectory = File(downloadsDirectory, "codex_app/cameraX")
            if (!targetDirectory.exists()) {
                targetDirectory.mkdirs()
            }

            val photoFile = File(targetDirectory, fileName)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            CaptureSaveRequest(
                outputOptions = outputOptions,
                userVisiblePath = photoFile.absolutePath,
                legacyFile = photoFile,
                legacyUri = Uri.fromFile(photoFile),
            )
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

    private fun createPhotoFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = formatter.format(Date())
        return "IMG_${timestamp}.jpg"
    }
}
