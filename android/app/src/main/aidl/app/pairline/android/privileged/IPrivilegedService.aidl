// The privileged service: Pairline's only code that runs with shell rights, in Pairline's own helper process
// (AGENTS.md rule 7, D-046, D-054). Every method is on this allowlist; nothing else can be asked of it.
package app.pairline.android.privileged;

interface IPrivilegedService {
    /** Stops the helper process. */
    void destroy() = 16777114;

    /** This interface's version, so the app can replace a service left over from an older install. */
    int version() = 1;

    /** JSON: Android version, which call-audio rights the shell user has, and whether a telephony output exists. */
    String probe() = 2;

    /**
     * Turns on the MANAGE_ONGOING_CALLS app-op for Pairline itself, so Android binds its companion call service
     * (keypad, mute and speaker from the iPhone). Refuses any other package.
     */
    boolean allowOngoingCalls(String packageName) = 3;

    /**
     * Starts capturing call audio from [source] (VOICE_DOWNLINK, VOICE_CALL, or PrivilegedService.SOURCE_INTERCEPT for
     * Android 13's call-audio interception) at [sampleRate] (8000, 16000 or 48000). Returns the read end of a pipe
     * carrying 16-bit little-endian mono PCM.
     */
    ParcelFileDescriptor startCapture(int source, int sampleRate) = 4;

    /**
     * Starts feeding audio into the call's uplink through the telephony output at [sampleRate]. Returns the write end
     * of a pipe the app writes 16-bit little-endian mono PCM into. With [muteMic] the bridge phone's own microphone
     * is muted until stopAudio().
     */
    ParcelFileDescriptor startInject(int sampleRate, boolean muteMic) = 5;

    /** Stops capture and injection and restores the microphone and the earpiece. */
    void stopAudio() = 6;

    /**
     * Grants ([allow]) or revokes WRITE_SECURE_SETTINGS for Pairline itself, so it can turn Developer options and
     * Wireless debugging back on when this helper is needed again (D-059). Refuses any other package.
     */
    boolean allowSecureSettings(String packageName, boolean allow) = 7;

    /** During a relayed call: silences ([on]) this phone's earpiece, or restores it. Also restored by stopAudio(). */
    boolean silenceEarpiece(boolean on) = 8;

    /** Silences the ringing call's ringtone and vibration on this phone; the call itself rings on (D-066). */
    boolean silenceRinger() = 9;

    /**
     * The user flipped a switch on the iPhone (D-073): starts ([on]) or stops Android's own tethering of [type]
     * (PrivilegedService.TETHER_WIFI, the phone's hotspot, or TETHER_BLUETOOTH), as Android's Hotspot & tethering
     * screen does. Returns 0 once Android started or stopped it, else Android's tethering error code
     * (PrivilegedService.TETHER_NO_ANSWER if it didn't answer in time).
     */
    int setTethering(int type, boolean on) = 10;

    /** The user's switch on the iPhone (D-073): mobile data on or off, on the data SIM. */
    boolean setMobileData(boolean on) = 11;

    /** The user's choice on the iPhone (D-073): the active SIM with subscription id [subId] becomes the data SIM. */
    boolean setDataSubscription(int subId) = 12;

    /**
     * Gives ([allow]) or returns Pairline's WRITE_SMS app-op, so a message read on the iPhone is marked read on this
     * phone too (D-074); otherwise only the default messaging app may write. Refuses any other package.
     */
    boolean allowWriteSms(String packageName, boolean allow) = 13;

    /**
     * Turns Bluetooth off and on again, only when the user turned on "Restart Bluetooth when stuck" and Android's
     * Bluetooth keeps failing to advertise (D-078). Returns whether Bluetooth is back on.
     */
    boolean restartBluetooth() = 14;
}
