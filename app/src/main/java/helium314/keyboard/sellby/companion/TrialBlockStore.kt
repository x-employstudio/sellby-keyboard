// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.companion

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.tasks.Task
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** A second copy of the trial start time, kept in Google Play services' Block Store instead of the
 *  app's own storage. Block Store data belongs to Play services, so it is not wiped together with the
 *  app's files: it should survive uninstall/reinstall (and, when the user's Google backup is on, a
 *  move to a new phone), which helps keep the trial "once per device".
 *
 *  Best effort, on purpose: it needs Google Play services, and Google does not document what
 *  "Clear data" does to it. Every call is wrapped so a missing or failing Play services only means
 *  "no second copy", never a crash or a blocked user. Writes are fire-and-forget; only [read] is
 *  awaited, once, at app start. */
internal object TrialBlockStore {
    private const val TAG = "SellbyTrialBlockStore"
    private const val KEY = "sellby_trial_start_millis"

    /** The stored start time, or null when there is none or Block Store is unavailable. */
    suspend fun read(context: Context): Long? = try {
        val request = RetrieveBytesRequest.Builder().setKeys(listOf(KEY)).build()
        val response = Blockstore.getClient(context.applicationContext).retrieveBytes(request).awaitOrNull()
        val bytes = response?.blockstoreDataMap?.get(KEY)?.bytes
        if (bytes != null && bytes.size == Long.SIZE_BYTES) ByteBuffer.wrap(bytes).long.takeIf { it > 0L } else null
    } catch (e: Exception) {
        Log.d(TAG, "read failed: $e")
        null
    }

    /** Fire-and-forget, and never on the caller's thread: it is called from the main thread (the Trial
     *  button, the app-start path) and talking to Play services must not be able to stall it. */
    fun write(context: Context, startMillis: Long) {
        val app = context.applicationContext
        writeScope.launch {
            try {
                val data = StoreBytesData.Builder()
                    .setBytes(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(startMillis).array())
                    .setKey(KEY)
                    .setShouldBackupToCloud(true)
                    .build()
                Blockstore.getClient(app).storeBytes(data)
            } catch (e: Exception) {
                Log.d(TAG, "write failed: $e")
            }
        }
    }

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun <T : Any> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resume(null) }
        addOnCanceledListener { if (cont.isActive) cont.resume(null) }
    }
}
