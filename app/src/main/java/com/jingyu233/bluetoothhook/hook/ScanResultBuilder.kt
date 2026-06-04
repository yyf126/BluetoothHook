package com.jingyu233.bluetoothhook.hook

import android.bluetooth.BluetoothAdapter
import android.os.SystemClock
import com.jingyu233.bluetoothhook.utils.Logger
import de.robv.android.xposed.XposedHelpers

/**
 * ScanResult构造器 - MIUI 14 Android 13 适配版
 *
 * Android 13 (API 33) 的 ScanResult 构造器:
 * public ScanResult(BluetoothDevice device, int eventType, int primaryPhy,
 *                   int secondaryPhy, int advertisingSid, int txPower,
 *                   int rssi, int periodicAdvInt, ScanRecord scanRecord,
 *                   long timestampNanos)
 *
 * eventType 取值:
 * - 0x00: ET_EXTENDED_ADVERTISING
 * - 0x01: ET_EXTENDED | ET_CONNECTABLE
 * - 0x10: ET_LEGACY_ADVERTISING
 * - 0x13: ET_LEGACY | ET_CONNECTABLE | ET_SCANNABLE
 *
 * 注意: ScanResult 是 framework 层公共 API，MIUI 不会修改它
 * 所以这个类在所有 Android 13 设备上应该都是兼容的
 */
class ScanResultBuilder(private val classLoader: ClassLoader) {
    companion object {
        private val TAG = Logger.Tags.HOOK_BUILDER
    }

    /**
     * 构造ScanResult对象
     *
     * @param macAddress MAC地址 (格式: AA:BB:CC:DD:EE:FF)
     * @param rssi 信号强度 (-100 to 0)
     * @param advDataHex 广播数据十六进制字符串
     * @param scanResponseHex 扫描响应数据十六进制字符串（可选）
     * @param useExtendedAdvertising 是否使用扩展广播
     * @param deviceName 设备名称（可选，会添加到广播数据中）
     * @return ScanResult对象，失败返回null
     */
    fun buildScanResult(
        macAddress: String,
        rssi: Int,
        advDataHex: String,
        scanResponseHex: String = "",
        useExtendedAdvertising: Boolean = false,
        deviceName: String? = null
    ): Any? {
        return try {
            // 1. 获取BluetoothAdapter并创建BluetoothDevice
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val device = adapter.getRemoteDevice(macAddress)

            // 2. 合并广播数据和扫描响应数据
            val combinedDataHex = if (scanResponseHex.isNotEmpty()) {
                advDataHex + scanResponseHex
            } else {
                advDataHex
            }
            val advBytes = hexStringToByteArray(combinedDataHex)

            // 3. 使用ScanRecord.parseFromBytes解析广播数据
            val scanRecordClass = XposedHelpers.findClass(
                "android.bluetooth.le.ScanRecord",
                classLoader
            )
            val scanRecord = XposedHelpers.callStaticMethod(
                scanRecordClass,
                "parseFromBytes",
                advBytes
            )

            // 4. 构造ScanResult对象
            val scanResultClass = XposedHelpers.findClass(
                "android.bluetooth.le.ScanResult",
                classLoader
            )
            val timestampNanos = SystemClock.elapsedRealtimeNanos()

            // 确定事件类型
            // AOSP flags: ET_LEGACY_ADV=0x0010, ET_CONNECTABLE=0x0001, ET_SCANNABLE=0x0002
            val eventType = if (useExtendedAdvertising) {
                0x01  // CONNECTABLE extended advertising
            } else {
                0x13  // LEGACY | CONNECTABLE | SCANNABLE
            }

            // 尝试使用完整参数的构造器 (Android 13+)
            try {
                XposedHelpers.newInstance(
                    scanResultClass,
                    device,                                        // BluetoothDevice
                    eventType,                                     // eventType
                    1,                                             // primaryPhy: 1 = LE 1M
                    if (useExtendedAdvertising) 1 else 0,           // secondaryPhy: 1 = LE 1M, 0 = None
                    255,                                           // advertisingSid: 255 = SID_NOT_PRESENT
                    127,                                           // txPower: 127 = TX_POWER_NOT_PRESENT (0x7F)
                    rssi,                                          // rssi
                    0,                                             // periodicAdvInt: 0 = None
                    scanRecord,                                    // ScanRecord
                    timestampNanos                                 // timestamp
                )
            } catch (e: Exception) {
                // 降级：使用简化构造器（兼容旧版本Android）
                // public ScanResult(BluetoothDevice device, ScanRecord scanRecord,
                //                   int rssi, long timestampNanos)
                XposedHelpers.newInstance(
                    scanResultClass,
                    device,
                    scanRecord,
                    rssi,
                    timestampNanos
                )
            }
        } catch (e: Throwable) {
            Logger.Hook.e(TAG, "Failed to build ScanResult for MAC=$macAddress", e)
            null
        }
    }

    /**
     * 十六进制字符串转字节数组
     */
    private fun hexStringToByteArray(hexString: String): ByteArray {
        var cleanHex = hexString.replace(" ", "").replace(":", "")
        // 奇数长度时截断最后一个字符，确保成对解析
        if (cleanHex.length % 2 != 0) {
            Logger.Hook.w(TAG, "Odd-length hex string (${cleanHex.length} chars), truncating last nibble")
            cleanHex = cleanHex.substring(0, cleanHex.length - 1)
        }
        val len = cleanHex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) +
                    Character.digit(cleanHex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * 生成标准BLE广播数据
     * 包含Flags和Complete Local Name
     */
    fun generateStandardAdvData(deviceName: String): String {
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val nameLength = nameBytes.size
        // Flags AD结构 (3 bytes): 02 01 06
        val flags = "020106"
        // Complete Local Name AD结构
        val nameHex = StringBuilder()
        nameHex.append(String.format("%02X", nameLength + 1))
        nameHex.append("09")
        nameBytes.forEach { byte ->
            nameHex.append(String.format("%02X", byte))
        }
        return flags + nameHex.toString()
    }

    /**
     * 验证MAC地址格式
     */
    fun isValidMacAddress(mac: String): Boolean {
        return mac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))
    }

    /**
     * 验证RSSI范围
     */
    fun isValidRssi(rssi: Int): Boolean {
        return rssi in -100..0
    }

    /**
     * 验证广播数据长度（Legacy BLE最多31字节，Extended最多254字节）
     */
    fun isValidAdvDataLength(hexString: String, extended: Boolean = false): Boolean {
        val byteCount = hexString.replace(" ", "").replace(":", "").length / 2
        return if (extended) {
            byteCount <= 254
        } else {
            byteCount <= 31
        }
    }
}
