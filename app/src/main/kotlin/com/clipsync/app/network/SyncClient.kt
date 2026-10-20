package com.clipsync.app.network

import android.util.Base64
import android.util.Log
import com.clipsync.app.crypto.CryptoUtils
import com.clipsync.app.crypto.Identity
import com.clipsync.app.data.PairedPc
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "ClipSync"

/** Which of the QR's addresses actually worked, plus the PC's answer. */
data class PairResult(val response: PairResponse, val address: String)

/**
 * Talks to the PC's embedded server (`server.rs`) over HTTPS with the PC's
 * self-signed certificate pinned by hash - see PinnedHttp.kt for why this
 * is pinning and not "trust anything", and PROTOCOL.md §2a for why HTTPS
 * is needed here specifically (Android blocks outbound cleartext by
 * default; the PC's own outbound pushes to this phone remain plain HTTP,
 * since that direction has no such OS policy to satisfy).
 *
 * Timeouts: 5 s to connect, 10 s per read/write. The earlier 3 s/3 s was
 * too tight for a phone whose Wi-Fi radio is in power-save (the first
 * packet after idle can take longer than that just to wake the radio) -
 * which looks exactly like "the PC is unreachable". Still bounded, so a
 * PC that is genuinely off fails promptly instead of hanging.
 *
 * HTTP/1.1 only: the PC's tiny_http server speaks nothing else, so there is
 * nothing to gain from offering h2 in ALPN.
 */
object SyncClient {
    private val baseClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()
    private val TEXT = "text/plain".toMediaType()

    private fun pinnedClient(certHash: ByteArray): OkHttpClient =
        baseClient.newBuilder()
            .sslSocketFactory(PinnedHttp.socketFactory(certHash), PinnedHttp.trustManager(certHash))
            .hostnameVerifier(PinnedHttp.trustAnyHostname)
            .build()

    /**
     * Tries the QR's primary address, then each fallback. Stops at the first
     * address that answers at all: if the PC says "no" (stale token), its
     * other addresses are the same PC and would say the same thing.
     */
    fun pair(
        payload: PairingQrPayload,
        identity: Identity.KeyPairRaw,
        myDeviceId: String,
        myDeviceName: String,
    ): PairResult {
        val certHash = Base64.decode(payload.certHash, Base64.NO_WRAP)
        val client = pinnedClient(certHash)
        val body = PairRequest(
            token = payload.token,
            deviceId = myDeviceId,
            deviceName = myDeviceName,
            publicKey = Base64.encodeToString(identity.publicKey, Base64.NO_WRAP),
        ).toJson()

        val failures = ArrayList<String>()
        for (addr in payload.candidateAddresses()) {
            try {
                return PairResult(pairOnce(client, addr, body), addr)
            } catch (e: PcRejectedException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "pairing via $addr failed", e)
                failures.add("• $addr - ${NetworkErrors.explain(e)}")
            }
        }
        throw PcUnreachableException(
            if (failures.size == 1) failures[0].removePrefix("• ")
            else "Не удалось подключиться к ПК ни по одному из адресов:\n" + failures.joinToString("\n"),
        )
    }

    private fun pairOnce(client: OkHttpClient, addr: String, jsonBody: String): PairResponse {
        val request = Request.Builder()
            .url("https://$addr/pair")
            .post(jsonBody.toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string()
            if (!response.isSuccessful || bodyText == null) {
                throw PcRejectedException(response.code, rejectionMessage(response.code))
            }
            val parsed = try {
                PairResponse.fromJson(bodyText)
            } catch (e: JSONException) {
                throw IOException("ПК ответил в неожиданном формате - версии приложений на ПК и телефоне не совпадают?")
            }
            // An X25519 public key is exactly 32 bytes. Anything else would be stored and then
            // blow up with an obscure array error on every later push, so refuse it here.
            val keyOk = try {
                Base64.decode(parsed.publicKey, Base64.NO_WRAP).size == 32
            } catch (e: IllegalArgumentException) {
                false
            }
            if (!keyOk) throw IOException("ПК прислал некорректный ключ шифрования - версии приложений не совпадают?")
            return parsed
        }
    }

    private fun rejectionMessage(code: Int): String = when (code) {
        403 -> "ПК отклонил сопряжение (403): QR-код устарел или уже использован. " +
            "На ПК нажмите «Добавить устройство» и отсканируйте новый QR."
        404 -> "ПК ответил 404: по этому адресу работает не ClipSync (или очень старая версия)."
        else -> "ПК отклонил сопряжение (код $code)"
    }

    /** Throws on any failure - callers decide whether that's fatal or, for
     * a background sync attempt, just something to log and move on from. */
    fun push(pc: PairedPc, identity: Identity.KeyPairRaw, myDeviceId: String, payload: PushPayload) {
        val transportKey = CryptoUtils.deriveTransportKey(Identity.sharedSecret(identity.privateKey, pc.publicKey))
        val encrypted = CryptoUtils.encrypt(transportKey, payload.toJson().toByteArray(Charsets.UTF_8))
        val bodyB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)

        val httpRequest = Request.Builder()
            .url("https://${pc.address}/push")
            .header("X-Device-Id", myDeviceId)
            .post(bodyB64.toRequestBody(TEXT))
            .build()

        pinnedClient(pc.certHash).newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw PcRejectedException(
                    response.code,
                    if (response.code == 403) {
                        "ПК не узнал это устройство (сопряжение потеряно). Сопрягите телефон с ПК заново."
                    } else {
                        "ПК не принял данные (код ${response.code})"
                    },
                )
            }
        }
    }

    /**
     * Proves TCP + TLS + certificate pin all work, without sending any
     * clipboard data. ANY HTTP status counts as success - the PC answers
     * 404 to paths it doesn't know, and a 404 still means "reached the right
     * PC and its certificate matched". Returns the status code.
     */
    fun ping(pc: PairedPc): Int {
        val request = Request.Builder().url("https://${pc.address}/ping").get().build()
        pinnedClient(pc.certHash).newCall(request).execute().use { return it.code }
    }
}
