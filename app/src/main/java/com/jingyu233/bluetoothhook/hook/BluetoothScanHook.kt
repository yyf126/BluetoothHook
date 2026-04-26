package com.jingyu233.bluetoothhook.hook

import com.jingyu233.bluetoothhook.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * 蓝牙扫描Hook核心类
 * 负责拦截ScanController.onScanResultInternal并注入虚拟设备
 */
class BluetoothScanHook(
    private val classLoader: ClassLoader,
    private val prefs: XSharedPreferences
) {
    companion object {
        private val TAG = Logger.Tags.HOOK_SCANNER
        private const val METHOD_ON_SCAN_RESULT_INTERNAL = "onScanResultInternal"

        // 不同Android版本/OEM可能使用不同的类名
        private val CANDIDATE_CLASSES = arrayOf(
            "com.android.bluetooth.le_scan.ScanController",
            "com.android.bluetooth.le_scan.TransitionalScanHelper",
            "com.android.bluetooth.gatt.ScanManager"
        )
    }

    private lateinit var scanResultBuilder: ScanResultBuilder
    private lateinit var virtualDeviceInjector: VirtualDeviceInjector

    fun init() {
        try {
            Logger.Hook.i(TAG, "Initializing BluetoothScanHook")

            // 初始化辅助工具
            scanResultBuilder = ScanResultBuilder(classLoader)
            virtualDeviceInjector = VirtualDeviceInjector(scanResultBuilder, prefs)

            // Hook主要方法（尝试多个类名和方法签名）
            val hooked = hookScanResultInternal()

            if (hooked) {
                Logger.Hook.i(TAG, "Successfully hooked onScanResultInternal")
            } else {
                Logger.Hook.e(TAG, "Failed to hook scan methods - no matching class/method found", null)
            }

        } catch (e: Throwable) {
            Logger.Hook.e(TAG, "Failed to initialize BluetoothScanHook", e)
        }
    }

    /**
     * Hook onScanResultInternal方法
     * 使用反射在运行时发现方法，兼容所有Android版本和OEM定制
     */
    private fun hookScanResultInternal(): Boolean {
        for (className in CANDIDATE_CLASSES) {
            val clazz = try {
                XposedHelpers.findClass(className, classLoader)
            } catch (e: Throwable) {
                Logger.Hook.i(TAG, "Class not found: $className, trying next...")
                continue
            }

            // 通过反射查找所有名为onScanResultInternal的方法
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
     * 在真实扫描结果处理完后调用
     * 兼容不同Android版本的类结构
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

            // 获取ScanManager：可能是字段(mScanManager)，也可能this本身就是ScanManager
            val scanManager = getFieldSafe(instance, "mScanManager")
                ?: getFieldSafe(instance, "mScanHelper")
                ?: instance // 如果都没有，this本身可能就是ScanManager

            // 获取ScannerMap：尝试多个可能的字段名
            // TransitionalScanHelper: instance.mScannerMap
            // ScanController (latest): instance.mScanHelper.mScannerMap
            // ScanManager (older): scanManager.mScannerMap
            val scannerMap = getFieldSafe(instance, "mScannerMap")
                ?: getFieldSafe(scanManager, "mScannerMap")
                ?: getFieldSafe(getFieldSafe(instance, "mScanHelper"), "mScannerMap")
                ?: getFieldSafe(instance, "mAppScanStats")
            if (scannerMap == null) {
                Logger.Hook.w(TAG, "Cannot find scannerMap field, skipping injection")
                return
            }

            // 获取当前扫描队列：尝试多个可能的方法名
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
