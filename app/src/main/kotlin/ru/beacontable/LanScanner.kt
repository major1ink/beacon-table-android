package ru.beacontable

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// Ищет столы в своей подсети Wi-Fi: стучится в /api/version на порт по
// умолчанию. Сервер ничего не объявляет, так что находятся и старые версии.
class LanScanner(context: Context) {

    private val connectivity = context.getSystemService<ConnectivityManager>()!!
    private val main = Handler(Looper.getMainLooper())
    private var pool: ExecutorService? = null

    fun scan(onFound: (url: String, version: String) -> Unit, onDone: (hasNetwork: Boolean) -> Unit) {
        stop()
        val (network, hosts) = localNetwork() ?: return onDone(false)
        val pool = Executors.newFixedThreadPool(THREADS)
        this.pool = pool
        val left = AtomicInteger(hosts.size)
        for (host in hosts) {
            pool.execute {
                val url = "http://$host:$PORT"
                val version = probe(network, url)
                main.post {
                    if (this.pool !== pool) return@post
                    if (version != null) onFound(url, version)
                    if (left.decrementAndGet() == 0) {
                        this.pool = null
                        onDone(true)
                    }
                }
            }
        }
        pool.shutdown()
    }

    fun stop() {
        pool?.shutdownNow()
        pool = null
    }

    // Wi-Fi или провод, а не активная сеть: при включённом VPN активной
    // считается она, и искали бы в её адресах.
    @Suppress("DEPRECATION")
    private fun localNetwork(): Pair<Network, List<String>>? {
        for (network in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) continue
            val link = connectivity.getLinkProperties(network) ?: continue
            val addr = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: continue
            return network to hostsAround(addr.address.address, addr.prefixLength)
        }
        return null
    }

    private fun probe(network: Network, url: String): String? = try {
        val conn = network.openConnection(URL("$url/api/version")) as HttpURLConnection
        conn.connectTimeout = 700
        conn.readTimeout = 1500
        try {
            if (conn.responseCode != 200) {
                null
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body).optString("version").ifEmpty { null }
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}

private const val PORT = 8080
private const val THREADS = 128

// Подсеть шире /24 (бывает в офисах) целиком не перебираем — только свою /24.
private fun hostsAround(own: ByteArray, prefix: Int): List<String> {
    val ip = own.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
    val bits = 32 - prefix.coerceIn(24, 30)
    val base = ip and (-1 shl bits)
    return (1 until (1 shl bits) - 1)
        .map { base + it }
        .filter { it != ip }
        .map { "${it ushr 24 and 0xff}.${it ushr 16 and 0xff}.${it ushr 8 and 0xff}.${it and 0xff}" }
}
