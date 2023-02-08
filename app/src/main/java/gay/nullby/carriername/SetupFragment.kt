package gay.nullby.carriername

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import gay.nullby.carriername.databinding.FragmentSetupBinding
import rikka.shizuku.Shizuku

class SetupFragment : Fragment() {

    private var _binding: FragmentSetupBinding? = null

    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (Shizuku.getBinder() != null) {
            // Shizuku is already bound
            handleShizukuConnected()
        } else {
            Shizuku.addBinderReceivedListener {
                // Shizuku connects after some time
                handleShizukuConnected()
            }
        }

        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                findNavController().navigate(R.id.action_setup_to_target)
            }
        }

        binding.buttonFirst.setOnClickListener {
            Shizuku.requestPermission(0)
        }
    }

    private fun handleShizukuConnected() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            findNavController().navigate(R.id.action_setup_to_target)
        } else {
            binding.buttonFirst.isEnabled = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}