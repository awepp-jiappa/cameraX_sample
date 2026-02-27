package com.example.cameraxsample.ui.preview

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.cameraxsample.R
import com.example.cameraxsample.databinding.ActivityPreviewBinding
import com.example.cameraxsample.storage.StorageModule
import java.io.File

class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding

    private var tempUri: Uri? = null
    private var tempFilePath: String? = null
    private var fileName: String = ""
    private var actionHandled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tempUri = intent.getStringExtra(EXTRA_TEMP_URI)?.let(Uri::parse)
        tempFilePath = intent.getStringExtra(EXTRA_TEMP_FILE_PATH)
        fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()

        if (tempUri == null && tempFilePath.isNullOrBlank()) {
            Log.e(TAG, "PreviewActivity launched without temp source")
            finish()
            return
        }

        setupListeners()
        loadPreviewImage()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            Glide.with(this).clear(binding.ivPreview)
        }
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener { onSaveClicked() }
        binding.btnDiscard.setOnClickListener { onDiscardClicked() }
        binding.btnClose.setOnClickListener { onDiscardClicked() }
    }

    private fun loadPreviewImage() {
        val source = tempUri ?: File(tempFilePath.orEmpty())
        Glide.with(this)
            .load(source)
            .fitCenter()
            .into(binding.ivPreview)
    }

    private fun onSaveClicked() {
        if (actionHandled) return
        actionHandled = true

        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = tempUri
            if (uri == null) {
                false
            } else {
                StorageModule.commitPendingCapture(this, uri)
            }
        } else {
            val path = tempFilePath
            val tempFile = if (path.isNullOrBlank()) null else File(path)
            if (tempFile == null || !tempFile.exists()) {
                false
            } else {
                StorageModule.saveLegacyTempCapture(this, tempFile, fileName) != null
            }
        }

        if (!saved) {
            actionHandled = false
            Toast.makeText(this, R.string.msg_photo_save_failed, Toast.LENGTH_SHORT).show()
            return
        }

        cleanupLegacyTempAfterSave()
        Toast.makeText(this, R.string.msg_save_completed, Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Save completed. uri=$tempUri path=$tempFilePath")
        finish()
    }

    private fun cleanupLegacyTempAfterSave() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return
        val path = tempFilePath ?: return
        val tempFile = File(path)
        if (!tempFile.exists()) return

        val deleted = tempFile.delete()
        Log.d(TAG, "Legacy temp cleanup after save path=$path deleted=$deleted")
    }

    private fun onDiscardClicked() {
        if (actionHandled) return
        actionHandled = true

        val discarded = StorageModule.discardTemporaryCapture(
            context = this,
            uri = tempUri,
            file = tempFilePath?.let(::File),
        )
        Log.d(TAG, "Discard clicked. discarded=$discarded uri=$tempUri path=$tempFilePath")
        finish()
    }

    companion object {
        private const val TAG = "PreviewActivity"

        const val EXTRA_TEMP_URI = "extra_temp_uri"
        const val EXTRA_TEMP_FILE_PATH = "extra_temp_file_path"
        const val EXTRA_FILE_NAME = "extra_file_name"
    }
}
