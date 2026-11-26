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
     * Whether the pairing screen offers pairing over the internet. Release builds accept only invitations for
     * Apple's private database, which no iPhone build makes yet (D-092), so there it would be a dead end.
     */
    val pairingOverInternet: Boolean get() = BuildConfig.DEBUG
}
