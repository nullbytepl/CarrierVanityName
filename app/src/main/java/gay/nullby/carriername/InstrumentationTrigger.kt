package gay.nullby.carriername

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.InvocationTargetException

/**
 * Triggers PrivilegedOverrideInstrumentation via IActivityManager.startInstrumentation
 * through Shizuku with INSTR_FLAG_NO_RESTART to attach to the active process.
 * Pattern from eigenlux-ai/roamer.
 */
object InstrumentationTrigger {

    init {
        HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.addHiddenApiExemptions("I")
    }

    fun trigger(context: Context, extras: Map<String, String>) {
        val args = Bundle().apply { extras.forEach { (k, v) -> putString(k, v) } }

        val amBinder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, Context.ACTIVITY_SERVICE) as IBinder
        val am = Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(amBinder))

        val component = ComponentName(context, PrivilegedOverrideInstrumentation::class.java)
        val flags = instrFlag("INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS", 1 shl 1) or
            instrFlag("INSTR_FLAG_NO_RESTART", 1 shl 3)

        val watcherClass = Class.forName("android.app.IInstrumentationWatcher")
        val connectionClass = Class.forName("android.app.IUiAutomationConnection")
        val uiAutomationConnection = Class.forName("android.app.UiAutomationConnection")
            .getConstructor().newInstance()

        val method = am.javaClass.getMethod(
            "startInstrumentation",
            ComponentName::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Bundle::class.java,
            watcherClass,
            connectionClass,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        try {
            method.invoke(am, component, null, flags, args, null, uiAutomationConnection, 0, null)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun instrFlag(name: String, fallback: Int): Int = runCatching {
        ActivityManager::class.java.getField(name).getInt(null)
    }.getOrDefault(fallback)
}
