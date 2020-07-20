package me.rosuh.easywatermark

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_main.*
import java.io.FileOutputStream
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt


class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.my_toolbar))
        iv_photo.apply {
            setOnClickListener {
                performFileSearch()
            }
        }
        sb_horizon.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                iv_photo.adjustHorizon(progress.toFloat() / 100)
            }
        })

        sb_vertical.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                iv_photo.adjustVertical(progress.toFloat() / 100)
            }
        })

        sb_alpha.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                iv_photo.textAlpha(progress.toFloat() / 100)
            }

        })


        sb_rotate.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                iv_photo.textRotate(progress.toFloat())
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_settings -> {
            true
        }

        R.id.action_save -> {
            saveImage()
            true
        }

        R.id.action_share -> {
            shareImage()
            true
        }

        else -> {
            // If we got here, the user's action was not recognized.
            // Invoke the superclass to handle it.
            super.onOptionsItemSelected(item)
        }
    }

    private fun generateImage(): Uri? {
        if (imageBitmap == null || imageBitmap!!.isRecycled) {
            return null
        }
        val mutableBitmap = imageBitmap!!.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap!!)
        val config = iv_photo.config
        val paint = Paint().apply {
            textSize = config.textSize
            color = config.textColor
            alpha = config.alpha
            isAntiAlias = true
            style = Paint.Style.STROKE
            isDither = true
        }
        val bounds = Rect()
        canvas.save()
        canvas.rotate(
            config.degree.toFloat(),
            (mutableBitmap.width / 2).toFloat(),
            (mutableBitmap.height / 2).toFloat()
        )
        paint.getTextBounds(config.text, 0, config.text.length, bounds);
        val textWidth = bounds.width().toFloat()
        val textHeight = bounds.height().toFloat()
        val horizonCount = (getMaxSize() / (textWidth + config.horizonGap)).roundToInt()
        val verticalCount = (getMaxSize() / (textHeight + config.verticalGap)).roundToInt()
        for (iX in 0..horizonCount) {
            for (iY in 0..verticalCount) {
                canvas.drawText(
                    config.text,
                    iX * (textWidth + (if (iX == 0) 0 else config.horizonGap)),
                    iY * (textHeight + (if (iY == 0) 0 else config.verticalGap)),
                    paint
                )
            }

        }
        canvas.restore()

        val imageCollection =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val imageDetail = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "Easy_water_mark_${System.currentTimeMillis()}.jpg"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val imageContentUri = contentResolver.insert(imageCollection, imageDetail)
        contentResolver.openFileDescriptor(imageContentUri!!, "w", null).use { pfd ->
            mutableBitmap.compress(
                Bitmap.CompressFormat.PNG,
                100,
                FileOutputStream(pfd!!.fileDescriptor)
            )
        }
        imageDetail.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            imageDetail.put(MediaStore.Images.Media.IS_PENDING, 0)
        }
        contentResolver.update(imageContentUri, imageDetail, null, null)

        return imageContentUri
    }

    private fun getMaxSize(): Float {
        return sqrt(imageBitmap!!.width.toFloat().pow(2) + imageBitmap!!.height.toFloat().pow(2))
    }

    private fun saveImage() {
        if (generateImage() == null) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
    }

    private fun shareImage() {
        val uri = generateImage()
        if (uri == null) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "image/jpeg"
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        startActivity(Intent.createChooser(intent, "Share Image"))
    }

    private var imageBitmap: Bitmap? = null
    private val READ_REQUEST_CODE: Int = 42

    /**
     * Fires an intent to spin up the "file chooser" UI and select an image.
     */
    private fun performFileSearch() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, READ_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.also { uri ->
                Log.i("onActivityResult", "Uri: $uri")
                resetBitmap()
                Thread {
                    imageBitmap = contentResolver.openInputStream(uri).use {
                        BitmapFactory.decodeStream(it)
                    }
                    runOnUiThread {
                        iv_photo.apply {
                            tag = uri
                            setImageURI(uri)
                            waterText = "此照片仅供测试，不得用于其他用途"
                        }
                    }
                }.start()
            }
        }
    }

    private fun resetBitmap() {
        if (imageBitmap?.isRecycled != true) {
            imageBitmap?.recycle()
        }
        imageBitmap = null
    }

    override fun onDestroy() {
        super.onDestroy()
        resetBitmap()
    }
}