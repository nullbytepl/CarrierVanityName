package gay.nullby.carriername

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyFrameworkInitializer
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
import com.android.internal.R.attr.country
import com.android.internal.telephony.ICarrierConfigLoader
import gay.nullby.carriername.databinding.FragmentTargetBinding
import rikka.shizuku.ShizukuBinderWrapper
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

        var _subId1: IntArray? = SubscriptionManager.getSubId(0);
        var _subId2: IntArray? = SubscriptionManager.getSubId(1);

        Log.d(TAG, "#onViewCreated(): subId1=$subId1 subId2=$subId2")

        if (_subId1 != null) {
            subId1 = _subId1[0]
            view.findViewById<RadioButton>(R.id.sub1_button).text = "Network 1 (carrier: ${getCarrierInfoBySubId(subId1)})"
        }
        if (_subId2 != null) {
            subId2 = _subId2[0]
            view.findViewById<RadioButton>(R.id.sub2_button).text = "Network 2 (carrier: ${getCarrierInfoBySubId(subId2)})"
        }

        if (subId2 == -1) {
            view.findViewById<View>(R.id.sub2_button).visibility = View.GONE
        }

        view.findViewById<Button>(R.id.button_set).setOnClickListener { onSetName(view.findViewById<EditText>(R.id.text_entry).text.toString(), view.findViewById<EditText>(R.id.iso_region_input).text.toString()) }

        view.findViewById<Button>(R.id.button_reset).setOnClickListener {
            onResetName()
            view.findViewById<EditText>(R.id.text_entry).setText("")
            view.findViewById<EditText>(R.id.iso_region_input).setText("")
        }

        view.findViewById<RadioGroup>(R.id.sub_selection).setOnCheckedChangeListener { _, checkedId -> onSelectSub(checkedId) }

        onSelectSub(0)
    }

    private fun onSetName(text: String, isoRegion: String) {
        var p = PersistableBundle();
        if (isoRegion.isNotEmpty()) {
            if (isoRegion.length == 2) {
                p.putString(CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING, isoRegion.lowercase(
                    Locale.ROOT
                )
                )
            } else {
                Toast.makeText(context, "Invalid ISO region!", Toast.LENGTH_SHORT).show()
                return
            }
        }
        Toast.makeText(context, "Set carrier vanity name to \"$text\"", Toast.LENGTH_SHORT).show()

        p.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true)
        p.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING, text)
        p.putString(CarrierConfigManager.KEY_CARRIER_CONFIG_VERSION_STRING, /* trans rights! 🏳️‍⚧️*/ ":3")
        p.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true)

        val subId: Int;
        if (selectedSub == 1) {
            subId = subId1!!
        } else {
            subId = subId2!!
        }
        overrideCarrierConfig(subId, p)
    }

    private fun onResetName() {
        var p = PersistableBundle();
        p.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, false)
        p.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING, "")
        val subId: Int;
        if (selectedSub == 1) {
            subId = subId1!!
        } else {
            subId = subId2!!
        }
        // Sometimes just setting the override to null doesn't work, so let's first set another override, disabling the name change
        overrideCarrierConfig(subId, p)
        overrideCarrierConfig(subId, null)
    }

    private fun onSelectSub(id: Int) {
        if (id == R.id.sub1_button || id == 0) {
            selectedSub = 1;
            Toast.makeText(context, "Selected Network 1", Toast.LENGTH_SHORT).show()
        } else if (id == R.id.sub2_button) {
            selectedSub = 2;
            Toast.makeText(context, "Selected Network 2", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getCarrierInfoBySubId(subId: Int): String {
        val telephonyManager = context!!.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return ""
        val networkName = telephonyManager.getNetworkOperatorName(subId)
        val networkCountry = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SubscriptionManager.getSlotIndex(subId)
                .takeIf { it != SubscriptionManager.SIM_NOT_INSERTED }
                ?.let { telephonyManager.getNetworkCountryIso(it) }
        } else null) ?: "Unknown"
        val simCountry = TelephonyManager.getSimCountryIso(subId)
        return "$networkName - $simCountry"
    }

    private fun overrideCarrierConfig(subId: Int, p: PersistableBundle?) {
        val carrierConfigLoader = ICarrierConfigLoader.Stub.asInterface(
            ShizukuBinderWrapper(
                TelephonyFrameworkInitializer
                    .getTelephonyServiceManager()
                    .carrierConfigServiceRegisterer
                    .get()
            )
        )
        carrierConfigLoader.overrideConfig(subId, p, true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}