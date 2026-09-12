package com.example.ftp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class NetworkInfo(
    val isHotspotOn: Boolean,
    val isWifiConnected: Boolean,
    val wifiSsid: String?,
    val primaryIp: String,
    val allIps: List<String>,
    val statusText: String
) {
    val isAvailable: Boolean
        get() = (isHotspotOn || isWifiConnected) && primaryIp.isNotEmpty()
}

object NetworkHelper {
    fun getNetworkInfo(context: Context): NetworkInfo {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        var isWifiConnected = false
        var wifiSsid: String? = null

        connectivityManager?.let { cm ->
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    isWifiConnected = true
                    try {
                        val info = wifiManager?.connectionInfo
                        val ssid = info?.ssid?.replace("\"", "")
                        if (!ssid.isNullOrEmpty() && ssid != "<unknown ssid>") {
                            wifiSsid = ssid
                        }
                    } catch (_: Exception) {
                    }
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    isWifiConnected = true
                    wifiSsid = "Wired/Emulator"
                }
            }
        }

        val allIps = mutableListOf<String>()
        var hotspotIp: String? = null
        var wifiIp: String? = null
        var isHotspotOn = false

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return fallbackInfo()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress ?: continue
                        allIps.add(hostAddress)

                        val isTetherInterface = name.startsWith("ap") ||
                                name.startsWith("softap") ||
                                name.startsWith("rndis") ||
                                name.contains("tether") ||
                                name.contains("wigig")

                        val isTetherSubnet = hostAddress.startsWith("192.168.43.") ||
                                hostAddress.startsWith("192.168.44.") ||
                                hostAddress.startsWith("192.168.49.") ||
                                hostAddress.startsWith("192.168.50.")

                        if (isTetherInterface || isTetherSubnet) {
                            isHotspotOn = true
                            hotspotIp = hostAddress
                        } else if (name.startsWith("wlan") || name.startsWith("eth")) {
                            if (wifiIp == null) {
                                wifiIp = hostAddress
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (!isHotspotOn && wifiManager != null) {
            try {
                val isApMethod = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
                isApMethod.isAccessible = true
                val enabled = isApMethod.invoke(wifiManager) as? Boolean
                if (enabled == true) {
                    isHotspotOn = true
                }
            } catch (_: Exception) {
            }

            if (!isHotspotOn) {
                try {
                    val getApStateMethod = wifiManager.javaClass.getDeclaredMethod("getWifiApState")
                    getApStateMethod.isAccessible = true
                    val state = getApStateMethod.invoke(wifiManager) as? Int
                    if (state == 13) {
                        isHotspotOn = true
                    }
                } catch (_: Exception) {
                }
            }
        }

        val primaryIp = when {
            isHotspotOn && !hotspotIp.isNullOrEmpty() -> hotspotIp
            isHotspotOn && allIps.isNotEmpty() -> allIps.first()
            isWifiConnected && !wifiIp.isNullOrEmpty() -> wifiIp
            isWifiConnected && allIps.isNotEmpty() -> allIps.first()
            allIps.isNotEmpty() -> allIps.first()
            else -> ""
        }

        val statusText = when {
            isHotspotOn -> "Hotspot is on"
            isWifiConnected && !wifiSsid.isNullOrEmpty() -> "Wi-Fi is connected: $wifiSsid"
            isWifiConnected -> "Wi-Fi is connected"
            else -> "Wi-Fi or Hotspot is off"
        }

        return NetworkInfo(
            isHotspotOn = isHotspotOn,
            isWifiConnected = isWifiConnected,
            wifiSsid = wifiSsid,
            primaryIp = primaryIp,
            allIps = allIps,
            statusText = statusText
        )
    }

    private fun fallbackInfo(): NetworkInfo {
        return NetworkInfo(
            isHotspotOn = false,
            isWifiConnected = false,
            wifiSsid = null,
            primaryIp = "",
            allIps = emptyList(),
            statusText = "Wi-Fi or Hotspot is off"
        )
    }

    fun observeNetwork(context: Context): Flow<NetworkInfo> = callbackFlow {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        trySend(getNetworkInfo(context))

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getNetworkInfo(context))
            }
            override fun onLost(network: Network) {
                trySend(getNetworkInfo(context))
            }
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(getNetworkInfo(context))
            }
        }

        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
        }

        awaitClose {
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
    }
}
