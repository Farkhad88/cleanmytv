package dev.cleanmytv.transport

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.cleanmytv.core.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A dedicated ADB identity, encrypted by an app-scoped Android Keystore key. Never backed up. */
class Credentials(private val context: Context) {
    data class Identity(val key: PrivateKey, val certificate: X509Certificate) { val publicKey get() = certificate.publicKey as RSAPublicKey }
    @Synchronized fun identity(): Identity {
        val f = File(context.noBackupFilesDir, "adb-identity.enc")
        if(f.exists()) {
            val data = f.readBytes(); require(data.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            val clear = cipher.doFinal(data.copyOfRange(12, data.size))
            try {
                val o = JSONObject(clear.toString(Charsets.UTF_8))
                return Identity(KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(o.getString("key")))),
                    CertificateFactory.getInstance("X.509").generateCertificate(Base64.getDecoder().decode(o.getString("cert")).inputStream()) as X509Certificate)
            } finally { clear.fill(0) }
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val subject = X500Name("CN=CleanMyTV Android companion")
        val provider = BouncyCastleProvider()
        val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(JcaX509v3CertificateBuilder(subject,
            BigInteger(128, SecureRandom()), Date(System.currentTimeMillis() - 60000), Date(System.currentTimeMillis() + 10L * 365 * 86400000), subject, pair.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.private)))
        val clear = JSONObject().put("key", b64(pair.private.encoded)).put("cert", b64(cert.encoded)).toString().toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val temp = File(f.parentFile, "adb-identity.tmp")
        java.io.FileOutputStream(temp).use { it.write(cipher.iv + cipher.doFinal(clear)); it.fd.sync() }; clear.fill(0)
        check(temp.renameTo(f)) { "Could not save the ADB identity." }
        return Identity(pair.private, cert)
    }
    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "cleanmytv-adb-wrap-v1"
        if(store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private val pins get() = context.getSharedPreferences("paired-certificate-pins", Context.MODE_PRIVATE)
    fun seal(bytes: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return c.iv + c.doFinal(bytes)
    }
    fun open(bytes: ByteArray): ByteArray {
        require(bytes.size >= 28) { "The encrypted recovery record is incomplete." }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        return c.doFinal(bytes.copyOfRange(12,bytes.size))
    }
    fun pin(host: String): String? = pins.getString(sha256(host.toByteArray()), null)
    fun savePin(host: String, certificate: java.security.cert.Certificate) {
        check(pins.edit().putString(sha256(host.toByteArray()), sha256(certificate.publicKey.encoded)).commit())
    }
    fun fingerprint(): String = digest("MD5", AdbKey.publicBytes(identity().publicKey)).uppercase().chunked(2).joinToString(":")
}
