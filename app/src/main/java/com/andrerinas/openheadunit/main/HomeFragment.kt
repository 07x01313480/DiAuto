package com.andrerinas.openheadunit.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.connection.CommManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/** The car's standby screen. DiAuto Lite connects over USB only. */
class HomeFragment : Fragment() {
    private val app get() = App.provide(requireContext())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<View>(R.id.wifi_button).setOnClickListener {
            if (app.commManager.isConnected) openProjection()
            else findNavController().navigate(R.id.action_homeFragment_to_usbListFragment)
        }
        view.findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        // Going back to the car does not tear down a connection or disable reconnect.
        view.findViewById<View>(R.id.exit_button).setOnClickListener { requireActivity().moveTaskToBack(true) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.commManager.connectionState.collect { state ->
                    val status = when (state) {
                        is CommManager.ConnectionState.Disconnected -> R.string.da_ready
                        is CommManager.ConnectionState.TransportStarted -> R.string.da_connected
                        else -> R.string.da_connecting
                    }
                    view.findViewById<TextView>(R.id.connection_status).setText(status)
                    view.findViewById<MaterialButton>(R.id.wifi_button).setText(
                        if (app.commManager.isConnected) R.string.da_open else R.string.da_connect)
                }
            }
        }
    }

    private fun openProjection() {
        startActivity(AapProjectionActivity.intent(requireContext()).apply {
            putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
        })
    }

    companion object {
        // Retained for existing automation entry points; self projection has no standby control.
        var forceSelfModeLaunch = false
        fun resetAutoStart() { forceSelfModeLaunch = false }
    }
}
