package com.localvision.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * 运行时生成的合成图片。
 *
 * 仓库不提交任何照片：既避免私人数据，也让 fixture 随时可复现，
 * 不依赖外部素材授权。全部写进应用缓存目录，用完即删。
 */
class ImageFixtures(private val context: Context) {

    private val created = mutableListOf<File>()
    private var sequence = 0

    /** 固定方向的普通图片，只有几何色块，不含任何人脸。 */
    fun plainImage(width: Int = 640, height: Int = 480): Uri =
        newFile("plain", width, height) { canvas, paint ->
            canvas.drawColor(Color.rgb(214, 222, 230))
            paint.color = Color.rgb(90, 140, 160)
            canvas.drawRect(width * 0.05f, height * 0.05f, width * 0.40f, height * 0.50f, paint)
            paint.color = Color.rgb(200, 160, 90)
            canvas.drawCircle(width * 0.70f, height * 0.30f, height * 0.18f, paint)
        }

    /** 带 EXIF 旋转标记的图片：解码路径必须照样成功，不能当成损坏文件。 */
    fun exifRotatedImage(
        orientation: Int = ExifInterface.ORIENTATION_ROTATE_90,
    ): Uri = newFile("exif", 480, 360) { canvas, paint ->
        canvas.drawColor(Color.rgb(120, 150, 120))
        paint.color = Color.rgb(30, 30, 30)
        canvas.drawRect(0f, 0f, 160f, 160f, paint)
        paint.color = Color.rgb(250, 250, 250)
        canvas.drawCircle(240f, 200f, 60f, paint)
    }.also { uri -> writeOrientation(uri, orientation) }

    /** 字节损坏的图片：用于验证图片错误路径。 */
    fun corruptImage(): Uri {
        val file = File(context.cacheDir, "fixture-corrupt-${sequence++}.jpg")
        file.writeBytes(ByteArray(1024) { index -> ((index * 31 + 7) % 251).toByte() })
        created += file
        return Uri.fromFile(file)
    }

    fun clear() {
        created.forEach { it.delete() }
        created.clear()
    }

    private fun newFile(name: String, width: Int, height: Int, draw: (Canvas, Paint) -> Unit): Uri {
        val file = File(context.cacheDir, "fixture-$name-${sequence++}.jpg")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas, Paint(Paint.ANTI_ALIAS_FLAG))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        created += file
        return Uri.fromFile(file)
    }

    private fun writeOrientation(uri: Uri, orientation: Int) {
        ExifInterface(File(requireNotNull(uri.path))).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
    }
}