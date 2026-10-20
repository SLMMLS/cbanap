package com.clipsync.app.network

import android.util.Base64
import android.util.Log
import com.clipsync.app.crypto.CryptoUtils
import com.clipsync.app.crypto.Identity
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import java.io.IOException
import java.security.GeneralSecurityException

private const val TAG = "ClipSync"

/** Largest /push body (base64 of the sealed payload) the phone will read: ~13 MB of PNG. */
const val MAX_PUSH_BODY_BYTES = 24L * 1024 * 1024
private const val MAX_DEVICE_ID_LEN = 64

/**
 * Android only ever *receives* /push (it always initiates /pair itself, by
 * scanning the PC's QR - see PROTOCOL.md §2), so this only needs to
 * implement one route.
 *
 * This socket is reachable by every device on the LAN, so the order of work
 * matters: nothing proportional to attacker-controlled input is allocated
 * until the sender has been identified as the paired PC, and every early
 * rejection closes the connection (the unread body must not be parsed as
 * the next keep-alive request).
 */
class SyncServer(
    port: Int,
    private val identity: Identity.KeyPairRaw,
    private val getPeerPublicKey: suspend (deviceId: String) -> ByteArray?,
    private val onPushReceived: suspend (senderDeviceId: String, payload: PushPayload) -> Unit,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/push" || session.method != Method.POST) {
            return reject(Response.Status.NOT_FOUND, "not found")
        }

        // ---- 1. Cheap checks on headers only; no body bytes are read yet. ----
        // NanoHTTPD lowercases header names internally.
        val senderId = session.headers["x-device-id"]?.takeIf { it.isNotBlank() && it.length <= MAX_DEVICE_ID_LEN }
            ?: return reject(Response.Status.BAD_REQUEST, "missing X-Device-Id")
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: return reject(Response.Status.LENGTH_REQUIRED, "Content-Length required")
        if (length <= 0L || length > MAX_PUSH_BODY_BYTES) {
            return reject(Response.Status.PAYLOAD_TOO_LARGE, "body too large")
        }

        // ---- 2. Is this the paired PC? Unknown senders never get to make us read a body. ----
        val theirPublicKey = try {
            runBlocking { getPeerPublicKey(senderId) }
        } catch (e: Exception) {
            Log.e(TAG, "peer lookup failed", e)
            return reject(Response.Status.INTERNAL_ERROR, "internal error")
        } ?: return reject(Response.Status.FORBIDDEN, "unknown device")

        // ---- 3. Read, authenticate (AES-GCM tag), parse. ----
        val payload = try {
            val body = readBody(session, length.toInt())
            val raw = Base64.decode(body, Base64.NO_WRAP)
            val transportKey = CryptoUtils.deriveTransportKey(Identity.sharedSecret(identity.privateKey, theirPublicKey))
            val plaintext = CryptoUtils.decrypt(transportKey, raw)
            PushPayload.fromJson(String(plaintext, Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "push from $senderId failed authentication", e)
            return reject(Response.Status.FORBIDDEN, "could not authenticate payload")
        } catch (e: IllegalArgumentException) { // bad base64
            return reject(Response.Status.BAD_REQUEST, "malformed body")
        } catch (e: JSONException) {
            return reject(Response.Status.BAD_REQUEST, "malformed payload")
        } catch (e: IOException) { // client went away mid-body
            Log.w(TAG, "push body truncated", e)
            return reject(Response.Status.BAD_REQUEST, "truncated body")
        } catch (e: RuntimeException) { // e.g. a malformed stored key: our problem, not the sender's
            Log.e(TAG, "unexpected failure while decoding a push from $senderId", e)
            return reject(Response.Status.INTERNAL_ERROR, "internal error")
        }

        if (payload.entryType != "text" && payload.entryType != "image") {
            return reject(Response.Status.BAD_REQUEST, "unsupported entry_type")
        }

        // ---- 4. Hand over to the app. ----
        return try {
            runBlocking { onPushReceived(senderId, payload) }
            newFixedLengthResponse(Response.Status.OK, "application/json", "{}")
        } catch (e: Exception) {
            Log.e(TAG, "failed to store pushed entry", e)
            reject(Response.Status.INTERNAL_ERROR, "internal error")
        }
    }

    private fun reject(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, "text/plain", message).also { it.closeConnection(true) }

    /** `InputStream.read` is not guaranteed to fill the buffer in one call
     * over a socket, so this loops until it actually has everything. */
    private fun readBody(session: IHTTPSession, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var readSoFar = 0
        while (readSoFar < length) {
            val n = session.inputStream.read(buffer, readSoFar, length - readSoFar)
            if (n == -1) throw IOException("connection closed after $readSoFar of $length bytes")
            readSoFar += n
        }
        return buffer
    }
}
