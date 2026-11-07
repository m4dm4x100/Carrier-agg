package in.carriercontrol

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Typeface

class MainActivity : Activity() {
    private lateinit var report: TextView
    private lateinit var toggle: Button
    private var telephony: TelephonyManager? = null
    private val prefs by lazy { getSharedPreferences("ca_state", MODE_PRIVATE) }
    private val permissionRequest = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p, p, p)
        }
        root.addView(TextView(this).apply {
            text = "Carrier Control"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "OnePlus Nord CE 3 • LTE carrier aggregation"
            textSize = 14f
        })
        report = TextView(this).apply {
            textSize = 15f
            setPadding(0, p, 0, p)
        }
        toggle = Button(this).apply {
            text = "Disable carrier aggregation"
            isEnabled = false
            setOnClickListener { changeCarrierAggregation() }
        }
        root.addView(report)
        root.addView(toggle)
        root.addView(Button(this).apply {
            text = "Refresh diagnostics"
            setOnClickListener { inspect() }
        })
        setContentView(ScrollView(this).apply { addView(root) })
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val wanted = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) inspect()
        else requestPermissions(missing.toTypedArray(), permissionRequest)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) inspect()
    }

    private fun selectedTelephonyManager(): TelephonyManager? {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        val base = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        return try {
            val id = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
            if (id >= 0) base.createForSubscriptionId(id) else base
        } catch (_: Exception) {
            base
        }
    }

    private fun inspect() {
        val tm = selectedTelephonyManager()
        telephony = tm
        if (tm == null) {
            report.text = "Phone permission is required. Grant it and refresh."
            toggle.isEnabled = false
            return
        }

        val lines = mutableListOf<String>()
        lines += "Device: ${Build.MANUFACTURER} ${Build.MODEL}"
        lines += "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        lines += "Default data subscription ID: ${android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()}"
        lines += "Network type: ${networkName(safe { tm.dataNetworkType })}"
        lines += "Carrier privileges: ${safe { tm.hasCarrierPrivileges() } ?: "not available"}"

        if (Build.VERSION.SDK_INT >= 33) {
            lines += "Allowed-network-types API: available"
            lines += "LTE_CA bit: 0x${java.lang.Long.toHexString(TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA)}"
            val privileges = safe { tm.hasCarrierPrivileges() } == true
            if (privileges) {
                val mask = safe {
                    tm.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER)
                }
                lines += "Current user network mask: ${mask?.let { "0x" + java.lang.Long.toHexString(it) } ?: "read failed"}"
            } else {
                lines += "Current user network mask: protected (carrier privileges not granted)"
            }
        } else {
            lines += "Allowed-network-types API requires Android 13+"
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val cells = safe { tm.allCellInfo }
            lines += "Visible cell records: ${cells?.size ?: "unavailable"}"
            cells?.filter { it.isRegistered }?.forEach {
                lines += "Registered cell: ${it.javaClass.simpleName}"
            }
        } else {
            lines += "Cell details hidden: location permission not granted"
        }

        val canControl = Build.VERSION.SDK_INT >= 33 && safe { tm.hasCarrierPrivileges() } == true
        lines += ""
        lines += if (canControl) {
            "Control: carrier privilege detected. The app can attempt the Android network-policy API."
        } else {
            "Control: unavailable for this SIM/app. Android has not granted carrier privileges."
        }
        report.text = lines.joinToString("\n")
        toggle.isEnabled = canControl
        toggle.text = if (prefs.getBoolean("disabled", false)) {
            "Re-enable carrier aggregation"
        } else {
            "Disable carrier aggregation"
        }
    }

    private fun changeCarrierAggregation() {
        val tm = telephony ?: return
        if (Build.VERSION.SDK_INT < 33 || safe { tm.hasCarrierPrivileges() } != true) {
            report.text = report.text.toString() + "\n\nAndroid denied carrier-control access."
            toggle.isEnabled = false
            return
        }

        try {
            val reason = TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER
            val ca = TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA
            if (!prefs.getBoolean("disabled", false)) {
                val current = tm.getAllowedNetworkTypesForReason(reason)
                prefs.edit().putLong("saved_mask", current).apply()
                tm.setAllowedNetworkTypesForReason(reason, current and ca.inv())
                prefs.edit().putBoolean("disabled", true).apply()
            } else {
                if (!prefs.contains("saved_mask")) {
                    report.text = report.text.toString() + "\n\nNo saved network mask; refusing to guess."
                    return
                }
                tm.setAllowedNetworkTypesForReason(reason, prefs.getLong("saved_mask", 0L))
                prefs.edit().putBoolean("disabled", false).apply()
            }
            inspect()
        } catch (e: SecurityException) {
            report.text = report.text.toString() + "\n\nPermission denied by Android: ${e.message}"
        } catch (e: Exception) {
            report.text = report.text.toString() + "\n\nOperation failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun networkName(type: Int?): String = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_LTE_CA -> "LTE_CA (CA reported)"
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        TelephonyManager.NETWORK_TYPE_UNKNOWN, null -> "Unknown"
        else -> "Network type $type"
    }

    private fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }
}
