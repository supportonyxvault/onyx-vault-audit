# ONYX VAULT

**Zero-Server · Zero-Trace · Zero-Compromise**

ONYX VAULT ist ein **dezentraler Peer-to-Peer-Messenger** für Android mit **Tor-Hidden-Service-Routing**, **Post-Quantum-Hybrid-Verschlüsselung**, **Hardware-Keystore-Isolation** und **forensischer RAM-Only-Speicherung**. Für Recovery und Fernlöschung (Kill Switch) nutzt die App das dezentrale **Nostr-Relay-Netzwerk** — ohne einen einzigen eigenen Server.

Dieses Repository enthält die **öffentliche Website** (GitHub Pages) für Registrierung, Recovery und Kill Switch. Die Website ist identisch mit den in-App-Assets und läuft komplett clientseitig.

- Live: **https://supportonyxvault.github.io/onyxvault-website/**
- Datenschutz: `/datenschutz.html` · Impressum: `/impressum.html`
- Lizenz: **AGPL-3.0** · © 2026 ONYX VAULT Project

---

## Inhalt

1. [Was ist ONYX VAULT?](#was-ist-onyx-vault)
2. [Architektur (Zero-Server)](#architektur-zero-server)
3. [App — Container-Modell](#app--container-modell)
4. [Registrierung & Master-QR](#registrierung--master-qr)
5. [Kontakt-QR & Schlüsselaustausch](#kontakt-qr--schlüsselaustausch)
6. [Chat-Bereich — Verschlüsselung & Selbstzerstörung](#chat-bereich--verschlüsselung--selbstzerstörung)
7. [Bunker-Fotos — Fragmentierung & EXIF-Entfernung](#bunker-fotos--fragmentierung--exif-entfernung)
8. [Forensisches Löschen (3-Pass + NIST SP 800-88)](#forensisches-löschen-3-pass--nist-sp-800-88)
9. [Recovery — Zugang wiederherstellen](#recovery--zugang-wiederherstellen)
10. [Kill Switch — Fernlöschung über Nostr](#kill-switch--fernlöschung-über-nostr)
11. [Wichtigkeit des Master-QR / Registrierungscodes](#wichtigkeit-des-master-qr--registrierungscodes)
12. [Roadmap — Erweiterter Kill Switch (Panic-PIN + Full Factory Reset)](#roadmap--erweiterter-kill-switch-panic-pin--full-factory-reset)
13. [Website-Technik & Deployment](#website-technik--deployment)
14. [Sicherheits-Hinweise](#sicherheits-hinweise)
15. [Lizenz & Verantwortung](#lizenz--verantwortung)

---

## Was ist ONYX VAULT?

Ein Messenger für Menschen, die **nicht einmal dem Betreiber vertrauen wollen** — weil es keinen gibt.

- **Journalist:innen** schützen Quellen gegen Beschlagnahmung.
- **Anwält:innen** halten das Mandantengeheimnis selbst bei verlorenem Handy.
- **Aktivist:innen & Dissident:innen** kommunizieren in Hochrisikokontexten.
- **Ärzt:innen** erfüllen DSGVO-Patientendatenschutz ohne zentrale Cloud.
- **Jede:r Privatperson**, die will, dass eine Nachricht in 60 Sekunden spurlos verschwindet.

Keine Telefonnummer. Keine E-Mail. Keine Anmeldung bei einer Firma. Nur eine lokal auf dem Gerät generierte **App-ID**.

---

## Architektur (Zero-Server)

ONYX VAULT betreibt **keinen zentralen Server**. Weder für Chats, noch für Recovery oder Kill Switch. Zwei dezentrale Netzwerke tragen die gesamte Kommunikation:

| Netzwerk | Zweck | Technik |
|---|---|---|
| **Tor Hidden Services v3** | Peer-to-Peer Chats zwischen Geräten | onion v3 (ed25519), obfs4-Bridges optional, lokaler Tor-Prozess (`org.torproject.jni`) |
| **Nostr Relays (wss://)** | Offline-Mailbox, Recovery-Signal, Kill-Switch-Signal | NIP-01 Events (Kind 30077–30081), BIP-340 Schnorr-Signaturen über secp256k1 |

**Was heißt das für dich?**

- Keine Firma kann abgeschaltet werden.
- Keine Datenbank kann beschlagnahmt werden.
- Kein zentraler Betreiber hat jemals Einblick in deine Daten.
- Wenn du den Kill-Switch auslöst, geht das Signal über **6 unabhängige Nostr-Relays** weltweit — mindestens einer wird dein Handy erreichen.

Verwendete Relays (konfigurierbar in-App):
```
wss://relay.damus.io
wss://nos.lol
wss://nostr.wine
wss://purplerelay.com
wss://relay.snort.social
wss://offchain.pub
```

---

## App — Container-Modell

Die App besteht aus **verschachtelten Containern**, jeder mit eigenem Schlüssel. Ein Angreifer, der einen Container knackt, hat nicht automatisch Zugriff auf den nächsten.

```
┌──────────────────────────────────────────────────────────┐
│  Hardware-Keystore  (Samsung Knox / Titan M2 / StrongBox)│
│  ├─ Master Private Key (ed25519)                         │
│  ├─ Nostr Keypair (BIP-340 Schnorr / secp256k1)          │
│  └─ Argon2id-Salt                                        │
└──────────────────────────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│  SQLCipher DB  (AES-256, PRAGMA key = Argon2id(PIN+Salt))│
│  ├─ Kontakte, Keys                                       │
│  ├─ Chat-Historie (nur Metadaten + persistente Keys)     │
│  └─ Settings, Audit-Log                                  │
└──────────────────────────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│  RAM-Only Chat Buffer  (verschwindet bei App-Exit)       │
│  ├─ Entschlüsselte Nachrichten                           │
│  ├─ Decrypted Fotos                                      │
│  └─ Session-Keys (Double Ratchet State)                  │
└──────────────────────────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│  Tor-SOCKS Tunnel  (127.0.0.1:9050)                      │
│  ├─ Alle Netzwerk-I/O geht hier durch                    │
│  └─ IP-Address niemals direkt sichtbar                   │
└──────────────────────────────────────────────────────────┘
```

### Zugangsschichten beim Entriegeln

1. **PIN** (4–8 Ziffern, benutzergewählt)
2. **Argon2id** härtet die PIN (`256 MB RAM, 3 Iterationen, 4 Threads`) → **DB-Key**
3. **SQLCipher** öffnet die DB mit diesem Key — bei falscher PIN **mathematisch unmöglich**, nichts zu entschlüsseln
4. Nach **5 Fehlversuchen** → 24h Lockout → Recovery via Master-QR oder forensischer Wipe

---

## Registrierung & Master-QR

Bei der ersten App-Installation:

1. **App-ID generieren** — 32 Byte random über `SecureRandom` → Base58 encode (24 Zeichen)
2. **Nostr-Keypair erzeugen** (secp256k1 private/public)
3. **PIN wählen** (lokal, verlässt niemals das Gerät)
4. **Hardware-Keystore einrichten** — AdaptiveKeystore erkennt automatisch Samsung Knox, Google Titan M2, oder StrongBox
5. **Master-QR exportieren**

### Was steht im Master-QR?

```
ONYX_MASTER:<app_id_base58>:<base64_payload>
```

Der `base64_payload` ist **AES-GCM-verschlüsselt** mit:
- **Key**: `SHA-256(app_id)` (32 Byte)
- **Nonce**: zufällige 12 Byte
- **Payload** (als JSON):
  ```json
  {
    "app_id": "<same as outer>",
    "nostr_sk": "<64-hex Schnorr private key>",
    "nostr_pk": "<64-hex x-only public key>",
    "ks_pub": "<64-hex kill-switch public key>",
    "created_at": 1760200000
  }
  ```

**Nur das Gerät mit derselben App-ID kann den QR entschlüsseln.** Die Website entschlüsselt lokal im Browser (Web Crypto API) — es wird nichts hochgeladen.

**⚠️ Verantwortung des Nutzers:**
- Der Master-QR ist **der einzige Weg** zur Fernsteuerung des eigenen Vaults.
- Druck ihn **auf Papier**, lege ihn an **zwei getrennten Orten** (Tresor, Vertrauensperson).
- **NIEMALS unverschlüsselt in Cloud-Diensten speichern** (Google Drive, iCloud, Dropbox usw. ohne eigene Zusatz-Verschlüsselung).
- **Verlust = kein Recovery, kein Kill Switch möglich**.

---

## Kontakt-QR & Schlüsselaustausch

Zwei Nutzer:innen fügen sich hinzu, indem sie einen **Kontakt-QR** scannen (idealerweise persönlich — Side-Channel-Verification).

### Kontakt-QR-Inhalt

```
ONYX_CONTACT:<peer_app_id>:<onion_addr>:<x25519_pub>:<kyber1024_pub>:<nostr_pub>
```

### Hybrid-Handshake (Signal-Standard + Post-Quantum)

1. **X25519 ECDH** — klassischer Diffie-Hellman über Curve25519
2. **Kyber-1024 KEM** — Post-Quantum Key Encapsulation (NIST ML-KEM Standard)
3. **HKDF-SHA256** kombiniert beide Shared Secrets → `root_key`
4. **Double Ratchet** startet mit `root_key` — ab jetzt bekommt **jede Nachricht einen neuen Schlüssel**

**Forward Secrecy**: Wird ein Message-Key kompromittiert, bleiben alle anderen sicher.
**Post-Compromise Security**: Nach einem Ratchet-Step heilt sich die Session selbst.
**Post-Quantum**: Selbst mit einem funktionierenden Quantencomputer wäre Kyber-1024 nicht brechbar.

---

## Chat-Bereich — Verschlüsselung & Selbstzerstörung

### Nachrichten-Verschlüsselung

Jede einzelne Chatnachricht:

```
ChaCha20-Poly1305(key=ratchet_step_i, nonce=i, aad=header)
```

- **ChaCha20** = Stream-Cipher, 256 Bit, resistent gegen Timing-Attacken
- **Poly1305** = MAC, verhindert Manipulation
- **Nonce rotiert pro Nachricht**
- **Message-Key wird nach Entschlüsselung sofort gelöscht**

### Selbstzerstörungs-Timer

Pro Chat wählbar:

| Timer | Zweck |
|---|---|
| **60 Sekunden** | Hochrisiko (Journalismus, Aktivismus) |
| **90 Sekunden** | Standard |
| **120 Sekunden** | Längere Lesephase |

**Nach Ablauf**:
1. Nachricht wird im RAM mit **Zufallsdaten überschrieben**
2. Message-Key wird **gelöscht**
3. UI-Entry verschwindet
4. **Nichts geht je auf die Festplatte** — nicht einmal in den Swap-Partition (`setSystemPropertiesForNoSwap()` in AndroidManifest)

### Peer-GONE Signal

Wird ein Chat vom Peer gelöscht, geht ein signiertes `GONE`-Event über Nostr an alle Kontakte dieser Session → ihre Apps **löschen den Verlauf automatisch**.

---

## Bunker-Fotos — Fragmentierung & EXIF-Entfernung

Fotos sind der häufigste Vektor für Datenverlust (EXIF leakt GPS, Kamera-Serial, Zeitstempel). ONYX VAULT macht sie forensisch unbrauchbar für Dritte:

1. **EXIF Strip** — alle Metadaten entfernt (`androidx.exifinterface`)
2. **Fragmentierung** — Bild wird in **32 KB Chunks** zerlegt
3. **Doppelte Verschlüsselung**:
   - Innen: ChaCha20 mit Chat-Key
   - Außen: AES-256-GCM mit Session-Nonce
4. **ZIP-Transport** über Tor-P2P
5. **Beim Empfänger** werden Chunks nur im RAM zusammengesetzt — **nie als temporäre Datei auf dem Dateisystem**

Nach dem Timer: 3-Pass-Overwrite der Chunks im RAM, dann GC-forced release.

---

## Forensisches Löschen (3-Pass + NIST SP 800-88)

Wenn ONYX VAULT etwas löscht, geht es **über das reine `delete()` hinaus**. Löschung = **forensische Vernichtung**.

Standard: **NIST SP 800-88 Rev. 1** (US-Richtlinie für Datenträger-Sanitation).

### 3-Pass-Overwrite

Jede zu löschende Datei:

```
Pass 1: Zufallsdaten  (CSPRNG über /dev/urandom)
Pass 2: 0x00 Nullen
Pass 3: 0xFF Muster
fsync() + delete()
```

Zusätzlich bei Kill-Switch:
- `FileChannel.truncate(0)` auf allen Chat-Dateien
- Secure Wipe der SQLCipher-DB
- Clear Hardware-Keystore slot (irreversibel)
- Löschung der Shared Preferences
- Zombie-Benachrichtigungen explizit canceln

**Auf SSD-Hardware** sind TRIM-Commands aktiv — das Löschen wird vom Flash-Controller auf Zellen-Ebene durchgesetzt, nicht nur im Dateisystem.

---

## Recovery — Zugang wiederherstellen

**Szenario**: Du hast deine PIN vergessen oder zu oft falsch eingegeben.

### Zwei Wege

#### Weg 1: In-App — Master-QR aus Galerie
1. In der Recovery-Oberfläche (öffnet sich automatisch nach 5 Fehlversuchen) → "Master-QR hochladen"
2. QR wird lokal entschlüsselt
3. Neue PIN wird gewählt
4. DB wird mit dem neuen PIN re-encrypted → **alle Chats, Kontakte, Onion-Adresse bleiben**

#### Weg 2: Website — Fernzugriff ohne Zugriff aufs Handy
1. Öffne https://supportonyxvault.github.io/onyxvault-website/recovery.html in einem beliebigen Browser
2. Lade deinen Master-QR hoch
3. Klick auf "Recovery jetzt senden (kostenlos)"
4. Dein Browser **signiert ein Nostr-Event** (Kind 30081) mit deinem privaten Schlüssel aus dem QR
5. Das Event geht an **6 Nostr-Relays**
6. Sobald dein Handy online ist, holt es das Event, **prüft die Signatur gegen den Hardware-Keystore**, und entsperrt sich
7. Alte Daten bleiben — nur **PIN wird neu vergeben**

### Was wird NICHT ersetzt
- Chat-Historie (bleibt)
- Kontaktliste (bleibt)
- Onion-Adresse (bleibt)
- Nostr-Keypair (bleibt)

### Was wird neu
- Master-PIN
- Fehlversuch-Counter (zurückgesetzt)

---

## Kill Switch — Fernlöschung über Nostr

**Szenario**: Handy verloren, gestohlen, Beschlagnahmung droht, Spyware erkannt.

### Ablauf

1. Öffne https://supportonyxvault.github.io/onyxvault-website/killswitch.html
2. Lade deinen Master-QR hoch
3. Klick auf "Kill Switch jetzt auslösen (kostenlos)"
4. Browser signiert ein Nostr-Event **Kind 30078** (`kind=kill`) mit deinem Schlüssel
5. Event geht an **alle 6 Nostr-Relays**
6. Sobald das Handy online geht:
   - **Peer-GONE** an alle Kontakte → ihre Apps löschen den Chat-Verlauf
   - **3-Pass-Overwrite** aller ONYX-Daten
   - **Forensische Vernichtung** der SQLCipher-DB
   - **Hardware-Keystore-Slot wird gewiped**
   - **App deinstalliert sich**

**Diese Aktion ist unumkehrbar.** Nach erfolgreicher Ausführung gibt es keinen Weg zurück — Vault ist weg wie wenn es nie da gewesen wäre.

### Was der Kill Switch (aktuell) NICHT macht
- Er wiped **nur ONYX-Daten**, nicht das ganze Handy.
- Fotos, andere Apps, Google-Account bleiben.

→ Siehe nächster Abschnitt: der **erweiterte Kill Switch** erweitert das zum **Full Factory Reset**.

---

## Wichtigkeit des Master-QR / Registrierungscodes

Der **Master-QR ist der zentrale kryptografische Anker**. Ohne ihn:

| Verlust-Szenario | Konsequenz ohne Master-QR |
|---|---|
| PIN vergessen | **Kein Recovery möglich** — Vault bleibt gesperrt, nach 24h Auto-Wipe |
| Handy verloren | **Kein Kill Switch möglich** — Finder/Dieb hätte theoretisch Zeit für Angriff auf PIN |
| Spyware vermutet | **Keine Fernlöschung** — Vault bleibt erreichbar für Angreifer |

**Empfehlung**:
1. **Drucke** den Master-QR beim ersten Setup auf Papier.
2. **Lege zwei Kopien** an getrennten Orten an (z. B. Hausbesitz-Tresor + Vertrauensperson).
3. **Fotografiere nicht** mit dem Handy, auf dem auch ONYX läuft — der Zusammenhang Handy ↔ QR wäre direkt auslesbar wenn das Handy gewiped wird.
4. **Verschlüssele digitale Kopien zusätzlich** mit VeraCrypt, Age oder GPG, bevor du sie irgendwo online legst.

---

## Roadmap — Erweiterter Kill Switch (Panic-PIN + Full Factory Reset)

Geplante Features für künftige Versionen:

### 🧨 Panic-PIN Lockscreen

**Vision**: Eine getarnte Android-Bildschirmsperre, die wie der normale System-Lockscreen aussieht. Bei **Entsperren mit einem vorher gewählten „Panic-PIN"** löst das Gerät eine **komplette Werkseinstellung** aus — nicht nur die ONYX-Daten, sondern **das gesamte Telefon**.

**Was im Nutzungsablauf passiert**:

1. In der App legst du einen zusätzlichen Panic-PIN fest (z. B. `9999`).
2. Die App installiert sich als **Device Owner** (ohne Root, über Android Device Administration API).
3. Der ONYX-Lockscreen wird aktiviert (opt-in).
4. **Normaler PIN** → ONYX öffnet sich normal.
5. **Panic-PIN** → ONYX zeigt kurz einen Fake-Fehler ("falsche PIN"), startet aber im Hintergrund:
   - Peer-GONE an alle Kontakte
   - 3-Pass-Overwrite der ONYX-Daten
   - **`DevicePolicyManager.wipeData(FLAG_WIPE_EXTERNAL_STORAGE | FLAG_WIPE_RESET_PROTECTION)`**
   - **Komplettes Android-Telefon wird auf Werkseinstellungen zurückgesetzt**
   - Reset-Protection wird mit entfernt → neuer Nutzer kann das Gerät sofort einrichten

**Zielgruppe**: Journalist:innen an Grenzübergängen, Aktivist:innen vor Razzien, Whistleblower mit akuter Bedrohung.

### 🔐 Smart Lock Integration

- Verknüpfung mit Androids Smart Lock (Bluetooth-Trust-Device, Trusted Location)
- Beispiel: Wenn das Handy **nicht mehr mit deiner Smartwatch gepaart ist UND außerhalb deiner Wohnung** → automatischer Panic-Mode-Timer startet, Kill Switch wird ausgelöst falls nicht binnen 60min deaktiviert.

### 🎭 Tarn-Lockscreen Varianten

- **Variante 1**: Sieht aus wie Android-System-Lockscreen (Default).
- **Variante 2**: Sieht aus wie Samsung-Secure-Folder-Prompt.
- **Variante 3**: Sieht aus wie das Lockscreen der (fake) Bank-App → ultra plausible deniability.

### 🧅 Dead-Man-Switch

- Wenn die App **X Tage** nicht entriegelt wird → automatischer Kill-Switch über die lokale Hardware-Clock-Signatur (kein Netz nötig).
- Konfigurierbar: 7 / 14 / 30 Tage.

### 🌐 Deployment auf F-Droid

- Reproducible Builds mit SHA-256 Verifikation.
- Keine Google-Play-Services-Abhängigkeit.
- GCM-Push-Fallback für Nostr-Signale wenn Tor nicht verfügbar.

---

### 🧅 Deep-Encryption aller Pfade (v37)

Aktuell sind `SecurePrefs` (EncryptedSharedPreferences, AES-256-GCM, Hardware-Keystore) und die SQLCipher-DB (AES-256) bereits verschlüsselt. Nicht geschützt sind: Diag-Logs, Tor Hidden-Service-Keys, Cache-Dateien, Teile von `filesDir`. v37 schließt alle diese Lücken:

- **DiagnosticLogger**: Jede Log-Zeile einzeln mit AES-GCM + random IV, Session-Key aus Hardware-Keystore
- **Tor Onion-Keys**: Beim ersten Start aus `app_TorService/hs/hs_ed25519_secret_key` lesen → verschlüsselt in SecurePrefs legen → Original 3-pass wipen → bei jedem Folgestart in tmpfs/RAM-Filesystem bereitstellen, nach Shutdown wipen
- **Cache + filesDir Audit**: Alles was nicht in SecurePrefs/SQLCipher liegt, in RAM-only ByteArray halten oder sofort nach Nutzung 3-pass wipen
- **QR-Code-Artefakte**: Reg-QR und Master-QR nur in-memory, Export mit User-Share-Intent, keine temporären Dateien in Cache

Ziel: **Auch bei Root, Cellebrite UFED oder Chip-Off-Analyse** darf kein Byte unverschlüsselter Nutzdaten auffindbar sein.

### 🔄 Recovery OHNE Datenverlust (v37)

v36 löscht beim Recovery die DB (weil ohne alte PIN nicht entschlüsselbar). v37 bringt einen zweiten Schlüsselpfad:

- Beim Setup wird ein **Recovery-Blob** erstellt: `userDbKey` AES-GCM-verschlüsselt mit `SHA-256(ks_priv + "ONYX_DB_RECOVERY")`, gespeichert in SecurePrefs
- Bei Recovery (Master-QR enthält `ks_priv`):
  1. Recovery-Blob entschlüsseln → **alter userDbKey**
  2. SQLCipher DB damit öffnen
  3. `PRAGMA rekey = stretchKeyWithPin(seed, neuePIN)`
  4. **Alle Chats, Kontakte und die Onion-Adresse bleiben erhalten.** Nur die PIN tauscht.
- Für v≤36 User: Beim ersten erfolgreichen Unlock in v37 wird der Blob automatisch nachträglich erstellt → ab dann recovery-fähig ohne Datenverlust

### 🧨 Erweiterter Kill-Switch — System-wide Factory Reset (v37+)

Der aktuelle Kill-Switch löscht nur die App-Daten. Der **erweiterte** Kill-Switch triggert zusätzlich eine **vollständige Werkseinstellung des gesamten Android-Geräts** — nicht nur ONYX VAULT.

**Zwei Trigger-Pfade:**

1. **Website / Nostr (Fernzugriff)** — wie heute, aber mit erweitertem Payload
   - Master-QR auf der Website hochladen → Kill-Switch senden mit `type: "FULL_DEVICE_WIPE"`
   - App bekommt Nostr-Event → führt `DevicePolicyManager.wipeData(FLAG_WIPE_EXTERNAL_STORAGE | FLAG_WIPE_RESET_PROTECTION)` aus
   - Komplettes Android zurück auf Werkseinstellungen + Factory-Reset-Protection weg

2. **Panic-PIN Lockscreen (lokal, am Gerät)** — getarnter Android-Lockscreen
   - Vorher gewählter zweiter PIN (z.B. `9999`) ersetzt den normalen Android-Lockscreen
   - Sieht aus wie **normale Bildschirmsperre**
   - Mit normaler PIN → ONYX öffnet sich normal
   - Mit **Panic-PIN** → kurzer Fake-Fehler, im Hintergrund:
     * Peer-GONE an alle Kontakte
     * 3-Pass-Overwrite aller ONYX-Daten (DB → QR-Artefakte → SharedPrefs → TorService-Dir → filesDir)
     * Hardware-Keystore `destroyAllKeys()`
     * `DevicePolicyManager.wipeData(…)` → **gesamtes Handy auf Werkseinstellungen**
     * Reset-Protection wird mit entfernt → Gerät sofort neu einrichtbar durch Dritte (keine Rück-Verfolgbarkeit zu dir)

**Zugangsvoraussetzung**: Der Master-QR ist der **einzige** Autorisierungsweg für den Fernzugriffs-Pfad. Ohne ihn gibt es weder Recovery noch Kill-Switch, auch keinen Full-Device-Wipe.

**Zielgruppe**: Journalist:innen an Grenzübergängen, Aktivist:innen vor Razzien, Whistleblower mit akuter Bedrohung.

### 📨 OnionPrivNote — Self-Destructing Secret Messages (v37+)

**Ohne App. Für jeden empfangbar. Zero-Server. 1 Klick. Dann weg.**

OnionPrivNote wird als zweite Produktlinie neben ONYX VAULT ins Ökosystem integriert — gleiche Sicherheitsstandards, aber für Empfänger die KEINE App brauchen.

**So funktioniert es:**

1. **Du** schreibst eine Nachricht in OnionPrivNote (als Website im Browser oder als Button in der ONYX VAULT App)
2. **Dein Browser** verschlüsselt die Nachricht clientseitig mit AES-256-GCM, Schlüssel landet im URL-Fragment (`#key=...`, wird NICHT an den Server geschickt)
3. **Verschlüsselter Blob** wird auf dem Nostr-Relay-Netzwerk abgelegt (NIP-33 replaceable, ks_pub als Identifier)
4. **Du bekommst einen Link** — kannst ihn per E-Mail, SMS, WhatsApp, Signal, Telegram oder jeder anderen Methode versenden; der Server sieht nur den verschlüsselten Blob, nicht den Schlüssel
5. **Empfänger öffnet Link** → sieht Button "Nachricht lesen"
6. **Klick** → Browser lädt Blob, entschlüsselt mit Schlüssel aus URL, zeigt Klartext an
7. **120 Sekunden-Countdown läuft** → nach Ablauf: **3-Pass-Overwrite** des RAM-Buffers + Blob auf Relay als gelöscht markiert + Seite zeigt "VERNICHTET"
8. **Replay unmöglich** — Reload des Links zeigt nur noch "Nachricht wurde bereits gelesen oder ist abgelaufen"

**Antwort-Funktion (optional):**
- User kann in den Settings aktivieren: "Antworten erlauben" → Empfänger sieht nach Lesen einen "Antworten"-Button → kann eine Antwort schreiben die an deine App ankommt (als Nostr-Event an deine ks_pub)
- Standard-Setting: AUS (nur Einweg-Versand)

**Deployment:**
- Eigenes GitHub-Pages-Repo `onionprivnote` unter dem gleichen `supportonyxvault` Account
- In der ONYX VAULT App: Button "Privnote senden" → öffnet OnionPrivNote-Website
- Browser-only Krypto, keine App-Installation beim Empfänger nötig
- Zero-Server, Zero-Tracking, keine Cookies

**Zielgruppe:** Journalist:innen die mit Nicht-App-Nutzern kommunizieren, Anwält:innen die Dokumente senden, Whistleblower die one-shot-Hinweise geben, jede:r der mal "nur mal eine Nachricht" sicher senden will ohne dass der Empfänger extra was installieren muss.

### 🧮 Killswitch Reihenfolge (verbindliche Spec v37)

Deine ausdrückliche Anforderung — **in genau dieser Reihenfolge**:

```
Phase 1: SQLCipher-DB (3× overwrite: 0x00 → 0xFF → CSPRNG → delete)
Phase 2: QR-Code-Artefakte in Cache/Downloads (3× overwrite → delete)
Phase 3: SharedPreferences (ks_keys, master_keys, app_id, db_seed) (3× overwrite → delete)
Phase 4: TorService-Dir (Onion-Keys, hostname) (3× overwrite → delete)
Phase 5: Rest filesDir + cacheDir (3× overwrite → delete)
Phase 6: Hardware-Keystore destroyAllKeys() ← ALS LETZTES
Phase 7: Process kill (exitProcess(0))
```

Begründung der Reihenfolge: Die DB braucht den Hardware-Key zum Lesen/Schreiben solange sie offen ist. Wenn man den Keystore zuerst zerstört, kann der Overwrite-Prozess auf halbwegs offenen Handles stolpern. **Erst Daten vernichten, dann die Keys.**

## Website-Technik & Deployment

### Dateien

| Datei | Zweck |
|---|---|
| `index.html` | Marketing-Übersicht, 8 Sicherheits-Schichten |
| `register.html` | QR-Code-Export, nur in-App genutzt (Browser als Backup) |
| `recovery.html` | Master-QR hochladen + Nostr-Recovery signieren |
| `killswitch.html` | Master-QR hochladen + Nostr-Kill signieren |
| `impressum.html` | §5 TMG / §18 MStV Pflichtangaben |
| `datenschutz.html` | DSGVO-konforme Datenschutzerklärung |
| `hermes.js` | Lokale AES-GCM-Entschlüsselung + BIP-340 Schnorr-Signatur + wss-Relay-Pool |
| `nostr.bundle.js` | nostr-tools (lokal gebündelt, keine CDN-Abhängigkeit) |
| `jsQR.min.js` | QR-Code-Dekodierung lokal im Browser |
| `qrcode.min.js` | QR-Code-Generation (nur register.html) |
| `style.css` | Dark-Teal-Theme |
| `logo.png` | Markenlogo |

### Build / Deploy

Die Website ist **statisches HTML** — keine Build-Pipeline, kein Framework.

```bash
# Lokal testen
cd onyxvault-website-github
python3 -m http.server 8080

# Deploy via Git
git add -A
git commit -m "Update"
git push origin main
# → GitHub Pages publishes automatisch (unter 60s)
```

### In-App Mirror

Dieselben Dateien liegen auch unter `app/src/main/assets/website/` der APK. Die App-WebView kann sie **offline** laden (z. B. wenn kein Netz verfügbar ist, aber der Kill-Switch gestartet werden soll — der Nutzer könnte den QR-Code manuell in ein Nostr-Event umwandeln lassen).

---

## Sicherheits-Hinweise

- **Keine externen Scripts** werden von der Website geladen (keine CDNs, kein Google Fonts, kein Analytics).
- **Keine Cookies, keine LocalStorage-Persistenz** von Keys — nur Session-Memory.
- **Alle Krypto** läuft über Web Crypto API (nativer Browser) oder lokal gebündelte Libs (`nostr.bundle.js`, `jsQR.min.js`).
- **Private Keys** werden nach Signatur **sofort aus dem RAM gelöscht** (`crypto.subtle` erlaubt zero-copy destruction).
- **Nostr-Events werden nach NIP-01** serialisiert — mit UTF-8-korrekter Byte-Reihenfolge (kein `\uXXXX`-Escape über 0x7F). Das war ein kritischer Bug in v33 der in v34 gefixt wurde.
- **BIP-340 Schnorr-Signaturen** (nicht ECDSA!) — Signatur wird vom Hardware-Keystore unterstützt wenn verfügbar, sonst BouncyCastle 1.80.

### Audit-Status (Stand Oktober 2026)

- ✅ NIP-01 Compliance verifiziert gegen nostr-tools 2.5+
- ✅ BIP-340 Schnorr vs. BouncyCastle `secp256k1` getestet
- ✅ SQLCipher 4.x Build-Chain
- ⏳ Externes Pentest-Audit noch nicht durchgeführt (geplant Q1 2027)
- ⏳ Reproducible Builds für F-Droid noch nicht enabled

---

## Lizenz & Verantwortung

**AGPL-3.0** © 2026 ONYX VAULT Project

Freie Software mit **starker Copyleft-Klausel**: Jede abgeleitete Arbeit, auch wenn sie als Netzwerkdienst angeboten wird, muss den Quellcode offenlegen.

### Verantwortung des Nutzers

- ONYX VAULT ist **kein Werkzeug für illegale Aktivitäten**. Die Software selbst ist eine zivile Software zur Wahrung des Rechts auf **Privatsphäre nach Artikel 8 der Charta der Grundrechte der EU** und **Artikel 10 des Grundgesetzes**.
- Nutzer sind für die rechtmäßige Verwendung selbst verantwortlich.
- Der Master-QR und die Kontakt-QRs sind **persönliche kryptografische Zugangsdaten**. Weitergabe erfolgt auf eigenes Risiko.
- Fernlöschung über Kill Switch ist **unumkehrbar** — jeder Nutzer bestätigt im Browser-Dialog, bevor das Signal gesendet wird.

### Haftungsausschluss

Diese Software wird **ohne Gewährleistung** bereitgestellt. Der Betreiber übernimmt keine Haftung für Datenverlust durch unsachgemäße Nutzung, verlorenen Master-QR oder versehentliche Kill-Switch-Auslösung.

---

**Kontakt**: [REDACTED-EMAIL]
**Website**: https://supportonyxvault.github.io/onyxvault-website/
**Impressum**: `/impressum.html` · **Datenschutz**: `/datenschutz.html`
