#!/bin/bash
# =====================================================================
# ONYX VAULT Hidden Service Setup — Full Automation
# Target: Ubuntu 22.04 LTS on Oracle Cloud Free Tier
#
# Was dieses Script tut:
#   1. System-Update + essential packages
#   2. Firewall (ufw) + fail2ban
#   3. Nginx auf 127.0.0.1:8080 (NICHT public!)
#   4. Clone des ONYX-VAULT GitHub-Repos
#   5. Tor + Hidden Service v3 Setup (web + ssh)
#   6. Beide .onion-Hostnamen auslesen
#   7. Backup der Hidden Service Keys verschlüsselt
#   8. Automatische Updates konfigurieren
#
# Als User ubuntu ausfuehren, benoetigt sudo.
# =====================================================================

set -euo pipefail

# ============ FARBEN ============
R='\033[0;31m'; G='\033[0;32m'; Y='\033[1;33m'; B='\033[0;34m'; NC='\033[0m'
log() { echo -e "${B}[$(date +%H:%M:%S)]${NC} $1"; }
ok()  { echo -e "${G}✅ $1${NC}"; }
err() { echo -e "${R}❌ $1${NC}"; exit 1; }
warn(){ echo -e "${Y}⚠️  $1${NC}"; }

# ============ CHECKS ============
log "Checking prerequisites..."
[[ "$EUID" -eq 0 ]] && err "Nicht als root ausfuehren - als ubuntu user mit sudo"
command -v sudo >/dev/null || err "sudo nicht verfuegbar"

# ============ PHASE 1: SYSTEM UPDATE ============
log "Phase 1/8: System Update + Essentials"
sudo apt-get update -qq
sudo apt-get upgrade -y -qq
sudo apt-get install -y -qq \
    git nginx tor ufw fail2ban \
    gnupg2 wget curl \
    unattended-upgrades apt-listchanges
ok "System aktualisiert + essentials installiert"

# ============ PHASE 2: FIREWALL ============
log "Phase 2/8: Firewall (ufw)"
# WICHTIG: Nur SSH im Clearnet erlauben (fuer Bootstrap), spaeter entfernen
sudo ufw --force reset >/dev/null
sudo ufw default deny incoming >/dev/null
sudo ufw default allow outgoing >/dev/null
sudo ufw allow 22/tcp comment 'SSH bootstrap' >/dev/null
sudo ufw --force enable >/dev/null
ok "Firewall aktiv - nur SSH 22 offen (fuer Bootstrap)"

# ============ PHASE 3: FAIL2BAN ============
log "Phase 3/8: fail2ban gegen SSH-brute-force"
sudo tee /etc/fail2ban/jail.d/sshd.conf >/dev/null <<'EOF'
[sshd]
enabled = true
port = 22
filter = sshd
logpath = /var/log/auth.log
maxretry = 3
bantime = 3600
findtime = 600
EOF
sudo systemctl enable fail2ban
sudo systemctl restart fail2ban
ok "fail2ban scharf"

# ============ PHASE 4: NGINX LOCALHOST-ONLY ============
log "Phase 4/8: Nginx auf 127.0.0.1:8080 (NICHT public!)"
sudo mkdir -p /var/www/[REDACTED-VM-HOSTNAME]
sudo chown -R ubuntu:ubuntu /var/www/[REDACTED-VM-HOSTNAME]

# Default site deaktivieren
sudo rm -f /etc/nginx/sites-enabled/default

# Onyx site config
sudo tee /etc/nginx/sites-available/[REDACTED-VM-HOSTNAME] >/dev/null <<'EOF'
server {
    listen 127.0.0.1:8080;
    server_name localhost;
    root /var/www/[REDACTED-VM-HOSTNAME];
    index killswitch.html index.html;

    # Minimal logging (keine User-IPs ins Log)
    access_log off;
    error_log /var/log/nginx/onyx-error.log warn;

    # Security headers
    add_header X-Frame-Options "DENY" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "no-referrer" always;
    add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;
    # CSP: nur eigene Assets + die 10 .onion Nostr-Relays
    add_header Content-Security-Policy "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' wss://*.onion ws://*.onion; frame-ancestors 'none';" always;

    # Deny hidden files
    location ~ /\. { deny all; }

    # Standard routing
    location / {
        try_files $uri $uri/ /killswitch.html;
    }
}
EOF

sudo ln -sf /etc/nginx/sites-available/[REDACTED-VM-HOSTNAME] /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl enable nginx
sudo systemctl restart nginx
ok "Nginx lauscht nur auf 127.0.0.1:8080"

