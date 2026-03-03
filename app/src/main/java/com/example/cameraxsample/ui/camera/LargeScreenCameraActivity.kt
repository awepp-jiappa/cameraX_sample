package com.example.cameraxsample.ui.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.example.cameraxsample.R
import com.example.cameraxsample.databinding.ActivityLargeScreenCameraBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LargeScreenCameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LargeScreenCamera"
    }

    private lateinit var binding: ActivityLargeScreenCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var pendingTempFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLargeScreenCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!hasCameraPermission() || (requiresLegacyWritePermission() && !hasLegacyWritePermission())) {
            Toast.makeText(this, R.string.required_permissions_needed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        setupUi()
        startCamera()
    }

    private fun setupUi() {
        binding.btnClose.setOnClickListener { finish() }
        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnSave.setOnClickListener { onSavePreview() }
        binding.btnDiscard.setOnClickListener { onDiscardPreview() }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder()
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()
                .also { it.surfaceProvider = binding.viewFinder.surfaceProvider }
            imageCapture = ImageCapture.Builder()
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                )
            } catch (_: Exception) {
                Toast.makeText(this, R.string.msg_camera_init_failed, Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }


    private fun takePhoto() {
        captureToPreview()
    }

    private fun captureToPreview() {
        val capture = imageCapture ?: return
        binding.btnShutter.isEnabled = false

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bitmap = imageProxyToBitmap(image)
                        ?: throw IOException("Failed to decode image")
                    val tempFile = writeBitmapToTempFile(bitmap)
                    bitmap.recycle()

                    runOnUiThread {
                        binding.btnShutter.isEnabled = true
                        replacePendingTempFile(tempFile)
                        showPreviewMode()
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        binding.btnShutter.isEnabled = true
                        Toast.makeText(this@LargeScreenCameraActivity, R.string.msg_photo_save_failed, Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    binding.btnShutter.isEnabled = true
                    Toast.makeText(this@LargeScreenCameraActivity, R.string.msg_photo_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val bitmap = when (image.format) {
            ImageFormat.JPEG -> jpegImageToBitmap(image)
            ImageFormat.YUV_420_888 -> yuvImageToBitmap(image)
            else -> null
        } ?: return null

        val rotation = image.imageInfo.rotationDegrees
        Log.d(TAG, "Captured image rotationDegrees=$rotation")
        if (rotation == 0) return bitmap

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    private fun jpegImageToBitmap(image: ImageProxy): Bitmap? {
        val buffer = image.planes.firstOrNull()?.buffer ?: return null
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun yuvImageToBitmap(image: ImageProxy): Bitmap? {
        val nv21 = yuv420888ToNv21(image) ?: return null
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        if (!yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 95, out)) {
            return null
        }
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray? {
        if (image.planes.size < 3) return null

        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        val yPlane = image.planes.getOrNull(0) ?: return null
        val uPlane = image.planes.getOrNull(1) ?: return null
        val vPlane = image.planes.getOrNull(2) ?: return null

        copyPlane(yPlane.buffer, width, height, yPlane.rowStride, yPlane.pixelStride, nv21, 0, 1)
        copyPlane(vPlane.buffer, width / 2, height / 2, vPlane.rowStride, vPlane.pixelStride, nv21, ySize, 2)
        copyPlane(uPlane.buffer, width / 2, height / 2, uPlane.rowStride, uPlane.pixelStride, nv21, ySize + 1, 2)

        return nv21
    }

    private fun copyPlane(
        source: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        output: ByteArray,
        offset: Int,
        outputStride: Int,
    ) {
        val planeBuffer = source.duplicate()
        var outputIndex = offset
        val rowData = ByteArray(rowStride)

        for (row in 0 until height) {
            val rowStart = row * rowStride
            planeBuffer.position(rowStart)
            if (pixelStride == 1 && outputStride == 1) {
                planeBuffer.get(output, outputIndex, width)
                outputIndex += width
            } else {
                planeBuffer.get(rowData, 0, rowStride)
                var col = 0
                while (col < width) {
                    output[outputIndex] = rowData[col * pixelStride]
                    outputIndex += outputStride
                    col++
                }
            }
        }
    }

    private fun writeBitmapToTempFile(bitmap: Bitmap): File {
        val tempFile = File(cacheDir, "capture_tmp_${System.currentTimeMillis()}.jpg")
        FileOutputStream(tempFile).use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                throw IOException("Failed to write temp bitmap")
            }
        }
        return tempFile
    }

    private fun showPreviewMode() {
        val previewSource = pendingTempFile ?: run {
            Toast.makeText(this, R.string.msg_photo_save_failed, Toast.LENGTH_SHORT).show()
            return
        }

        Glide.with(this)
            .load(previewSource)
            .fitCenter()
            .into(binding.ivPreview)

        binding.ivPreview.visibility = android.view.View.VISIBLE
        binding.previewActions.visibility = android.view.View.VISIBLE
        binding.btnShutter.visibility = android.view.View.GONE
    }

    private fun onSavePreview() {
        val tempFile = pendingTempFile ?: return
        binding.btnSave.isEnabled = false
        saveFinalImage(tempFile)
    }

    private fun saveFinalImage(tempFile: File) {
        val saveCallback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                runOnUiThread {
                    binding.btnSave.isEnabled = true
                    deleteTempFile(tempFile)
                    Toast.makeText(this@LargeScreenCameraActivity, R.string.msg_save_completed, Toast.LENGTH_SHORT).show()
                    clearPreviewMode()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    binding.btnSave.isEnabled = true
                    Toast.makeText(this@LargeScreenCameraActivity, R.string.msg_photo_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        cameraExecutor.execute {
            if (!tempFile.exists()) {
                saveCallback.onError(
                    ImageCaptureException(
                        ImageCapture.ERROR_FILE_IO,
                        "Temporary file is missing",
                        null,
                    ),
                )
                return@execute
            }

            val savedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveTempFileToMediaStore(tempFile)
            } else {
                saveTempFileToLegacyDownloads(tempFile)
            }

            if (savedUri != null) {
                saveCallback.onImageSaved(ImageCapture.OutputFileResults(savedUri))
            } else {
                saveCallback.onError(
                    ImageCaptureException(
                        ImageCapture.ERROR_FILE_IO,
                        "Failed to save image",
                        null,
                    ),
                )
            }
        }
    }

    private fun saveTempFileToMediaStore(tempFile: File): android.net.Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, createPhotoFileName())
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/codex_app/cameraX")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null

        return try {
            contentResolver.openOutputStream(uri)?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Failed to open output stream")
            uri
        } catch (_: Exception) {
            contentResolver.delete(uri, null, null)
            null
        }
    }

    private fun saveTempFileToLegacyDownloads(tempFile: File): android.net.Uri? {
        if (!hasLegacyWritePermission()) {
            runOnUiThread {
                Toast.makeText(this, R.string.required_permissions_needed, Toast.LENGTH_SHORT).show()
            }
            return null
        }

        val destination = createLegacyOutputFile()
        return try {
            tempFile.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            MediaScannerConnection.scanFile(
                this,
                arrayOf(destination.absolutePath),
                arrayOf("image/jpeg"),
                null,
            )
            android.net.Uri.fromFile(destination)
        } catch (_: Exception) {
            null
        }
    }

    private fun onDiscardPreview() {
        pendingTempFile?.let { deleteTempFile(it) }
        Toast.makeText(this, R.string.msg_delete_completed, Toast.LENGTH_SHORT).show()
        clearPreviewMode()
    }

    private fun clearPreviewMode() {
        Glide.with(this).clear(binding.ivPreview)
        binding.ivPreview.setImageDrawable(null)
        binding.ivPreview.visibility = View.GONE
        binding.previewActions.visibility = View.GONE
        binding.btnShutter.visibility = View.VISIBLE
        binding.btnShutter.isEnabled = true
        binding.btnSave.isEnabled = true

        pendingTempFile = null
    }

    private fun replacePendingTempFile(newFile: File) {
        pendingTempFile?.let { oldFile ->
            if (oldFile.absolutePath != newFile.absolutePath) {
                deleteTempFile(oldFile)
            }
        }
        pendingTempFile = newFile
    }

    private fun deleteTempFile(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }

    private fun createLegacyOutputFile(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDirectory = File(downloadsDir, "codex_app/cameraX")
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }
        return File(targetDirectory, createPhotoFileName())
    }

    private fun createPhotoFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "IMG_${formatter.format(Date())}.jpg"
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLegacyWritePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiresLegacyWritePermission(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

    override fun onDestroy() {
        imageCapture = null
        cameraProvider?.unbindAll()
        pendingTempFile?.let { deleteTempFile(it) }
        pendingTempFile = null
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        super.onDestroy()
    }
}
