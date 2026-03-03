package com.example.cameraxsample.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Environment
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
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
    private var pendingNormalizedBitmap: Bitmap? = null
    private lateinit var debugOverlay: TextView

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
        setupDebugOverlay()
        binding.btnClose.setOnClickListener { finish() }
        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnSave.setOnClickListener { onSavePreview() }
        binding.btnDiscard.setOnClickListener { onDiscardPreview() }
    }

    private fun setupDebugOverlay() {
        debugOverlay = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0x66000000)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            textSize = 12f
            text = "rotationDegrees=-\nnormalizedDeg=-"
        }

        val params = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            marginStart = dp(12)
            bottomMargin = dp(12)
            horizontalBias = 0f
        }

        debugOverlay.gravity = Gravity.START
        binding.root.addView(debugOverlay, params)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            try {
                bindCameraUseCases()
                logWindowMetrics("startCamera")
            } catch (_: Exception) {
                Toast.makeText(this, R.string.msg_camera_init_failed, Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.INITIALIZED) || isDestroyed) {
            return
        }

        val preview = Preview.Builder()
            .build()
            .also {
                it.targetRotation = android.view.Surface.ROTATION_0
                it.surfaceProvider = binding.viewFinder.surfaceProvider
            }
        imageCapture = ImageCapture.Builder()
            .build()
            .also { it.targetRotation = android.view.Surface.ROTATION_0 }

        provider.unbindAll()
        provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture,
        )
    }

    private fun rebindCameraSafely() {
        try {
            cameraProvider?.unbindAll()
            bindCameraUseCases()
            logWindowMetrics("rebindCameraSafely")
        } catch (e: Exception) {
            Log.e(TAG, "Camera rebind failed", e)
        }
    }

    private fun logWindowMetrics(source: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Log.d(TAG, "$source window=${bounds.width()}x${bounds.height()}")
            return
        }

        val metrics = resources.displayMetrics
        Log.d(TAG, "$source window=${metrics.widthPixels}x${metrics.heightPixels}")
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        rebindCameraSafely()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebindCameraSafely()
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
                    val rotationDegrees = image.imageInfo.rotationDegrees
                    Log.d(TAG, "rotationDegrees=$rotationDegrees")
                    val normalizedDeg = (360 - rotationDegrees) % 360
                    Log.d(TAG, "normalizedDeg=$normalizedDeg")
                    val rawBitmap = imageProxyToBitmap(image)
                        ?: throw IOException("Failed to decode image")
                    val normalizedBitmap = rawBitmap.rotateClockwise(normalizedDeg)
                    if (normalizedBitmap != rawBitmap) {
                        rawBitmap.recycle()
                    }
                    Log.d(TAG, "normalizedBitmap size=${normalizedBitmap.width}x${normalizedBitmap.height}")

                    runOnUiThread {
                        updateDebugOverlay(rotationDegrees, normalizedDeg)
                        binding.btnShutter.isEnabled = true
                        replacePendingBitmap(normalizedBitmap)
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
        return when (image.format) {
            ImageFormat.JPEG -> jpegImageToBitmap(image)
            ImageFormat.YUV_420_888 -> yuvImageToBitmap(image)
            else -> null
        }
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

    private fun showPreviewMode() {
        val previewBitmap = pendingNormalizedBitmap ?: run {
            Toast.makeText(this, R.string.msg_photo_save_failed, Toast.LENGTH_SHORT).show()
            return
        }

        Glide.with(this)
            .load(previewBitmap)
            .fitCenter()
            .into(binding.ivPreview)

        binding.ivPreview.visibility = android.view.View.VISIBLE
        binding.previewActions.visibility = android.view.View.VISIBLE
        binding.btnShutter.visibility = android.view.View.GONE
    }

    private fun onSavePreview() {
        val normalizedBitmap = pendingNormalizedBitmap ?: return
        binding.btnSave.isEnabled = false
        saveFinalImage(normalizedBitmap)
    }

    private fun saveFinalImage(normalizedBitmap: Bitmap) {
        val saveCallback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                runOnUiThread {
                    binding.btnSave.isEnabled = true
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
            val savedUri = saveNormalizedBitmapToMediaStore(normalizedBitmap, createPhotoFileName())
            Log.d(TAG, "saveFinalImage result uri=$savedUri")

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

    private fun saveNormalizedBitmapToMediaStore(bitmap: Bitmap, displayName: String): android.net.Uri? {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            return saveNormalizedBitmapForLegacy(bitmap, displayName)
        }

        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/codex_app/cameraX")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        return try {
            val wroteBitmap = resolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            } ?: false

            if (!wroteBitmap) {
                throw IOException("Failed to encode normalized bitmap")
            }

            val pendingValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, pendingValues, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save normalized bitmap uri=$uri", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun saveNormalizedBitmapForLegacy(bitmap: Bitmap, displayName: String): android.net.Uri? {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val outputDir = File(picturesDir, "codex_app/cameraX")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            return null
        }

        val outputFile = File(outputDir, displayName)
        return try {
            val wroteBitmap = FileOutputStream(outputFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            }
            if (!wroteBitmap) {
                throw IOException("Failed to encode legacy normalized bitmap")
            }

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DATA, outputFile.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            }
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: android.net.Uri.fromFile(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed legacy bitmap save file=${outputFile.absolutePath}", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            null
        }
    }

    private fun onDiscardPreview() {
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

        pendingNormalizedBitmap?.recycle()
        pendingNormalizedBitmap = null
    }

    private fun replacePendingBitmap(newBitmap: Bitmap) {
        pendingNormalizedBitmap?.recycle()
        pendingNormalizedBitmap = newBitmap
    }

    private fun updateDebugOverlay(rotationDegrees: Int, normalizedDeg: Int) {
        debugOverlay.text = "rotationDegrees=$rotationDegrees\nnormalizedDeg=$normalizedDeg"
    }

    private fun Bitmap.rotateClockwise(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply {
            postRotate(degrees.toFloat())
        }
        return Bitmap.createBitmap(
            this,
            0,
            0,
            width,
            height,
            matrix,
            true,
        )
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
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
        pendingNormalizedBitmap?.recycle()
        pendingNormalizedBitmap = null
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        super.onDestroy()
    }
}
