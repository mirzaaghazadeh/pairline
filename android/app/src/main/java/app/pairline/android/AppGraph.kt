package app.pairline.android

import android.app.Application
import app.pairline.android.audio.CallAudioRelay
import app.pairline.android.audio.MediaChannelTransport
import app.pairline.android.files.FileNotifications
import app.pairline.android.files.FileTransfers
import app.pairline.android.files.PhotoLibrary
import app.pairline.android.link.IdentityStore
import app.pairline.android.netshare.NetShare
import app.pairline.android.netshare.NetSwitches
import app.pairline.android.tools.Ringer
import app.pairline.android.privileged.PrivilegedManager
import app.pairline.android.privileged.TalkReminder
import app.pairline.android.link.LinkManager
import app.pairline.android.link.MessageRouter
import app.pairline.android.link.PairingManager
import app.pairline.android.link.PrefsPeerKeyStore
import app.pairline.android.link.remote.DirectLink
import app.pairline.android.link.remote.DirectListener
import app.pairline.android.link.remote.PrefsKeyValueStore
import app.pairline.android.link.remote.RemoteConfig
import app.pairline.android.link.remote.RemoteLink
import app.pairline.android.link.remote.RemotePairingFlow
import app.pairline.android.link.remote.RemoteStore
import app.pairline.android.link.wifi.BridgeHotspot
import app.pairline.android.log.Log
import app.pairline.android.telephony.BlockList
import app.pairline.android.telephony.CallTracker
import app.pairline.android.telephony.ContactsSync
import app.pairline.android.telephony.DataUsage
import app.pairline.android.telephony.MmsSender
import app.pairline.android.telephony.MmsStore
import app.pairline.android.telephony.ScheduledSms
import app.pairline.android.telephony.SimDirectory
import app.pairline.android.telephony.SmsSender
import app.pairline.android.telephony.SmsStore
import app.pairline.android.telephony.StatusMonitor
import app.pairline.android.telephony.UssdRunner
import app.pairline.android.update.UpdateManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Hand-written service locator: one instance of each component per process (D-023: no DI library). */
object AppGraph {
    lateinit var app: Application
        private set

    fun init(application: Application) {
        app = application
    }

    /** Short background jobs from broadcast receivers. */
    val io: ExecutorService by lazy { Executors.newCachedThreadPool { r -> Thread(r, "pairline-io") } }

    val prefs by lazy { AppPrefs(app) }
    val identity by lazy { IdentityStore(app) }
    val peers by lazy { PrefsPeerKeyStore(app) }
    val pairing by lazy {
        PairingManager(
            deviceId = { identity.deviceId },
            identityPublicKey = { identity.identity().publicKey },
            peers = peers,
            wifiHint = { link.state.value.wifiHint },
        )
    }
    val link: LinkManager by lazy {
        LinkManager({ identity.identity() }, pairing, peers, prefs).also { it.remote = remote }
    }

    /** Remote mode (D-092): the mailbox in the user's own iCloud, the path next to the sessions. */
    val remote: RemoteLink by lazy {
        RemoteLink(
            RemoteStore.create(app),
            identity = { identity.identity() },
            peerKey = peers::pinned,
            lastSeen = { prefs.lastSeen },
            deliver = { link.deliverFromMailbox(it) },
        )
    }

    /**
     * Remote mode's direct connection through the home router (D-092): a public listener whose sessions join
     * [link] on their own lane, and the router's port mapping. Its `remote.addr` / `remote.state` go out like events.
     */
    val direct: DirectLink by lazy {
        DirectLink(DirectListener(link), link::send, PrefsKeyValueStore(app, "pairline.direct"))
    }

    /** Pairing from far away through an invitation (D-092). */
    val remotePairing: RemotePairingFlow by lazy {
        RemotePairingFlow(
            object : RemotePairingFlow.Host {
                override fun identity() = identity.identity()
                override fun deviceId() = identity.deviceId
                override fun pin(remoteKey: ByteArray) = link.pairedRemotely(remoteKey)
                override fun complete(config: RemoteConfig, token: String) = remote.configure(config, token)
                override fun abandon() = link.unpairLocally()
            },
        )
    }

    /** The bridge's own hotspot for the iPhone (M5, user-started). */
    val hotspot by lazy { BridgeHotspot(app) }

    /** File transfers over the Wi-Fi link (M5). */
    val files by lazy { FileTransfers(app, link).also { it.notifications = FileNotifications(app) } }

    val sims: SimDirectory by lazy { SimDirectory(app) }

