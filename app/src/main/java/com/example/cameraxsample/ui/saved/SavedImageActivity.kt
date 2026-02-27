package com.example.cameraxsample.ui.saved

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.cameraxsample.R
import com.example.cameraxsample.databinding.ActivitySavedImageBinding
import java.io.File

class SavedImageActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySavedImageBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySavedImageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        binding.btnBackToCamera.setOnClickListener { finish() }

        loadSavedImage()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            Glide.with(this).clear(binding.ivSavedImage)
        }
    }

    private fun loadSavedImage() {
        val savedUri = intent.getStringExtra(EXTRA_SAVED_URI)?.let(Uri::parse)
        val savedFilePath = intent.getStringExtra(EXTRA_SAVED_FILE_PATH)
        val source = when {
            savedUri != null -> savedUri
            !savedFilePath.isNullOrBlank() -> File(savedFilePath)
            else -> null
        }

        if (source == null) {
            onImageLoadFailed()
            return
        }

        Glide.with(this)
            .load(source)
            .fitCenter()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>?,
                    isFirstResource: Boolean,
                ): Boolean {
                    onImageLoadFailed()
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>?,
                    dataSource: com.bumptech.glide.load.DataSource?,
                    isFirstResource: Boolean,
                ): Boolean = false
            })
            .into(binding.ivSavedImage)
    }

    private fun onImageLoadFailed() {
        Toast.makeText(this, R.string.msg_saved_image_load_failed, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_SAVED_URI = "extra_saved_uri"
        const val EXTRA_SAVED_FILE_PATH = "extra_saved_file_path"
    }
}
