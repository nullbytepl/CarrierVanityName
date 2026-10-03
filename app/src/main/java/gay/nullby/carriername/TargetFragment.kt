package gay.nullby.carriername

import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.PersistableBundle
import android.os.Process
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import gay.nullby.carriername.databinding.FragmentTargetBinding
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.InvocationTargetException
import java.util.Locale

class TargetFragment : Fragment() {
    private val TAG: String = "TargetFragment"

    private var _binding: FragmentTargetBinding? = null

    private val binding get() = _binding!!

    private var subId1: Int = -1;
    private var subId2: Int = -1;

    private var selectedSub: Int = 1;

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentTargetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check/request phone state permissions needed to enumerate subscriptions
        val perms = arrayOf(
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_NUMBERS,
        )
        val missing = perms.filter {
            requireContext().checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1001)
            return
        }
        loadSubscriptions(view)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            view?.let { loadSubscriptions(it) }
        }
    }

    private fun loadSubscriptions(view: View) {
        // Use public SubscriptionManager API instead of hidden getSubId()
        val sm = requireContext().getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val subs = try { sm.activeSubscriptionInfoList.orEmpty() } catch (_: Exception) { emptyList() }

        Log.d(TAG, "#onViewCreated(): found ${subs.size} subscriptions")

        if (subs.isNotEmpty()) {
            subId1 = subs[0].subscriptionId
            view.findViewById<RadioButton>(R.id.sub1_button).text = "Network 1 (carrier: ${getCarrierNameBySubId(subId1)})"
        }
        if (subs.size >= 2) {
            subId2 = subs[1].subscriptionId
            view.findViewById<RadioButton>(R.id.sub2_button).text = "Network 2 (carrier: ${getCarrierNameBySubId(subId2)})"
        }

        if (subId2 == -1) {
            view.findViewById<View>(R.id.sub2_button).visibility = View.GONE
        }

        view.findViewById<Button>(R.id.button_set).setOnClickListener {
            onSetName(
                view.findViewById<EditText>(R.id.text_entry).text.toString(),
                view.findViewById<EditText>(R.id.iso_region_input).text.toString()
            )
        }

        view.findViewById<Button>(R.id.button_reset).setOnClickListener {
            onResetName()
            view.findViewById<EditText>(R.id.text_entry).setText("")
            view.findViewById<EditText>(R.id.iso_region_input).setText("")
        }

        view.findViewById<RadioGroup>(R.id.sub_selection).setOnCheckedChangeListener { _, checkedId -> onSelectSub(checkedId) }

        onSelectSub(0)
    }

    private fun onSetName(text: String, isoRegion: String) {
        val p = PersistableBundle()
        if (isoRegion.isNotEmpty()) {
            if (isoRegion.length == 2) {
                p.putString(CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING, isoRegion.lowercase(Locale.ROOT))
            } else {
                Toast.makeText(context, "Invalid ISO region!", Toast.LENGTH_SHORT).show()
                return
            }
        }
        Toast.makeText(context, "Set carrier vanity name to \"$text\"", Toast.LENGTH_SHORT).show()

        p.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true)
        p.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING, text)
        p.putString(CarrierConfigManager.KEY_CARRIER_CONFIG_VERSION_STRING, ":3")
        p.putBoolean(CarrierConfigManager.KEY_FORCE_HOME_NETWORK_BOOL, true)

        val subId = if (selectedSub == 1) subId1 else subId2
        if (subId == -1) {
            Toast.makeText(context, "No SIM subscription found", Toast.LENGTH_SHORT).show()
            return
        }
        overrideCarrierConfig(subId, p)
    }

    private fun onResetName() {
        val subId = if (selectedSub == 1) subId1 else subId2
        if (subId == -1) return

        try {
            InstrumentationTrigger.trigger(requireContext(), mapOf("action" to "reset", "subId" to subId.toString()))
            Toast.makeText(context, "Carrier config reset", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            val cause = generateSequence(t) { it.cause }.last()
            Log.e(TAG, "onResetName failed", cause)
            Toast.makeText(context, "Failed: ${cause.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onSelectSub(id: Int) {
        if (id == R.id.sub1_button || id == 0) {
            selectedSub = 1
        } else if (id == R.id.sub2_button) {
            selectedSub = 2
        }
    }

    /** Use public createForSubscriptionId() instead of hidden getNetworkOperatorName(subId). */
    private fun getCarrierNameBySubId(subId: Int): String {
        val tm = (context?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
            ?.createForSubscriptionId(subId) ?: return ""
        return tm.simOperatorName.orEmpty().ifBlank {
            try { tm.networkOperatorName.orEmpty() } catch (_: Exception) { "" }
        }
    }

    private fun getRealCountryIso(subId: Int): String {
        val tm = (context?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
            ?.createForSubscriptionId(subId) ?: return ""
        return tm.simCountryIso.orEmpty().lowercase(Locale.ROOT)
    }

    /**
     * Override carrier config via PrivilegedOverrideInstrumentation (roamer pattern).
     * Uses startInstrumentation through Shizuku to run in a shell-identity context,
     * then startDelegateShellPermissionIdentity + CarrierConfigManager.overrideConfig().
     * This avoids both "cannot be invoked by shell" and "requires MODIFY_PHONE_STATE" errors
     * on Android 17.
     */
    private fun overrideCarrierConfig(subId: Int, p: PersistableBundle?) {
        try {
            val extras = mutableMapOf("action" to "set", "subId" to subId.toString())
            if (p?.containsKey(CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING) == true) {
                p.getString(CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING)?.let {
                    extras["iso"] = it
                }
            }
            if (p?.containsKey(CarrierConfigManager.KEY_CARRIER_NAME_STRING) == true) {
                p.getString(CarrierConfigManager.KEY_CARRIER_NAME_STRING)?.let {
                    extras["name"] = it
                }
            }
            InstrumentationTrigger.trigger(requireContext(), extras)
            Toast.makeText(context, "Carrier config updated", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            val cause = generateSequence(t) { it.cause }.last()
            Log.e(TAG, "overrideCarrierConfig failed", cause)
            Toast.makeText(context, "Failed: ${cause.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
