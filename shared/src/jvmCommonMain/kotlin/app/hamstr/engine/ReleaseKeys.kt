package app.hamstr.engine

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Who may publish what Hamstr runs. A release's SHA256SUMS.txt says which file has which hash;
 * SHA256SUMS.txt.sig, made with a key that only the release workflow holds, says that list is
 * genuine. A checksum alone only proves the file matches its own release, so someone who could
 * change a release could change both; the signature they can't make.
 */
object ReleaseKeys {
    /** Signs Cheek's releases (CHEEK_SIGNING_KEY in the cheek repository). */
    const val CHEEK = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEp0INvYFhy/I0h39/GzflYk5LmbOz+S0nOcaUXWskNAbjLplFDe2LIjZt8YtEo0ttKeM8agn99uuIN+Pbh5KEeA=="

    /** Signs Hamstr's releases (HAMSTR_SIGNING_KEY in this repository). */
    const val HAMSTR = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEw57/ZlLWI/FArvhv3JYUejo6KNcraHL9r9fe3Z5Xe9k5TzM/n3vDp9AtA3qenr/e79caZ2eN18VO+P+S3fuS3Q=="

    /** True when [signature] (DER, as `openssl dgst -sha256 -sign` writes it) is [key]'s over [data]. */
    fun verify(data: ByteArray, signature: ByteArray, key: String): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key))))
            update(data)
            verify(signature)
        }
    }.getOrDefault(false)

    /** The "<hash>  <name>" lines of a SHA256SUMS.txt, by name. */
    fun sums(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim().split(Regex("\\s+"), limit = 2) }
        .filter { it.size == 2 && it[0].length == 64 }
        .associate { (hash, name) -> name.removePrefix("*") to hash.lowercase() }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

/** Version numbers as Hamstr and Cheek tag them: 1.10.0 is later than 1.9.2. */
object Versions {
    fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    /** True when [a] is a later version than [b]. */
    fun newer(a: String, b: String): Boolean {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /**
     * Whether a Cheek release keeps the command line Hamstr speaks with [own]: the same major
     * version, and for 0.x the same minor, like Cargo's semver rules.
     */
    fun sameCheekLine(version: String, own: String): Boolean {
        if (!Regex("""\d+\.\d+\.\d+""").matches(version.trim().removePrefix("v"))) return false
        val v = parts(version)
        val o = parts(own)
        return v[0] == o[0] && (o[0] > 0 || v[1] == o[1])
    }
}
