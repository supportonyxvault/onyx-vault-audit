# ONYX VAULT — Oracle Cloud Hidden Service Setup

## Was du bisher getan hast
- ✅ Oracle Cloud Konto (eu-frankfurt-1)
- ✅ API-Config in `[DEFAULT].txt`
- ✅ SSH-Key auf Windows erstellt: `C:\Users\Windows XI\.ssh\onyx_oracle_key`

## Dein Public SSH Key (für Oracle VM-Erstellung)
```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOb0hpzbkl7rvlJbKfWZORlafZvdC7fdiZt0EeIvH/iB onyx-ssh-oracle
```

## Phase 1: VCN + VM erstellen in Oracle Console

### 1.1 VCN mit Internet Connectivity
Oracle Console → **Networking** → **Virtual Cloud Networks** → **Start VCN Wizard** → **Create VCN with Internet Connectivity**

Settings:
- **VCN Name:** `onyx-vcn`
- **CIDR Block:** `10.0.0.0/16` (default)
- Rest default lassen → **Next** → **Create**

Resultat: VCN mit Public Subnet, Internet Gateway, Route Tables.

### 1.2 Compute Instance (VM)
Oracle Console → **Compute** → **Instances** → **Create Instance**

Settings:
- **Name:** `[REDACTED-VM-HOSTNAME]`
- **Image:** Change Image → **Canonical Ubuntu 22.04 Minimal aarch64**
- **Shape:** Change Shape → **Specialty and previous generation** → **Ampere** → **VM.Standard.A1.Flex** → 1 OCPU / 6 GB RAM (Always Free)
- **Primary Network:** Default `onyx-vcn` + Public Subnet
- **Assign a public IPv4 address:** ✅ YES (temporär!)
- **SSH keys:** → **Paste public keys** → Public Key von oben einfügen

→ **Create** → warten bis Status = `RUNNING` (~1 Min)

### 1.3 Public IP notieren
Auf der Instance-Detailseite steht `Public IP Address: XX.XX.XX.XX` — kopieren!

## Phase 2: SSH + Setup-Script ausführen

Auf Windows (PowerShell):
```powershell
cd C:\Dev\ONYXVAULT\oracle-setup
# Script auf VM hochladen
scp -i "$env:USERPROFILE\.ssh\onyx_oracle_key" onyx-hidden-service-setup.sh ubuntu@<PUBLIC_IP>:~/
# SSH + Script ausführen
ssh -i "$env:USERPROFILE\.ssh\onyx_oracle_key" ubuntu@<PUBLIC_IP> "chmod +x onyx-hidden-service-setup.sh && ./onyx-hidden-service-setup.sh"
```

Das Script macht:
1. System-Update + install (nginx, tor, ufw, fail2ban, git)
2. Firewall härten
3. Nginx auf 127.0.0.1:8080 (nur localhost)
4. Website von GitHub clonen
5. Tor Hidden Service v3 (Web + SSH)
6. Hidden Service Keys Backup
7. Unattended Security Updates

**Dauer:** ca. 3-5 Minuten.

**Ausgabe am Ende:** 2 `.onion`-Adressen (Web + SSH).

## Phase 3: Testen via Tor-Browser

Tor-Browser auf deinem PC → `http://<ONION_WEB>.onion/` → sollte deine Killswitch-Seite zeigen.

## Phase 4: BACKUP der Hidden Service Keys (KRITISCH!)

Die `hs_ed25519_secret_key` Dateien sind der einzige Weg die `.onion`-Adresse zu reproduzieren wenn die VM weg ist.

```powershell
scp -i "$env:USERPROFILE\.ssh\onyx_oracle_key" -r ubuntu@<PUBLIC_IP>:~/onyx-hs-backup-* C:\Dev\ONYXVAULT\oracle-setup\backup\
```

Dann **verschlüsseln und offline sichern** (USB-Stick, verschlüsselter Container).

## Phase 5: Public IP ENTFERNEN (Onion-Only)

Sobald alles läuft:

Oracle Console → **Compute** → `[REDACTED-VM-HOSTNAME]` → **Attached VNICs** → `primaryvnic` → **IP Addresses** → neben Public IP → **Edit** → **No Public IP** → Save.

**Ab jetzt**: VM nur noch via SSH-Hidden-Service erreichbar:
```powershell
# Tor-Browser oder tor service muss laufen (SOCKS auf 127.0.0.1:9050)
ssh -o ProxyCommand="nc -x 127.0.0.1:9050 %h %p" -i "$env:USERPROFILE\.ssh\onyx_oracle_key" ubuntu@<SSH_ONION>.onion
```

## Phase 6: In ONYX VAULT App integrieren

In der App werden die `.onion`-URLs eingetragen:
- `OnyxUrls.RECOVERY_URL = "http://<ONION_WEB>.onion/recovery.html"`
- `OnyxUrls.KILLSWITCH_URL = "http://<ONION_WEB>.onion/killswitch.html"`

App zeigt in Settings → "Website im Tor-Browser öffnen" → User muss Tor-Browser nutzen → kein Clearnet-Besuch mehr möglich.
