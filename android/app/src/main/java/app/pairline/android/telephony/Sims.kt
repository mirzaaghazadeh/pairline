package app.pairline.android.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import app.pairline.android.protocol.putNullable
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** One active SIM, as the iPhone sees it in `status.sims`. */
data class SimSnapshot(
    val slot: Int,
    val subId: Int,
    val carrier: String,
    val label: String,
    val number: String?,
    val signal: Int,
    val net: String,
    val roaming: Boolean,
    val defaultSms: Boolean,
    val defaultVoice: Boolean,
    /** Android's data SIM (D-073). */
    val defaultData: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("slot", slot)
        .put("carrier", carrier)
        .put("label", label)
        .putNullable("number", number)
        .put("signal", signal)
        .put("net", net)
        .put("roaming", roaming)
        .put("defaultSms", defaultSms)
        .put("defaultVoice", defaultVoice)
        .put("defaultData", defaultData)
}

/** Maps SIM slots (what the protocol uses) to subscription ids (what Android uses) and describes each SIM. */
class SimDirectory(private val context: Context) {
    private val subs = context.getSystemService(SubscriptionManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    /** Latest radio facts per subscription, fed by [StatusMonitor]'s telephony callbacks. */
    val serviceStates = ConcurrentHashMap<Int, Int>()
    val displayOverrides = ConcurrentHashMap<Int, Int>()

    @SuppressLint("MissingPermission")
    fun activeSubscriptions(): List<SubscriptionInfo> {
        if (!Perms.granted(context, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        return try {
            subs?.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun activeCount(): Int = activeSubscriptions().size

    /** The subscription for a slot; null slot means the default for [purpose]. */
    fun subIdFor(slot: Int?, purpose: Purpose): Int? {
        if (slot == null) {
            val id = when (purpose) {
                Purpose.SMS -> SubscriptionManager.getDefaultSmsSubscriptionId()
                Purpose.VOICE -> SubscriptionManager.getDefaultVoiceSubscriptionId()
            }
            if (id != SubscriptionManager.INVALID_SUBSCRIPTION_ID) return id
            return activeSubscriptions().firstOrNull()?.subscriptionId
                ?: SubscriptionManager.getDefaultSubscriptionId().takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        }
        return activeSubscriptions().firstOrNull { it.simSlotIndex == slot }?.subscriptionId
    }

    fun slotFor(subId: Int?): Int? {
        if (subId == null || subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID || subId < 0) return null
        return activeSubscriptions().firstOrNull { it.subscriptionId == subId }?.simSlotIndex
    }

    /** The Telecom phone account for a subscription, to place a call on a chosen SIM. */
    @SuppressLint("MissingPermission")
    fun phoneAccountFor(subId: Int): PhoneAccountHandle? {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return null
        if (!Perms.granted(context, Manifest.permission.READ_PHONE_STATE)) return null
        return try {
            telecom.callCapablePhoneAccounts.firstOrNull { handle -> subIdFor(handle) == subId }
        } catch (_: SecurityException) {
            null
        }
    }

    /** The SIM slot of a Telecom phone account, for calls reported by the InCallService. */
    @SuppressLint("MissingPermission") // a SecurityException is caught below
    fun slotForAccount(handle: PhoneAccountHandle?): Int? {
        if (handle == null) return null
        return try {
            slotFor(subIdFor(handle))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The subscription behind a Telecom phone account. Android 11 has an API for it; on Android 9 and 10 the
     * telephony phone account's id is the subscription id or the SIM's ICCID (it varies by maker), so match both.
     */
    @SuppressLint("MissingPermission")
    private fun subIdFor(handle: PhoneAccountHandle): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return telephony?.getSubscriptionId(handle)
        val id = handle.id?.takeIf { it.isNotBlank() } ?: return null
        return activeSubscriptions().firstOrNull { info ->
            if (info.subscriptionId.toString() == id) return@firstOrNull true
            @Suppress("DEPRECATION")
            val iccId = runCatching { info.iccId }.getOrNull()?.takeIf { it.isNotBlank() }
            iccId != null && (id.startsWith(iccId) || iccId.startsWith(id))
        }?.subscriptionId
    }

    fun snapshots(): List<SimSnapshot> {
        val defaultSms = SubscriptionManager.getDefaultSmsSubscriptionId()
        val defaultVoice = SubscriptionManager.getDefaultVoiceSubscriptionId()
        val defaultData = SubscriptionManager.getDefaultDataSubscriptionId()
        val list = activeSubscriptions()
        return list.map { info ->
            val tm = telephony?.createForSubscriptionId(info.subscriptionId)
            val inService = serviceStates[info.subscriptionId]?.let { it == ServiceState.STATE_IN_SERVICE }
            val signal = if (inService == false) 0 else (tm?.signalStrength?.level ?: 0).coerceIn(0, 4)
            SimSnapshot(
                slot = info.simSlotIndex,
                subId = info.subscriptionId,
                carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "",
                label = info.displayName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${info.simSlotIndex + 1}",
                number = numberOf(info),
                signal = signal,
                net = if (inService == false) "none" else netType(tm, info.subscriptionId),
                roaming = runCatching { tm?.isNetworkRoaming == true }.getOrDefault(false),
                defaultSms = info.subscriptionId == defaultSms || (list.size == 1),
                defaultVoice = info.subscriptionId == defaultVoice || (list.size == 1),
                defaultData = info.subscriptionId == defaultData || (list.size == 1),
            )
        }
    }

    fun simsJson(): JSONArray = JSONArray().apply { snapshots().forEach { put(it.toJson()) } }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun numberOf(info: SubscriptionInfo): String? {
        if (!Perms.granted(context, Manifest.permission.READ_PHONE_NUMBERS)) return null
        val n = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                subs?.getPhoneNumber(info.subscriptionId)
            } else {
                info.number
            }
        } catch (_: SecurityException) {
            null
        }
        return n?.takeIf { it.isNotBlank() }
    }

    @SuppressLint("MissingPermission")
    private fun netType(tm: TelephonyManager?, subId: Int): String {
        if (tm == null || !Perms.granted(context, Manifest.permission.READ_PHONE_STATE)) return "none"
        when (displayOverrides[subId]) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED,
            -> return "5G"
        }
        val type = try {
            tm.dataNetworkType.takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN } ?: tm.voiceNetworkType
        } catch (_: SecurityException) {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        }
        return networkGeneration(type)
    }

    enum class Purpose { SMS, VOICE }

    companion object {
        /** Maps Android's radio technology to the protocol's coarse generation. */
        @Suppress("DEPRECATION")
        fun networkGeneration(type: Int): String = when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN, 19 /* LTE_CA */ -> "LTE"
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            -> "3G"
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_IDEN, TelephonyManager.NETWORK_TYPE_GSM,
            -> "2G"
            else -> "none"
        }
    }
}
