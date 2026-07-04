package com.apk.claw.android.shizuku.autosetup

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import com.apk.claw.android.utils.XLog

private const val TAG_ADB = "OctopusAdbManager"

/**
 * octopus 的 ADB 连接管理器 —— [AbsAdbConnectionManager] 的具体实现(libadb-android)。
 *
 * 负责给无线调试提供**稳定的身份**:一对 RSA2048 私钥 + 自签 X509 证书。
 * 关键:**证书持久化到 filesDir**。配对时设备记住的是我们的公钥;若每次启动都重新生成,
 * 就得反复重新配对。落盘后:配一次,之后每次只要「连接 + 拉起 Shizuku」即可。
 *
 * 仅在需要「全自动配置 Shizuku」时才实例化(懒加载),平时零开销。
 * minSdk=28,故无需 PRNGFixes(仅 <4.4 需要)。
 */
class OctopusAdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val priv: PrivateKey
    private val cert: Certificate

    private val keyFile = File(context.filesDir, "adb/adbkey.pk8")
    private val certFile = File(context.filesDir, "adb/adbkey.cer")

    init {
        // 声明对端 adbd 的 API 版本,libadb 据此选握手方式(11+ 走 TLS)。
        setApi(Build.VERSION.SDK_INT)
        val loaded = load()
        if (loaded != null) {
            priv = loaded.first
            cert = loaded.second
        } else {
            val (p, c) = generate()
            priv = p
            cert = c
            save(p, c)
        }
    }

    override fun getPrivateKey(): PrivateKey = priv

    override fun getCertificate(): Certificate = cert

    override fun getDeviceName(): String = "OctopusMobile"

    // ---------- 身份持久化 ----------

    private fun load(): Pair<PrivateKey, Certificate>? {
        if (!keyFile.exists() || !certFile.exists()) return null
        return try {
            val pk = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val ct = certFile.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            pk to ct
        } catch (e: java.security.spec.InvalidKeySpecException) {
            XLog.w(TAG_ADB, "私钥格式损坏,将重新生成: ${e.message}")
            null
        } catch (e: java.security.cert.CertificateException) {
            XLog.w(TAG_ADB, "证书格式损坏,将重新生成: ${e.message}")
            null
        } catch (e: java.io.IOException) {
            XLog.w(TAG_ADB, "密钥/证书文件读失败,将重新生成: ${e.message}")
            null
        }
    }

    private fun save(pk: PrivateKey, ct: Certificate) {
        keyFile.parentFile?.mkdirs()
        keyFile.writeBytes(pk.encoded)      // PKCS#8
        certFile.writeBytes(ct.encoded)     // X.509 DER
    }

    /** 生成 RSA2048 密钥对 + 自签 X509 证书(照搬 libadb-android 官方示例,用 sun.security)。 */
    private fun generate(): Pair<PrivateKey, Certificate> {
        val keyGen = KeyPairGenerator.getInstance("RSA").apply {
            initialize(KEY_SIZE, SecureRandom.getInstance("SHA1PRNG"))
        }
        val kp = keyGen.generateKeyPair()
        val publicKey = kp.public
        val privateKey = kp.private

        val subject = "CN=Octopus Mobile"
        val algorithm = "SHA512withRSA"
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + CERT_VALIDITY_MS)

        val extensions = CertificateExtensions().apply {
            set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier))
            set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        }
        val x500Name = X500Name(subject)
        val info = X509CertInfo().apply {
            set("version", CertificateVersion(CertificateVersion.V3))
            set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
            set("subject", CertificateSubjectName(x500Name))
            set("key", CertificateX509Key(publicKey))
            set("validity", CertificateValidity(notBefore, notAfter))
            set("issuer", CertificateIssuerName(x500Name))
            set("extensions", extensions)
        }
        val certImpl = X509CertImpl(info)
        certImpl.sign(privateKey, algorithm)
        return privateKey to certImpl
    }

    companion object {
        private const val KEY_SIZE = 2048
        private const val CERT_VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000 // 10 年

        @Volatile
        private var instance: OctopusAdbManager? = null

        @JvmStatic
        fun getInstance(context: Context): OctopusAdbManager =
            instance ?: synchronized(this) {
                instance ?: OctopusAdbManager(context.applicationContext).also { instance = it }
            }
    }
}
