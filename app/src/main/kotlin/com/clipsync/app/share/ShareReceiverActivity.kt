package com.clipsync.app.share

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.clipsync.app.data.Repository
import com.clipsync.app.network.NetworkErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val TAG = "ClipSync"

/** Input cap: a share from another app is untrusted data, don't buffer unbounded amounts of it. */
private const val MAX_INPUT_BYTES = 40 * 1024 * 1024

/** Longest side after decoding; larger images are subsampled instead of exhausting the heap. */
private const val MAX_IMAGE_SIDE = 4096

/** What we are willing to send; the receiving side rejects bodies above ~24 MB of base64. */
private const val MAX_PNG_BYTES = 12 * 1024 * 1024

/**
 * Target for Android's native "Share" action from any app. This - not
 * background clipboard watching, which the OS does not allow past
 * Android 10 - is the main way of sending content TO the PC.
 *
 * This activity is exported (any app can send it a share intent), so what it
 * receives is treated as untrusted: only content:// URIs are opened (never
 * file:// - a hostile app could point that at this app's own private files),
 * reads are size-capped, and decoding happens off the main thread.
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = Repository(applicationContext)

        lifecycleScope.launch {
            try {
                when {
                    intent?.type == "text/plain" -> handleText(repository)
                    intent?.type?.startsWith("image/") == true -> handleImage(repository)
                    else -> toast("Этот тип содержимого не поддерживается")
                }
            } catch (e: Exception) {
                Log.w(TAG, "share failed", e)
                toast("Не удалось отправить: ${NetworkErrors.explain(e)}")
            } finally {
                finish()
            }
        }
    }

    private suspend fun handleText(repository: Repository) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            toast("Пустой текст")
            return
        }
        repository.sendToPc("text", text.toByteArray(Charsets.UTF_8))
        toast("Отправлено на ПК")
    }

    private suspend fun handleImage(repository: Repository) {
        val uri = extraStreamUri()
        if (uri == null || uri.scheme != ContentResolver.SCHEME_CONTENT) {
            toast("Не удалось прочитать изображение")
            return
        }

        val prepared = withContext(Dispatchers.IO) {
            val bytes = readLimited(uri, MAX_INPUT_BYTES) ?: return@withContext null
            val bitmap = decodeSampled(bytes) ?: return@withContext null
            val png = ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
            Triple(png, bitmap.width, bitmap.height)
        }
        if (prepared == null) {
            toast("Не удалось прочитать изображение (повреждено или слишком большое)")
            return
        }
        val (png, width, height) = prepared
        if (png.size > MAX_PNG_BYTES) {
            toast("Изображение слишком большое для отправки")
            return
        }
        repository.sendToPc("image", png, width, height)
        toast("Отправлено на ПК")
    }

    /** Reads at most [limit] bytes; null if the stream is missing or larger than that. */
    private fun readLimited(uri: Uri, limit: Int): ByteArray? {
        contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                total += n
                if (total > limit) return null
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
        return null
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_SIDE || bounds.outHeight / sample > MAX_IMAGE_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun extraStreamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
