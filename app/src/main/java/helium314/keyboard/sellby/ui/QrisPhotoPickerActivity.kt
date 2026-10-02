// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Transparent, UI-less proxy Activity: launches the system Photo Picker for a QRIS payment
 * method's QR-code image and reports the result back via local broadcast (no
 * startActivityForResult - IME context can't use it, same constraint EmojiSearchActivity works
 * around).
 *
 * Unlike EmojiSearchActivity (which broadcasts from onStop()), the result is broadcast the moment
 * the photo has been copied, not on a lifecycle callback. onStop() was the wrong hook here: it
 * fires as soon as an opaque picker covers this transparent Activity (long before anything is
 * picked - that early broadcast carried a null path and wiped the existing QRIS photo), and for
 * the real result it only fires after finish() has fully played out and the host app is back on
 * screen, which on some devices is a visible delay. A broadcast is only sent for a photo that was
 * really copied - cancelling the picker or a failed copy changes nothing.
 */
class QrisPhotoPickerActivity : ComponentActivity() {

    private var targetPaymentMethodId: String? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = targetPaymentMethodId
        if (uri == null || id == null) {
            finish()
            return@registerForActivityResult
        }
        // The picker's temporary read grant on the Uri lasts only while this Activity is alive, so
        // finish() waits until the copy is done - but the copy itself must not block the main
        // thread (full-resolution gallery photos can be several MB).
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val path = savePhoto(appContext, uri, id)
            if (path != null) {
                appContext.sendBroadcast(
                    Intent(QRIS_PHOTO_PICKED_ACTION).setPackage(appContext.packageName)
                        .putExtra(EXTRA_PAYMENT_METHOD_ID, id)
                        .putExtra(EXTRA_RESULT_PATH, path)
                )
            }
            withContext(Dispatchers.Main) {
                if (path == null) Toast.makeText(appContext, "Gagal menyimpan foto QRIS", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPaymentMethodId = intent.getStringExtra(EXTRA_PAYMENT_METHOD_ID)
        if (targetPaymentMethodId == null) {
            finish()
            return
        }
        // Only on a fresh start - after a recreate (e.g. rotation while the picker is up) the
        // registered launcher still delivers the pending result by itself, launching again here
        // would stack a second picker.
        if (savedInstanceState == null) {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    /** Copies the picked photo to filesDir/qris_photos/<id>.jpg (a temp file first, then renamed,
     *  so a reader never sees a half-written image) and returns the final path, or null on failure. */
    private fun savePhoto(context: Context, uri: Uri, paymentMethodId: String): String? {
        return try {
            val dir = File(context.filesDir, "qris_photos")
            if (!dir.exists() && !dir.mkdirs()) return null
            val temp = File(dir, "$paymentMethodId.tmp")
            val target = File(dir, "$paymentMethodId.jpg")
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { src -> temp.outputStream().use { dst -> src.copyTo(dst, COPY_BUFFER_BYTES) } }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target.absolutePath
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        const val QRIS_PHOTO_PICKED_ACTION = "helium314.keyboard.sellby.QRIS_PHOTO_PICKED"
        const val EXTRA_PAYMENT_METHOD_ID = "payment_method_id"
        const val EXTRA_RESULT_PATH = "result_path"
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
