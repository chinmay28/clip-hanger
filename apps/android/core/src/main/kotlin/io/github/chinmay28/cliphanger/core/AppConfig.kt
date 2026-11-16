package io.github.chinmay28.cliphanger.core

/**
 * Everything the person chose in Settings: which Clip Hanger server to open.
 *
 * There is one mode. The footage lives on the machine the cameras record to,
 * and the server has to run beside it — a phone holds neither the directory
 * nor the disks — so the app is always a thin client of that server.
 */
data class AppConfig(
    /** The server, as typed; [Origin.parse] reads it. Blank until set up. */
    val serverAddress: String = "",
) {
    val configured: Boolean get() = serverAddress.isNotBlank()

    /** The origin the web view opens, or a reason it has none. */
    fun origin(): Result<Origin> =
        if (configured) Origin.parse(serverAddress) else Result.failure(IllegalStateException("Enter your Clip Hanger server's address first."))
}
