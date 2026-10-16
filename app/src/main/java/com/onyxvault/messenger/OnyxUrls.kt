package com.onyxvault.messenger

/**
 * ONYX VAULT - Zentrale URL-Konfiguration
 *
 * V1: Tor Hidden Service (Onion v3) auf Oracle Cloud als PRIMARY
 *   .onion: [REDACTED-ONION]
 *
 * GitHub Pages Clearnet-Mirror als FALLBACK (nur falls Tor nicht erreichbar).
 *   Repo:  https://github.com/supportonyxvault/onyxvault-website
 *   Pages: https://supportonyxvault.github.io/onyxvault-website/
 *
 * WICHTIG: Diese URLs sind user-sichtbar in:
 *  - RecoveryScreen (wenn App gesperrt - DER kritische Punkt!)
 *  - MainScreen (Service-Buttons oeffnen die URLs in-app via WebsiteScreen)
 *  - Share-Dialoge
 *
 * App verwendet Tor-Only Fetch via OrbotProxy (SOCKS5 127.0.0.1:9050).
 * Clearnet-Fallback NUR fuer User-Hinweis, nie fuer automatische Nutzung.
 */
object OnyxUrls {

    // === PRIMARY: TOR HIDDEN SERVICE ===
    const val ONION_HOST = "[REDACTED-ONION]"
    const val ONION_BASE = "http://$ONION_HOST"

    const val INDEX_URL      = "$ONION_BASE/"
    const val REGISTER_URL   = "$ONION_BASE/register.html"
    const val RECOVERY_URL   = "$ONION_BASE/recovery.html"
    const val KILLSWITCH_URL = "$ONION_BASE/killswitch.html"

    // === FALLBACK: GitHub Pages Clearnet (nur bei User-Konsens) ===
    const val CLEARNET_BASE  = "https://supportonyxvault.github.io/onyxvault-website"
    const val CLEARNET_INDEX = "$CLEARNET_BASE/"

    // Fuer Text-Hinweise (ohne Scheme damit Nutzer selbst in Tor Browser tippt)
    const val HUMAN_READABLE = ONION_HOST

    // Admin SSH Onion (nur fuer Entwickler/Admin, nicht in UI)
    // === SaveCard Trigger URLs (V1.1 Partnership-Erweiterung) ===
    const val SAVETRIGGER_ONION    = "$ONION_BASE/savetrigger.html"
    const val SAVETRIGGER_CLEARNET = "$CLEARNET_BASE/savetrigger.html"
    const val TOR_GATE_URL         = "$CLEARNET_BASE/tor-gate.html"
    const val SAVECARD_ONION       = "$ONION_BASE/savecard.html"
    const val SAVECARD_CLEARNET    = "$CLEARNET_BASE/savecard.html"

    // Admin SSH Onion (nur fuer Entwickler/Admin, nicht in UI)
    const val ADMIN_SSH_ONION = "[REDACTED-ONION]"
}
