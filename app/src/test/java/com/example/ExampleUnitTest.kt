package com.example

import com.example.ftp.DeviceNameHelper
import com.example.ftp.FtpServerManager
import com.example.ftp.NetworkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun networkInfo_isAvailable_requiresWifiOrHotspotAndIp() {
    val neitherOn = NetworkInfo(
      isHotspotOn = false,
      isWifiConnected = false,
      wifiSsid = null,
      primaryIp = "",
      allIps = emptyList(),
      statusText = "Wi-Fi or Hotspot is off"
    )
    assertFalse(neitherOn.isAvailable)

    val hotspotWithIp = NetworkInfo(
      isHotspotOn = true,
      isWifiConnected = false,
      wifiSsid = null,
      primaryIp = "192.168.43.1",
      allIps = listOf("192.168.43.1"),
      statusText = "Hotspot is on"
    )
    assertTrue(hotspotWithIp.isAvailable)

    val wifiWithIp = NetworkInfo(
      isHotspotOn = false,
      isWifiConnected = true,
      wifiSsid = "MyHomeNetwork",
      primaryIp = "192.168.1.100",
      allIps = listOf("192.168.1.100"),
      statusText = "Wi-Fi is connected: MyHomeNetwork"
    )
    assertTrue(wifiWithIp.isAvailable)
  }

  @Test
  fun ftpServerManager_initialState_isStopped() {
    assertFalse(FtpServerManager.isRunning.value)
    assertFalse(FtpServerManager.startedOnHotspot)
    assertFalse(FtpServerManager.startedOnWifi)
  }

  @Test
  fun deviceNameHelper_sanitize_removesInvalidFtpCharacters() {
    val sanitized = DeviceNameHelper.sanitize("My/Phone:Special*Device?\"Name<Cool>|")
    assertFalse(sanitized.contains("/"))
    assertFalse(sanitized.contains(":"))
    assertFalse(sanitized.contains("*"))
    assertFalse(sanitized.contains("?"))
    assertFalse(sanitized.contains("\""))
    assertFalse(sanitized.contains("<"))
    assertFalse(sanitized.contains(">"))
    assertFalse(sanitized.contains("|"))
    assertTrue(sanitized.isNotEmpty())
  }

  @Test
  fun ftpServerManager_setDeviceName_updatesValueWhenStopped() {
    FtpServerManager.setDeviceName("Pixel 8 Pro")
    assertEquals("Pixel 8 Pro", FtpServerManager.deviceName.value)
  }
}
