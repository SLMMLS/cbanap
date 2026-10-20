package com.clipsync.app.data

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import com.clipsync.app.crypto.CryptoUtils
import com.clipsync.app.crypto.Identity
import com.clipsync.app.network.NetworkErrors
import com.clipsync.app.network.PairingQrPayload
import com.clipsync.app.network.PushPayload
import com.clipsync.app.network.SyncClient
import com.clipsync.app.service.ReceiverService
import com.clipsync.app.service.SYNC_PORT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

data class DecryptedEntry(
    val id: String,
    val entryType: String,
    val content: ByteArray, // UTF-8 text bytes, or PNG bytes for images
    val createdAt: Long,
    val pinned: Boolean,
)

class Repository(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val identity: Identity.KeyPairRaw by lazy { Identity.loadOrCreate(context) }
    private val localKey: ByteArray by lazy { Identity.loadOrCreateLocalStorageKey(context) }
    private val deviceId: String by lazy { getOrCreateDeviceId(context) }

    val pairedPc: Flow<PairedPc?> = db.pairedPcDao().observePc()

    fun observeEntries(): Flow<List<DecryptedEntry>> =
        db.clipDao().observeRecent().map { rows -> rows.mapNotNull(::tryDecrypt) }

    // A row that fails to decrypt (e.g. after clearing app data mid-way)
    // is dropped from the list rather than crashing the whole screen.
    private fun tryDecrypt(row: ClipEntryRow): DecryptedEntry? = try {
        DecryptedEntry(
            id = row.id,
            entryType = row.entryType,
            content = CryptoUtils.decrypt(localKey, row.encryptedContent),
            createdAt = row.createdAt,
            pinned = row.pinned,
        )
    } catch (e: Exception) {
        null
    }

    suspend fun togglePin(id: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        db.clipDao().setPinned(id, pinned)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        db.clipDao().delete(id)
    }

    suspend fun clearUnpinned() = withContext(Dispatchers.IO) {
        db.clipDao().clearUnpinned()
    }

    suspend fun unpair() = withContext(Dispatchers.IO) {
        db.pairedPcDao().clear()
    }

    suspend fun pair(qrJson: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = PairingQrPayload.fromJson(qrJson)
            val result = SyncClient.pair(payload, identity, deviceId, deviceName())
            val pc = PairedPc(
                deviceId = result.response.deviceId,
                name = result.response.deviceName,
                publicKey = Base64.decode(result.response.publicKey, Base64.NO_WRAP),
                address = result.address, // the address that actually answered, not just the QR's first guess
                certHash = Base64.decode(payload.certHash, Base64.NO_WRAP),
            )
            // This app pairs with exactly one PC. Without the clear(), re-pairing with a PC whose
            // device_id changed (reinstalled / data folder wiped) would leave the old row behind,
            // and "SELECT ... LIMIT 1" could keep returning the stale one.
            db.withTransaction {
                db.pairedPcDao().clear()
                db.pairedPcDao().upsert(pc)
            }
        }.onFailure { Log.w(TAG, "pairing failed", it) }
    }

    /**
     * One screen's worth of facts for "why doesn't it connect": can this phone
     * reach the PC (TCP + TLS + pinned certificate), is this phone's own
     * receiver listening, and which networks is the phone on (a VPN here is a
     * very common culprit).
     */
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val pc = db.pairedPcDao().getPc() ?: return@withContext "ПК не сопряжён."
        val lines = ArrayList<String>()
        lines.add("ПК: ${pc.name} (${pc.address})")

        lines.add(
            try {
                val code = SyncClient.ping(pc)
                "Телефон → ПК: работает (адрес доступен, сертификат совпал, HTTP $code)"
            } catch (e: Exception) {
                Log.w(TAG, "diagnose: ping failed", e)
                "Телефон → ПК: НЕ работает.\n${NetworkErrors.explain(e)}"
            },
        )

        val receiverState = if (ReceiverService.isRunning) {
            "приёмник запущен, слушает порт $SYNC_PORT"
        } else {
            "приёмник НЕ запущен" + (ReceiverService.lastError?.let { " ($it)" } ?: "")
        }
        lines.add("ПК → телефон: $receiverState")

        val addresses = NetworkErrors.localAddresses(context)
        lines.add(
            "Адреса телефона: " +
                (if (addresses.isEmpty()) "нет сети" else addresses.joinToString { "${it.kind} ${it.ip}" }),
        )
        if (addresses.any { it.kind == "VPN" }) {
            lines.add("Включён VPN: он может уводить трафик к ПК в туннель. Отключите его на время проверки.")
        }
        lines.joinToString("\n\n")
    }

    suspend fun sendToPc(entryType: String, content: ByteArray, imageWidth: Int? = null, imageHeight: Int? = null) =
        withContext(Dispatchers.IO) {
            val pc = db.pairedPcDao().getPc() ?: error("Нет сопряжённого ПК")
            val contentB64 = if (entryType == "image") {
                Base64.encodeToString(content, Base64.NO_WRAP)
            } else {
                String(content, Charsets.UTF_8)
            }
            val payload = PushPayload(entryType, contentB64, imageWidth, imageHeight, System.currentTimeMillis())
            SyncClient.push(pc, identity, deviceId, payload)

            // Also record it in this phone's own history so it shows locally.
            db.clipDao().insert(
                ClipEntryRow(
                    id = UUID.randomUUID().toString(),
                    entryType = entryType,
                    encryptedContent = CryptoUtils.encrypt(localKey, content),
                    createdAt = System.currentTimeMillis(),
                    pinned = false,
                    direction = "outgoing",
                ),
            )
        }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    companion object {
        private const val TAG = "ClipSync"
        private const val PREFS = "clipsync_prefs"
        private const val KEY_DEVICE_ID = "device_id"

        fun getOrCreateDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }
    }
}
