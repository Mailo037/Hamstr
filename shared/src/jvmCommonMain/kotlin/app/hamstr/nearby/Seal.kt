package app.hamstr.nearby

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Messages only the device they are for can read: a fresh key pair per message, ECDH with the
 * receiver's public key (P-256), HKDF-SHA256 and AES-256-GCM. The same exchange also yields a
 * second key, for the file data that may follow on the same connection ([Frames]).
 */
internal object Seal {
    private const val INFO = "hamstr nearby 2"
    private val random = SecureRandom()

    class Sealed(val bytes: ByteArray, val dataKey: ByteArray)
    class Opened(val plain: ByteArray, val dataKey: ByteArray)

    fun publicKey(encoded: String): PublicKey? = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
    }.getOrNull()

    fun seal(to: PublicKey, plain: ByteArray): Sealed {
        val eph = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val ephBytes = eph.public.encoded
        val keys = derive(eph.private, to, ephBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys, 0, 32, "AES"), GCMParameterSpec(128, iv))
            updateAAD(ephBytes)
        }
        val json = JSONObject().put("v", 2).put("e", b64(ephBytes)).put("iv", b64(iv)).put("ct", b64(cipher.doFinal(plain)))
        return Sealed(json.toString().toByteArray(Charsets.UTF_8), keys.copyOfRange(32, 64))
    }

    /** The message, or null when it isn't one for [own] (or was changed on the way). */
    fun open(own: PrivateKey, bytes: ByteArray): Opened? = runCatching {
        val m = JSONObject(String(bytes, Charsets.UTF_8))
        if (m.optInt("v") != 2) return null
        val ephBytes = Base64.getDecoder().decode(m.getString("e"))
        val eph = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(ephBytes))
        val keys = derive(own, eph, ephBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keys, 0, 32, "AES"), GCMParameterSpec(128, Base64.getDecoder().decode(m.getString("iv"))))
            updateAAD(ephBytes)
        }
        Opened(cipher.doFinal(Base64.getDecoder().decode(m.getString("ct"))), keys.copyOfRange(32, 64))
    }.getOrNull()

    private fun derive(own: PrivateKey, other: PublicKey, salt: ByteArray): ByteArray {
        val secret = KeyAgreement.getInstance("ECDH").run { init(own); doPhase(other, true); generateSecret() }
        return hkdf(secret, salt, INFO.toByteArray(), 64)
    }

    /** RFC 5869 with SHA-256. */
    fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(salt, "HmacSHA256")); doFinal(secret) }
        val out = ByteArray(length)
        var block = ByteArray(0)
        var done = 0
        var i = 1
        while (done < length) {
            block = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(prk, "HmacSHA256"))
                update(block); update(info); update(i.toByte())
                doFinal()
            }
            block.copyInto(out, done, 0, minOf(block.size, length - done))
            done += block.size
            i++
        }
        return out
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}

/**
 * File data after a sealed offer: AES-GCM frames numbered from 0, so none can be dropped,
 * repeated or swapped. The first byte of a frame says whether it ends the file; it is
 * authenticated too, so a file can't be cut short unnoticed.
 */
internal class Frames(key: ByteArray) {
    private val spec = SecretKeySpec(key, "AES")
    private var counter = 0L

    private fun cipher(mode: Int, flag: Byte) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, spec, GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(0).putLong(counter++).array()))
        updateAAD(byteArrayOf(flag))
    }

    fun seal(data: ByteArray, length: Int, last: Boolean): ByteArray {
        val flag: Byte = if (last) 1 else 0
        return byteArrayOf(flag) + cipher(Cipher.ENCRYPT_MODE, flag).doFinal(data, 0, length)
    }

    /** The data and whether the file ends with it; throws when the frame isn't the next one. */
    fun open(frame: ByteArray): Pair<ByteArray, Boolean> {
        require(frame.isNotEmpty())
        val flag = frame[0]
        require(flag == 0.toByte() || flag == 1.toByte())
        return cipher(Cipher.DECRYPT_MODE, flag).doFinal(frame, 1, frame.size - 1) to (flag == 1.toByte())
    }
}
