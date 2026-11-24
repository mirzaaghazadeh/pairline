// The privileged service: Pairline's only code that runs with shell rights, started through Shizuku
// (AGENTS.md rule 7, D-046). Every method is on this allowlist; nothing else can be asked of it.
package app.pairline.android.privileged;

interface IPrivilegedService {
    /** Shizuku's reserved transaction for stopping the service process. */
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
     * Starts capturing call audio from [source] (VOICE_DOWNLINK or VOICE_CALL) at [sampleRate] (8000, 16000 or
     * 48000). Returns the read end of a pipe carrying 16-bit little-endian mono PCM.
     */
    ParcelFileDescriptor startCapture(int source, int sampleRate) = 4;

    /**
     * Starts feeding audio into the call's uplink through the telephony output at [sampleRate]. Returns the write end
     * of a pipe the app writes 16-bit little-endian mono PCM into. With [muteMic] the bridge phone's own microphone
     * is muted until stopAudio().
     */
    ParcelFileDescriptor startInject(int sampleRate, boolean muteMic) = 5;

    /** Stops capture and injection and restores the microphone. */
    void stopAudio() = 6;
}
