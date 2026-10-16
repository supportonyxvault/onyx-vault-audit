# ONYX VAULT V1 — Security Audit Package

**Projekt:** ONYX VAULT V1 — Open Source Secure Messenger
**Snapshot:** 2026-10-16
**Zweck:** Externer Security-Audit (read-only, static analysis)

## Was ist ONYX VAULT

ONYX VAULT V1 ist ein Zero-Server Secure Messenger für Android. Kommunikation läuft Peer-to-Peer über Tor Hidden Services, Kill-Switch und Recovery nutzen signierte Nostr-Events (BIP-340 Schnorr).

Die App hostet keine zentralen Benutzerdaten. Die Website wird parallel über Tor (.onion) und Clearnet (GitHub Pages) ausgeliefert, enthält aber nur statische Seiten für Registrierung, Recovery, SaveCard-Generator und manuellen Killswitch.

## Was ist redacted

- `.onion` Hostnamen (56-char v3) → `[REDACTED-ONION]`
- Oracle/Hetzner Public IPs → `[REDACTED-*-IP]`
- Oracle Cloud OCIDs → `[REDACTED-OCID]`
- Email, Name, Adresse, VM-Hostnames → `[REDACTED-*]`

## Inhalt

### `app/`
Android App Source (Kotlin + Jetpack Compose). Keine build-Artefakte, keine APK, kein Keystore.

Wichtige Pfade:
- `app/src/main/java/com/onyxvault/messenger/` — alle Kotlin-Quellen
- `app/src/main/java/com/onyxvault/messenger/nostr/` — Nostr Signing + KillCommandListener + BIP-340 Verifier
- `app/src/main/java/com/onyxvault/messenger/savecard/SaveCardGenerator.kt` — Lokale SaveCard-PDF-Generation
- `app/src/main/java/com/onyxvault/messenger/crypto/` — AES-GCM, Argon2, Hardware-Keystore
- `app/src/main/AndroidManifest.xml` — Permissions, exported Components

### `website/`
Static-HTML Website (deployed auf GitHub Pages + Oracle Tor Hidden Service).

Kritische Dateien für Krypto-Review:
- `website/hermes.js` — Client-side BIP-340 Nostr-Signatur, AES-GCM
- `website/savecard.html` + `savecard.js` — QR-Gen + jsPDF, AES-GCM
- `website/savetrigger.html` + `savetrigger.js` — QR-Scan + Entschlüsselung + Nostr-Publish
- `website/killswitch.html` + `killswitch.js` — Manueller Killswitch via Master-QR
- `website/recovery.html` + `recovery.js` — PIN-Reset-Flow
- `website/register.html` + `register.js` — Reg→Master QR Transformation
- `website/tor-gate.html` + `tor-gate.js` — Tor-Browser-Detection + Fallback
- `website/vendor/` — lokal gehostete JS-Libraries (jsPDF, pdf.js) mit SRI-Hashes

### `oracle/`
Oracle Cloud Infrastructure (VM.Standard.E2.1.Micro, EU-FRANKFURT-1).
- `oracle/relay_publish_server.js` — Node.js Relay-Proxy (Fanout .onion + Clearnet)
- `oracle/nginx_combined.conf` — Reverse Proxy Config, Security Headers, CSP
- `oracle/onyx-relay.service` — systemd Unit
- `oracle/torrc` — Tor Config (Hidden Service v3)
- `oracle/onyx-hidden-service-setup.sh` — Setup-Script

## Architektur-Zusammenfassung

### Threat Model
Angreifer-Fähigkeiten:
- Netzwerk-MITM (Tor schützt Metadaten)
- Kompromittierter Website-Server (sieht nur verschlüsselte Blobs)
- Phone-Theft (Hardware-Keystore + Argon2 PIN)
- SaveCard-Besitz (QR1+QR2 reichen für Killswitch — by design!)
- Böswillige Nostr-Relay-Betreiber (sehen nur signed+encrypted Blobs)

### Krypto-Primitiven
- **Chats E2E:** ChaCha20-Poly1305 + X25519 + Kyber-1024 (PQ-sicher)
- **Nostr-Events:** BIP-340 Schnorr-Signaturen (secp256k1)
- **Master-QR:** AES-256-GCM mit SHA-256(passphrase) Key, 12-Byte IV
- **PIN:** Argon2id im Hardware-Keystore
- **SQLCipher:** AES-256 für lokale DB

### Nostr Kill-Event Flow
```
Partner scannt QR1 (URL mit Hash-Token)
  → savetrigger.html öffnet, Token aus location.hash gelesen, replaceState()
Partner scannt QR2 (AES-GCM Cipher)
  → Browser entschlüsselt mit SHA-256(Token)
  → Master-QR Payload freigelegt
  → Hermes.saveIdentity() lädt ks_priv
  → Hermes.sendKillCommand():
       kind=30078
       tags=[['p', ks_pub], ['t', 'onyx_kill'], ['d', ks_pub+'-'+kind]]
       content={target, type:'FULL_WIPE', timestamp, reason}
       sig = BIP-340(ks_priv, event_hash)
  → POST /relay-publish (CORS-whitelisted, rate-limited, signature-verified)
  → Server fanout zu 10+ Nostr-Relays (via Clearnet + Tor SOCKS → .onion)
App (Target):
  → KillCommandListener empfängt kind 30078
  → BIP-340 Signatur-Verifikation (Bip340Verifier.kt)
  → Canonical hash check: event.id == sha256(canonical NIP-01)
  → Filter: tags["p"] == my_ks_pub (secp256k1-abgeleitet)
  → Payload-check: target == my_ks_pub, type == FULL_WIPE
  → Replay-Protection: seenEventIds (persistiert) + created_at Toleranz
  → Lokaler Wipe (3× overwrite, NIST SP 800-88)
```

### Angriffsfläche
- **Oracle VM:** Nur Tor HS v3 öffentlich reachable (via nginx→Node); Admin-SSH über separate Tor HS. Keine 0.0.0.0 bindings.
- **GitHub Pages:** Statisch, nur IP leakt an GitHub beim Fetch.
- **Nostr Relays:** Sehen nur signed+encrypted Blobs.

## Was NICHT drin ist
- `*.apk` (separat für MobSF-Scan)
- `*.jks` / `*.keystore` / signing keys
- `oracle_key*` (SSH private keys)
- `.oci/config` (OCI API credentials)
- `.git/` History
- `build/` / `.gradle/` Caches

## Audit-Scope
Siehe `SCOPE.txt` für die konkrete Prioritätsliste und Threat-Model-Details.
