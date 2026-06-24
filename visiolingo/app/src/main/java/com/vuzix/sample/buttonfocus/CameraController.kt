package com.vuzix.sample.buttonfocus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Capture d'images de la camera des lunettes via CameraX, SANS apercu a l'ecran. La camera
 * reste liee au cycle de vie de l'Activity ; [capture] prend une photo a la demande, la reduit
 * (cote max + JPEG) et renvoie les octets sur un thread de fond.
 */
internal class CameraController(context: Context, private val lifecycleOwner: LifecycleOwner) {

    fun interface FrameCallback {
        fun onFrame(jpeg: ByteArray)
    }

    private val context: Context = context.applicationContext
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile
    private var imageCapture: ImageCapture? = null

    /** Lie la camera (use case ImageCapture seul, pas de Preview) au cycle de vie. */
    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = ic
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, ic)
                Log.d(TAG, "Camera prete")
            } catch (e: Exception) {
                Log.e(TAG, "Echec init camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Prend une image maintenant ; le callback recoit le JPEG reduit (thread de fond). */
    fun capture(cb: FrameCallback) {
        val ic = imageCapture
        if (ic == null) {
            Log.d(TAG, "capture ignoree (camera pas prete)")
            return
        }
        ic.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val jpeg = toScaledJpeg(image)
                    if (jpeg != null) cb.onFrame(jpeg)
                } catch (e: Exception) {
                    Log.e(TAG, "Conversion image echouee", e)
                } finally {
                    image.close()
                }
            }

            override fun onError(e: ImageCaptureException) {
                Log.e(TAG, "takePicture erreur", e)
            }
        })
    }

    fun stop() {
        exec.shutdown() // la camera est deliee automatiquement avec le cycle de vie
    }

    companion object {
        private const val TAG = "VisioLingo"
        private const val MAX_SIDE = 768 // cote max de l'image envoyee a l'API
        private const val JPEG_QUALITY = 75

        private fun toScaledJpeg(image: ImageProxy): ByteArray? {
            val buffer = image.planes[0].buffer
            val raw = ByteArray(buffer.remaining())
            buffer.get(raw)
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null

            val rotation = image.imageInfo.rotationDegrees
            val w = bmp.width
            val h = bmp.height
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))

            var out = bmp
            if (scale < 1f || rotation != 0) {
                val m = Matrix()
                if (scale < 1f) m.postScale(scale, scale)
                if (rotation != 0) m.postRotate(rotation.toFloat())
                out = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
            }

            val baos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            if (out !== bmp) out.recycle()
            bmp.recycle()
            return baos.toByteArray()
        }
    }
}