    /** Numbers whose texts and calls don't reach the iPhone (D-043). */
    val blockList: BlockList by lazy { BlockList(app) }
    /** Picture and group messages (D-089). */
    val mmsStore: MmsStore by lazy { MmsStore(app, sims, blockList::isBlocked) }
    val mmsSender: MmsSender by lazy { MmsSender(app, sims, link::send) }
    val smsStore: SmsStore by lazy { SmsStore(app, sims, blockList::isBlocked, mmsStore) }
    val smsSender: SmsSender by lazy { SmsSender(app, sims, smsStore, link::send) }
    val calls: CallTracker by lazy {
        CallTracker(app, sims, prefs, link::send) { router.statusChanged() }.also { tracker ->
            tracker.onCallEnded = { id -> audioRelay.stop(id, "call ended") }
            tracker.isBlocked = blockList::isBlocked
            tracker.onIncomingRinging = ::ringOnIphoneOnly
        }
    }
    val contacts: ContactsSync by lazy { ContactsSync(app, sims, blockList::isBlocked) }
    val ussd: UssdRunner by lazy { UssdRunner(app, sims) }
    val status: StatusMonitor by lazy {
        StatusMonitor(
            app,
            sims,
            prefs,
            BuildConfig.VERSION_NAME,
            inCallServiceAvailable = { calls.inCallServiceAvailable },
            mediaCaps = { audioRelay.canListen to audioRelay.canTalk },
            mediaLink = { audioRelay.canLink },
            net = { netSwitches.state().toJson() },
            netSwitch = { netSwitches.canSwitch },
            smsWrite = { privileged.canWriteSms() },
            ringer = { Ringer.mode(app) },
            dataUsage = { dataUsage.accessGranted() },
            photos = { photos.available() },
        )
    }

    /** This phone's photos for the iPhone, opt-in (D-088). */
    val photos: PhotoLibrary by lazy { PhotoLibrary(app, prefs, files) }

    /** Messages the iPhone asked to send later (D-084). */
    val scheduledSms: ScheduledSms by lazy { ScheduledSms(app, smsSender, link::send) }

    /** Mobile data used, for the iPhone's meter (D-084). */
    val dataUsage: DataUsage by lazy { DataUsage(app, sims) }
    val router: MessageRouter by lazy {
        MessageRouter(
            link, prefs, status, smsStore, smsSender, calls, contacts, ussd, audioRelay, netShare, files, blockList,
            netSwitches, app, scheduledSms, dataUsage, photos, mmsStore, mmsSender, remote,
            checkHome = { direct.check() },
        ).also { router ->
            remote.onConfigured = {
                router.remoteStarted()
                direct.announce()
            }
        }
    }

    /** This phone's hotspot, Bluetooth tethering, mobile data and data SIM, switched from the iPhone (D-073). */
    val netSwitches: NetSwitches by lazy {
        NetSwitches(app, privileged, sims, hotspot).also { it.onChange = { router.statusChanged() } }
    }

    /** Internet sharing for the paired iPhone (M8, D-048). */
    val netShare: NetShare by lazy {
        NetShare(app, prefs).also { share ->
            share.onChange = { router.netShareChanged() }
            share.peerAddress = { link.wifiPeerAddress }
        }
    }

    /** Pairline's helper with shell rights ("Talk on iPhone", D-046, D-054). Started by [PairlineApp]. */
    val privileged: PrivilegedManager by lazy {
        PrivilegedManager(app, prefs).also { manager ->
            manager.onChange = {
                router.statusChanged()
                when (manager.state.value) {
                    PrivilegedManager.State.READY -> {
                        TalkReminder.cancel(app)
                        // The user turned on Talk on iPhone: also let Android bind the companion call service, which
                        // gives the iPhone the keypad, mute and speaker (Android 12+).
                        // It also lets Pairline turn Wireless debugging back on by itself after a restart (D-059).
                        if (prefs.talkOnIphone) {
                            io.execute {
                                manager.allowOngoingCalls()
                                manager.allowSecureSettings()
                                // Messages read on the iPhone get marked read here too (D-074).
                                if (manager.allowWriteSms()) router.statusChanged()
                            }
                        }
                    }
                    // The helper is gone (usually a restart turned Wireless debugging off): tell the user once per
                    // boot how to get call audio back.
                    PrivilegedManager.State.NEEDS_WIRELESS_DEBUGGING ->
                        if (prefs.talkOnIphone && manager.paired && !prefs.bridgeStopped) TalkReminder.maybeShow(app)
                    else -> Unit
                }
            }
        }
    }

    /** Call audio between this phone and the iPhone (D-047). */
    val audioRelay: CallAudioRelay by lazy {
        val audio = app.getSystemService(android.media.AudioManager::class.java)
        CallAudioRelay(
            privileged,
            prefs,
            link::send,
            audioMode = { audio.mode },
            linkMedia = { token -> link.linkMedia(token)?.let(::MediaChannelTransport) },
        ).also { relay -> relay.onCapsChanged = { router.statusChanged() } }
    }

    /** The daily GitHub update check and the verified update (D-042). */
    val updates: UpdateManager by lazy { UpdateManager(app) }

    /**
     * "Ring only on the iPhone" (D-066, on by default): while the iPhone is linked, an incoming call that reaches it
     * doesn't also ring here. The helper silences the ringtone and vibration; Telecom silences only a ring that has
     * started, so it asks again as the ring starts. Without the helper (Talk on iPhone not set up) both phones ring.
     */
    private fun ringOnIphoneOnly() {
        if (!prefs.ringOnIphoneOnly || !link.state.value.connected) return
        if (privileged.state.value != PrivilegedManager.State.READY) return
        io.execute {
            for (wait in SILENCE_RETRIES_MS) {
                if (wait > 0) Thread.sleep(wait)
                privileged.silenceRinger()
            }
            Log.i("calls", "ringing only on the iPhone")
        }
    }

    private val SILENCE_RETRIES_MS = longArrayOf(0, 300, 900)
}
