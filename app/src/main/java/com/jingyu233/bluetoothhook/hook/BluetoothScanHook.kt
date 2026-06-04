package com.jingyu233.bluetoothhook.hook

import com.jingyu233.bluetoothhook.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * 蓝牙扫描Hook核心类 - MIUI 14 Android 13 适配版
 *
 * Hook目标: com.android.bluetooth.gatt.GattService.onScanResultInternal
 * 方法签名: (int, int, String, int, int, int, int, int, int, byte[])V
 *
 * MIUI 14 的蓝牙扫描架构:
 * - GattService 持有 mScanManager (ScanManager)
 * - ScanManager 持有 mScannerMap (ContextMap<IScannerCallback, ...>)
 * - 扫描结果通过 IScannerCallback.onScanResult(ScanResult) 回调给应用
 */
class BluetoothScanHook(
    private val classLoader: ClassLoader,
    private val prefs: XSharedPreferences
) {
    companion object {
        private val TAG = Logger.Tags.HOOK_SCANNER
        private const val METHOD_ON_SCAN_RESULT_INTERNAL = "onScanResultInternal"

        // MIUI 14 候选类（按优先级排序）
        // MIUI 14 将扫描逻辑放在 GattService 中，而非 AOSP 的 ScanController/TransitionalScanHelper
        private val CANDIDATE_CLASSES = arrayOf(
            "com.android.bluetooth.gatt.GattService",              // MIUI 14 实际使用的类 ★
            "com.android.bluetooth.gatt.ScanManager",                // MIUI 14 备选（ScanManager也存在）
            "com.android.bluetooth.le_scan.ScanController",         // AOSP Android 12+
            "com.android.bluetooth.le_scan.TransitionalScanHelper",  // AOSP Android 13+
            "com.android.bluetooth.gatt.ScanManagerInjector"        // MIUI 特有注入器
        )
    }

    private lateinit var scanResultBuilder: ScanResultBuilder
    private lateinit var virtualDeviceInjector: VirtualDeviceInjector

    fun init() {
        try {
            Logger.Hook.i(TAG, "Initializing BluetoothScanHook for MIUI 14")

            // 初始化辅助工具
            scanResultBuilder = ScanResultBuilder(classLoader)
            virtualDeviceInjector = VirtualDeviceInjector(scanResultBuilder, prefs)

            // Hook主要方法（尝试多个类名和方法签名）
            val hooked = hookScanResultInternal()

            if (hooked) {
                Logger.Hook.i(TAG, "Successfully hooked onScanResultInternal")
            } else {
                Logger.Hook.e(TAG, "Failed to hook scan methods - no matching class/method found")
            }

        } catch (e: Throwable) {
            Logger.Hook.e(TAG, "Failed to initialize BluetoothScanHook", e)
        }
    }

    /**
     * Hook onScanResultInternal 方法
     * 使用反射在运行时发现方法，兼容所有Android版本和OEM定制
     *
     * MIUI 14 签名: (int eventType, int addressType, String address,
     *              int primaryPhy, int secondaryPhy, int advertisingSid,
     *              int txPower, int rssi, int periodicAdvInt, byte[] scanRecord)
     * 注意: 只有10个参数，没有 AOSP Android 15 的第11个参数 String originalAddress
     */
    private fun hookScanResultInternal(): Boolean {
        for (className in CANDIDATE_CLASSES) {
            val clazz = try {
                XposedHelpers.findClass(className, classLoader)
            } catch (e: Throwable) {
                Logger.Hook.i(TAG, "Class not found: $className, trying next...")
                continue
            }

            // 通过反射查找所有名为 onScanResultInternal 的方法
            val methods = findMethodsByName(clazz, METHOD_ON_SCAN_RESULT_INTERNAL)
            if (methods.isEmpty()) {
                Logger.Hook.i(TAG, "No $METHOD_ON_SCAN_RESULT_INTERNAL in $className, trying next class...")
                continue
            }

            // 记录找到的所有方法签名（用于调试）
            for (m in methods) {
                val paramStr = m.parameterTypes.joinToString(",") { it.name }
                Logger.Hook.i(TAG, "Found method: $className.$METHOD_ON_SCAN_RESULT_INTERNAL($paramStr)")
            }

            // Hook所有找到的重载方法
            var hooked = false
            for (method in methods) {
                try {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                injectVirtualDevices(param)
                            } catch (e: Throwable) {
                                Logger.Hook.e(TAG, "Error during virtual device injection", e)
                            }
                        }
                    })
                    val paramStr = method.parameterTypes.joinToString(",") { it.name }
                    Logger.Hook.i(TAG, "Hooked $className.$METHOD_ON_SCAN_RESULT_INTERNAL($paramStr)")
                    hooked = true
                } catch (e: Throwable) {
                    Logger.Hook.e(TAG, "Failed to hook method variant in $className", e)
                }
            }

            if (hooked) return true
        }
        return false
    }

    /**
     * 通过反射查找类中所有指定名称的方法（包括父类中声明的）
     */
    private fun findMethodsByName(clazz: Class<*>, name: String): List<Method> {
        val result = mutableListOf<Method>()
        try {
            // 搜索当前类和所有父类
            var current: Class<*>? = clazz
            while (current != null) {
                for (method in current.declaredMethods) {
                    if (method.name == name) {
                        method.isAccessible = true
                        result.add(method)
                    }
                }
                current = current.superclass
            }
        } catch (e: Throwable) {
            Logger.Hook.e(TAG, "Error finding methods by name: $name", e)
        }
        return result
    }

    /**
     * 注入虚拟设备到扫描结果
     * 兼容 MIUI 14 的 GattService 结构
     *
     * MIUI 14 的 GattService 结构:
     * - this (GattService) 持有 mScanManager (ScanManager)
     * - mScanManager 持有 scannerMap (ContextMap<IScannerCallback, PendingIntentInfo>)
     * - 通过 mScanManager.getRegularScanQueue() 获取活跃扫描客户端
     */
    private fun injectVirtualDevices(param: XC_MethodHook.MethodHookParam) {
        try {
            // 重新加载配置（SharedPreferences可能被UI进程更新）
            prefs.reload()

            // 检查全局开关
            val globalEnabled = prefs.getBoolean("global_enabled", true)
            if (!globalEnabled) {
                return // 全局开关关闭，静默返回
            }

            val instance = param.thisObject

            // MIUI 14: GattService 中的扫描管理相关字段
            // GattService -> mScanManager -> ScanManager
            val scanManager = getFieldSafe(instance, "mScanManager")
                ?: getFieldSafe(instance, "mScanHelper")
                ?: instance // 如果都没有，this本身可能就是ScanManager

            // 获取 ScannerMap (ContextMap<IScannerCallback, PendingIntentInfo>)
            // 尝试多个可能的字段路径:
            // 1. GattService.mScannerMap (如果GattService直接持有)
            // 2. ScanManager.mScannerMap (如果ScanManager持有)
            // 3. GattService.mScanManager.mScannerMap
            val scannerMap = getFieldSafe(instance, "mScannerMap")
                ?: getFieldSafe(scanManager, "mScannerMap")
                ?: getFieldSafe(getFieldSafe(instance, "mScanHelper"), "mScannerMap")

            if (scannerMap == null) {
                Logger.Hook.w(TAG, "Cannot find scannerMap field, skipping injection")
                return
            }

            // 获取当前扫描队列
            // MIUI 14 ScanManager 的方法名
            val scanQueue = callMethodSafe(scanManager, "getRegularScanQueue") as? Collection<*>
                ?: callMethodSafe(scanManager, "getScanQueue") as? Collection<*>

            if (scanQueue == null || scanQueue.isEmpty()) {
                return // 没有活跃的扫描客户端，静默返回
            }

            // 执行虚拟设备注入
            virtualDeviceInjector.injectDevices(
                instance,
                scanManager,
                scannerMap,
                scanQueue
            )

        } catch (e: Throwable) {
            Logger.Hook.e(TAG, "Error in injectVirtualDevices", e)
        }
    }

    /**
     * 安全获取对象字段，不存在时返回null而非抛异常
     */
    private fun getFieldSafe(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        return try {
            XposedHelpers.getObjectField(obj, fieldName)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 安全调用方法，不存在时返回null而非抛异常
     */
    private fun callMethodSafe(obj: Any, methodName: String, vararg args: Any?): Any? {
        return try {
            XposedHelpers.callMethod(obj, methodName, *args)
        } catch (e: Throwable) {
            null
        }
    }
}
