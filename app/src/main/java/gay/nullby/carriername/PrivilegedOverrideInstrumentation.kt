package gay.nullby.carriername

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.PersistableBundle
import android.os.Process
import android.telephony.CarrierConfigManager
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.InvocationTargetException

/**
 * Instrumentation running in the app process that applies CarrierConfig overrides
 * using shell permission identity. Pattern from eigenlux-ai/roamer.
 *
 * Android 17 blocks ICarrierConfigLoader.overrideConfig() from shell UID directly,
 * but CarrierConfigManager.overrideConfig() works when the app UID has delegated
 * shell permissions via startDelegateShellPermissionIdentity.
 */
class PrivilegedOverrideInstrumentation : Instrumentation() {

    private companion object {
        const val TAG = "CarrierPriv"
        val KEY_ISO = CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING
        val KEY_NAME_BOOL = CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL
        val KEY_NAME = CarrierConfigManager.KEY_CARRIER_NAME_STRING
        val KEY_FORCE_HOME = CarrierConfigManager.KEY_FORCE_HOME_NETWORK_BOOL
    }

    init {
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")
    }

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        Thread { runPrivileged(arguments) }.start()
    }

    private fun runPrivileged(arguments: Bundle) {
        val result = Bundle()
        var error: Throwable? = null
        try {
            val action = arguments.getString("action")?.takeIf { it.isNotBlank() } ?: "set"
            val subId = arguments.getString("subId")?.toIntOrNull() ?: -1
            require(subId >= 0) { "invalid subId" }

            val ctx = waitForTargetContext()
            waitForShizukuBinder()
            val mgr = ctx.getSystemService(Context.CARRIER_CONFIG_SERVICE) as CarrierConfigManager

            withShellPermissionIdentity {
                applyAction(action, subId, mgr, ctx, arguments)
            }
        } catch (t: Throwable) {
            error = t.unwrap()
        }

        if (error == null) {
            result.putBoolean("result", true)
        } else {
            Log.e(TAG, "failed", error)
            result.putBoolean("result", false)
            result.putString("error", error.message ?: error.javaClass.simpleName)
        }
        finish(if (error == null) 0 else 1, result)
    }

    private fun applyAction(
        action: String,
        subId: Int,
        mgr: CarrierConfigManager,
        ctx: Context,
        arguments: Bundle,
    ) {
        when (action) {
            "set" -> {
                val bundle = PersistableBundle()
                arguments.getString("iso")?.takeIf { it.isNotBlank() }
                    ?.let { bundle.putString(KEY_ISO, it.lowercase()) }
                arguments.getString("name")?.takeIf { it.isNotBlank() }?.let {
                    bundle.putBoolean(KEY_NAME_BOOL, true)
                    bundle.putString(KEY_NAME, it)
                    bundle.putBoolean(KEY_FORCE_HOME, true)
                }
                require(!bundle.isEmpty) { "set has no valid fields" }
                overrideConfig(mgr, subId, bundle)
                Log.i(TAG, "SET subId=$subId OK")
            }

            "reset" -> {
                overrideConfig(mgr, subId, null)
                Log.i(TAG, "RESET subId=$subId OK")
            }

            else -> throw IllegalArgumentException("unknown action=$action")
        }
    }

    private fun withShellPermissionIdentity(block: () -> Unit) {
        val am = activityManager()
        try {
            try {
                am.javaClass.getMethod(
                    "startDelegateShellPermissionIdentity",
                    Int::class.javaPrimitiveType,
                    Array<String>::class.java,
                ).invoke(am, Process.myUid(), null)
            } catch (e: InvocationTargetException) {
                throw e.targetException ?: e
            }
            block()
        } finally {
            runCatching {
                am.javaClass.getMethod("stopDelegateShellPermissionIdentity").invoke(am)
            }
        }
    }

    private fun activityManager(): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, Context.ACTIVITY_SERVICE) as IBinder
        return Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(binder))!!
    }

    /**
     * Call CarrierConfigManager.overrideConfig() via reflection.
     * On SecurityException (override=true rejected), retries with override=false.
     */
    private fun overrideConfig(mgr: CarrierConfigManager, subId: Int, bundle: PersistableBundle?) {
        val m = mgr.javaClass.getMethod(
            "overrideConfig",
            Int::class.javaPrimitiveType,
            PersistableBundle::class.java,
            Boolean::class.javaPrimitiveType,
        )
        try {
            m.invoke(mgr, subId, bundle, true)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            if (cause is SecurityException) {
                try {
                    m.invoke(mgr, subId, bundle, false)
                } catch (e2: InvocationTargetException) {
                    throw e2.targetException ?: e2
                }
            } else {
                throw cause
            }
        }
    }

    private fun waitForTargetContext(timeoutMs: Long = 8000L): Context {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            targetContext?.let { return it }
            Thread.sleep(50)
        }
        throw IllegalStateException("targetContext wait timed out")
    }

    private fun waitForShizukuBinder(timeoutMs: Long = 8000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
            Thread.sleep(50)
        }
        throw IllegalStateException("Shizuku not ready")
    }

    private fun Throwable.unwrap(): Throwable =
        (this as? InvocationTargetException)?.targetException ?: this
}
