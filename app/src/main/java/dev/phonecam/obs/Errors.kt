package dev.phonecam.obs

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Turns network exceptions into one sentence and a next step; never shows addresses, ports or errno names. */
object Errors {
    fun friendly(e: Throwable?, computer: String = "the computer"): String = sentence(e, computer).replaceFirstChar { it.uppercase() }

    private fun sentence(e: Throwable?, computer: String): String {
        if (e is WifiClient.Refused) return e.message ?: "OBS refused the connection."
        val text = generateSequence(e) { it.cause }.joinToString(" ") { "${it.javaClass.simpleName} ${it.message.orEmpty()}" }
        return when {
            e is ConnectException || "ECONNREFUSED" in text || "Connection refused" in text ->
                "$computer refused the connection. In OBS, click Pair a phone over Wi-Fi again, then scan the new code."
            e is SocketTimeoutException || "ETIMEDOUT" in text || "timed out" in text ->
                "$computer didn't answer. Check that the phone and $computer are on the same Wi-Fi. Guest and public Wi-Fi often block this; use the phone's hotspot or the USB cable."
            e is NoRouteToHostException || e is UnknownHostException || "EHOSTUNREACH" in text || "ENETUNREACH" in text ->
                "Can't reach $computer from this Wi-Fi. Put both on the same network, or use the phone's hotspot or the USB cable."
            e is java.io.IOException && e.message != null && e.cause == null && !e.message!!.contains("errno", ignoreCase = true) -> e.message!!
            else -> "Pairing didn't work. In OBS, click Pair a phone over Wi-Fi again and scan the new code."
        }
    }
}
