package com.novpn.splitdroid

/**
 * Default packages for auto-pause lists.
 * Bypass = kick VPN on enter. VpnNeeded = restore VPN on enter (not on Home).
 */
object AppRouteDefaults {
    val bypassPackages: Set<String> = RussianPackages.packages.toSet()

    val vpnNeededPackages: Set<String> = setOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.android.chrome",
        "com.instagram.android",
        "com.discord",
        "com.whatsapp",
        "com.twitter.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.aweme"
    )
}
