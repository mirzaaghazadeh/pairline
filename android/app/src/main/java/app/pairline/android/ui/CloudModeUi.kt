package app.pairline.android.ui

import app.pairline.android.BuildConfig

/**
 * Where the Android screens mention Cloud mode ("remote mode" in the code, D-092). Cloud mode is turned on from the
 * iPhone, which gives this phone its iCloud access, and only debug iPhone builds offer it so far: a release bridge
 * paired with an App Store iPhone mustn't send its user looking for a switch that isn't there.
 */
object CloudModeUi {
    /** Whether to suggest turning Cloud mode on: debug builds, or a paired iPhone whose `device.info` said `proto` 1+. */
    fun offered(peerProto: Int): Boolean = BuildConfig.DEBUG || peerProto >= 1

    /**
     * Whether the pairing screen offers pairing over the internet: in every build since TestFlight iPhones make
     * invitations for the public database in CloudKit Production, which release builds accept (RemoteConfig.accepts,
     * D-092); before that only debug builds could use one.
     */
    val pairingOverInternet: Boolean get() = true
}
