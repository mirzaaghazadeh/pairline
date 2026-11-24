package app.pairline.android

import android.app.Application
import app.pairline.android.audio.CallAudioRelay
import app.pairline.android.files.FileNotifications
import app.pairline.android.files.FileTransfers
import app.pairline.android.link.IdentityStore
import app.pairline.android.netshare.NetShare
import app.pairline.android.privileged.PrivilegedManager
import app.pairline.android.privileged.TalkReminder
import app.pairline.android.link.LinkManager
import app.pairline.android.link.MessageRouter
import app.pairline.android.link.PairingManager
import app.pairline.android.link.PrefsPeerKeyStore
import app.pairline.android.link.wifi.BridgeHotspot
import app.pairline.android.telephony.BlockList
import app.pairline.android.telephony.CallTracker
import app.pairline.android.telephony.ContactsSync
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
    val link: LinkManager by lazy { LinkManager({ identity.identity() }, pairing, peers, prefs) }

    /** The bridge's own hotspot for the iPhone (M5, user-started). */
    val hotspot by lazy { BridgeHotspot(app) }

    /** File transfers over the Wi-Fi link (M5). */
    val files by lazy { FileTransfers(app, link).also { it.notifications = FileNotifications(app) } }

    val sims: SimDirectory by lazy { SimDirectory(app) }

    /** Numbers whose texts and calls don't reach the iPhone (D-043). */
    val blockList: BlockList by lazy { BlockList(app) }
    val smsStore: SmsStore by lazy { SmsStore(app, sims, blockList::isBlocked) }
    val smsSender: SmsSender by lazy { SmsSender(app, sims, smsStore, link::send) }
    val calls: CallTracker by lazy {
        CallTracker(app, sims, prefs, link::send) { router.statusChanged() }.also { tracker ->
            tracker.onCallEnded = { id -> audioRelay.stop(id, "call ended") }
            tracker.isBlocked = blockList::isBlocked
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
        )
    }
    val router: MessageRouter by lazy {
        MessageRouter(link, prefs, status, smsStore, smsSender, calls, contacts, ussd, audioRelay, netShare, files, blockList)
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
        CallAudioRelay(privileged, prefs, link::send, audioMode = { audio.mode })
            .also { relay -> relay.onCapsChanged = { router.statusChanged() } }
    }

    /** The daily GitHub update check and the verified update (D-042). */
    val updates: UpdateManager by lazy { UpdateManager(app) }
}