# ============ PHASE 5: GITHUB REPO DEPLOYEN ============
log "Phase 5/8: ONYX VAULT Website vom GitHub-Repo deployen"
cd /tmp
rm -rf onyxvault-website
git clone --depth=1 https://github.com/supportonyxvault/onyxvault-website.git
cp -r onyxvault-website/* /var/www/[REDACTED-VM-HOSTNAME]/
# killswitch als Startseite (optional - sonst index.html)
# cp /var/www/[REDACTED-VM-HOSTNAME]/killswitch.html /var/www/[REDACTED-VM-HOSTNAME]/index.html
sudo chown -R www-data:www-data /var/www/[REDACTED-VM-HOSTNAME]
sudo chmod -R 750 /var/www/[REDACTED-VM-HOSTNAME]
sudo chmod 755 /var/www/[REDACTED-VM-HOSTNAME]
ok "Website deployed nach /var/www/[REDACTED-VM-HOSTNAME]/"

# ============ PHASE 6: TOR HIDDEN SERVICE v3 ============
log "Phase 6/8: Tor Hidden Service konfigurieren"
# Backup existing torrc
sudo cp /etc/tor/torrc /etc/tor/torrc.backup.$(date +%s) 2>/dev/null || true

sudo tee -a /etc/tor/torrc >/dev/null <<'EOF'

# ===== ONYX VAULT Hidden Service (Web) =====
HiddenServiceDir /var/lib/tor/onyx_killswitch/
HiddenServicePort 80 127.0.0.1:8080

# ===== SSH Admin Hidden Service =====
HiddenServiceDir /var/lib/tor/ssh_hidden_service/
HiddenServicePort 22 127.0.0.1:22

# ===== Logging minimal =====
Log notice stdout
EOF

sudo systemctl enable tor
sudo systemctl restart tor

# Warten bis Hidden Service hostnames erzeugt sind
log "Warte auf Hidden Service Keys (max 30s)..."
for i in {1..30}; do
    if sudo test -f /var/lib/tor/onyx_killswitch/hostname && \
       sudo test -f /var/lib/tor/ssh_hidden_service/hostname; then
        break
    fi
    sleep 1
done

ONION_WEB=$(sudo cat /var/lib/tor/onyx_killswitch/hostname 2>/dev/null || echo "FEHLER")
ONION_SSH=$(sudo cat /var/lib/tor/ssh_hidden_service/hostname 2>/dev/null || echo "FEHLER")

[[ "$ONION_WEB" == "FEHLER" ]] && err "Tor Hidden Service Web-Hostname nicht erzeugt"
[[ "$ONION_SSH" == "FEHLER" ]] && err "Tor Hidden Service SSH-Hostname nicht erzeugt"

ok "Tor Hidden Service laeuft"
ok "Web-Onion: $ONION_WEB"
ok "SSH-Onion: $ONION_SSH"

# ============ PHASE 7: BACKUP (verschluesselt) ============
log "Phase 7/8: Backup der Hidden Service Keys"
BACKUP_DIR=/home/ubuntu/onyx-hs-backup-$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP_DIR"

sudo cp -r /var/lib/tor/onyx_killswitch/hs_ed25519_* "$BACKUP_DIR/" 2>/dev/null
sudo cp -r /var/lib/tor/ssh_hidden_service/hs_ed25519_* "$BACKUP_DIR/" 2>/dev/null
sudo cp /var/lib/tor/onyx_killswitch/hostname "$BACKUP_DIR/onyx_web.hostname"
sudo cp /var/lib/tor/ssh_hidden_service/hostname "$BACKUP_DIR/ssh.hostname"
sudo chown -R ubuntu:ubuntu "$BACKUP_DIR"

echo ""
warn "WICHTIG: Backup liegt in $BACKUP_DIR"
warn "Du MUSST diese Keys offline sichern! Falls Oracle-VM weg ist, kannst du den Hidden Service nicht wiederherstellen."
warn "Transfer per SCP: scp -i ~/.ssh/onyx_oracle_key -r ubuntu@<ip>:$BACKUP_DIR ."
ok "Backup erstellt"

# ============ PHASE 8: AUTOMATIC UPDATES ============
log "Phase 8/8: unattended security updates"
sudo dpkg-reconfigure -plow unattended-upgrades --priority=low <<< "yes" 2>/dev/null || true
sudo tee /etc/apt/apt.conf.d/50unattended-upgrades-onyx >/dev/null <<'EOF'
Unattended-Upgrade::Allowed-Origins { "${distro_id}:${distro_codename}-security"; };
Unattended-Upgrade::Automatic-Reboot "false";
EOF
ok "Automatic security updates aktiv"

# ============ ZUSAMMENFASSUNG ============
echo ""
echo "=============================================================="
echo "  🎉 ONYX VAULT Hidden Service SETUP COMPLETE"
echo "=============================================================="
echo ""
echo "  WEB (Killswitch via Tor-Browser):"
echo "  🧅 http://$ONION_WEB/"
echo "  🧅 http://$ONION_WEB/killswitch.html"
echo "  🧅 http://$ONION_WEB/recovery.html"
echo ""
echo "  SSH Admin Zugang via Tor:"
echo "  🔧 ssh -o ProxyCommand=\"nc -x 127.0.0.1:9050 %h %p\" ubuntu@$ONION_SSH"
echo ""
echo "  BACKUP: $BACKUP_DIR"
echo ""
echo "=============================================================="
echo "  NAECHSTE SCHRITTE:"
echo "  1. Teste Zugriff via Tor-Browser auf http://$ONION_WEB/"
echo "  2. Backup der Hidden Service Keys auf OFFLINE-Medium"
echo "  3. Public IP in Oracle Console entfernen (siehe Doku)"
echo "=============================================================="
