package com.omni.downloader.engine

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/**
 * Export the Android trusted CA store to a private PEM bundle for the standalone
 * aria2/OpenSSL executable. Never disable peer certificate verification.
 */
internal object AndroidCaBundle {
    private const val FILE_NAME = "aria2-android-ca.pem"
    private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(1)

    @Synchronized
    fun get(context: Context): File {
        val dir = File(context.noBackupFilesDir, "native-runtime").apply { mkdirs() }
        val output = File(dir, FILE_NAME)
        if (output.isFile && output.length() > 1024 &&
            System.currentTimeMillis() - output.lastModified() < MAX_AGE_MS
        ) return output

        val store = KeyStore.getInstance("AndroidCAStore")
        store.load(null)
        val aliases = store.aliases()
        val certs = mutableListOf<X509Certificate>()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            (store.getCertificate(alias) as? X509Certificate)?.let(certs::add)
        }
        if (certs.isEmpty()) throw IOException("AndroidCAStore 没有可用的受信任根证书")

        val tmp = File(dir, "$FILE_NAME.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                certs.forEach { cert ->
                    out.write("-----BEGIN CERTIFICATE-----\n".toByteArray(Charsets.US_ASCII))
                    val text = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
                    text.chunked(64).forEach { line ->
                        out.write(line.toByteArray(Charsets.US_ASCII))
                        out.write('\n'.code)
                    }
                    out.write("-----END CERTIFICATE-----\n".toByteArray(Charsets.US_ASCII))
                }
                out.fd.sync()
            }
            if (tmp.length() < 1024L) throw IOException("Android CA 证书导出为空")
            if (!tmp.renameTo(output)) {
                tmp.copyTo(output, overwrite = true)
                tmp.delete()
            }
        } finally {
            tmp.delete()
        }
        return output
    }
}
