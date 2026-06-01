#!/bin/bash
# =============================================================================
#  Ubuntu 26.04 LTS — vSphere Template Setup Script
#  Run with:  wget -O setup.sh https://YOUR-SERVER/vsphere-template-setup.sh && sudo bash setup.sh
# =============================================================================

set -e

# ── Colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

log()  { echo -e "${GREEN}[✔]${NC} $1"; }
info() { echo -e "${CYAN}[→]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
err()  { echo -e "${RED}[✘]${NC} $1"; exit 1; }
hdr()  { echo -e "\n${BOLD}${CYAN}════════════════════════════════════════${NC}"; \
         echo -e "${BOLD}${CYAN}  $1${NC}"; \
         echo -e "${BOLD}${CYAN}════════════════════════════════════════${NC}"; }

# ── Must run as root ──────────────────────────────────────────────────────────
[[ $EUID -ne 0 ]] && err "Please run as root:  sudo bash $0"

# =============================================================================
hdr "STEP 1 — Collect Passwords"
# =============================================================================

prompt_password() {
    local label="$1" var1 var2
    while true; do
        echo -ne "${CYAN}  Enter password for ${BOLD}${label}${NC}${CYAN}: ${NC}"
        read -rs var1; echo
        echo -ne "${CYAN}  Confirm password for ${BOLD}${label}${NC}${CYAN}: ${NC}"
        read -rs var2; echo
        if [[ "$var1" == "$var2" && -n "$var1" ]]; then
            echo "$var1"
            return
        else
            warn "Passwords do not match or are empty. Try again."
        fi
    done
}

echo ""
info "You will be prompted for passwords for: root, ubuntu, vnadmin"
echo ""

ROOT_PASS=$(prompt_password "root")
UBUNTU_PASS=$(prompt_password "ubuntu")
VNADMIN_PASS=$(prompt_password "vnadmin")

log "Passwords collected."

# =============================================================================
hdr "STEP 2 — Set Timezone (Brisbane / Australia)"
# =============================================================================

info "Setting timezone to Australia/Brisbane..."
timedatectl set-timezone Australia/Brisbane
log "Timezone set: $(timedatectl | grep 'Time zone')"

info "Setting locale to Australia..."
localectl set-locale LANG=en_AU.UTF-8 || true
log "Locale set."

# =============================================================================
hdr "STEP 3 — Install All Updates"
# =============================================================================

info "Updating package lists..."
apt-get update -y

info "Upgrading all packages..."
DEBIAN_FRONTEND=noninteractive apt-get upgrade -y
DEBIAN_FRONTEND=noninteractive apt-get dist-upgrade -y

info "Removing unused packages..."
apt-get autoremove -y
apt-get autoclean -y

log "System fully updated."

# =============================================================================
hdr "STEP 4 — Set Passwords for root and ubuntu"
# =============================================================================

echo "root:${ROOT_PASS}" | chpasswd
log "Root password set."

# Set ubuntu user password if account exists
if id "ubuntu" &>/dev/null; then
    echo "ubuntu:${UBUNTU_PASS}" | chpasswd
    log "Ubuntu user password set."
fi

# =============================================================================
hdr "STEP 5 — Enable Root Login"
# =============================================================================

info "Enabling root account..."
passwd -u root 2>/dev/null || true
log "Root account unlocked."

# =============================================================================
hdr "STEP 6 — Create vnadmin User with Sudo Access"
# =============================================================================

if id "vnadmin" &>/dev/null; then
    warn "User vnadmin already exists — updating password only."
else
    info "Creating user vnadmin..."
    useradd -m -s /bin/bash -c "VN Admin" vnadmin
    log "User vnadmin created."
fi

echo "vnadmin:${VNADMIN_PASS}" | chpasswd
usermod -aG sudo vnadmin
log "vnadmin password set and added to sudo group."

# =============================================================================
hdr "STEP 7 — Create /VN Directory"
# =============================================================================

info "Creating /VN with full read/write access for all users..."
mkdir -p /VN
chmod 1777 /VN          # sticky bit — anyone can write, can't delete others' files
chown root:root /VN
log "/VN created with permissions: $(stat -c '%A' /VN)"

# =============================================================================
hdr "STEP 8 — Configure SSH"
# =============================================================================

SSH_PORT=45000
SSHD_CONFIG="/etc/ssh/sshd_config"

info "Backing up sshd_config..."
cp "$SSHD_CONFIG" "${SSHD_CONFIG}.bak.$(date +%Y%m%d%H%M%S)"

info "Setting SSH port to ${SSH_PORT}..."
sed -i "s/^#\?Port .*/Port ${SSH_PORT}/" "$SSHD_CONFIG"
grep -q "^Port" "$SSHD_CONFIG" || echo "Port ${SSH_PORT}" >> "$SSHD_CONFIG"

info "Enabling password authentication..."
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' "$SSHD_CONFIG"
sed -i 's/^#\?KbdInteractiveAuthentication.*/KbdInteractiveAuthentication yes/' "$SSHD_CONFIG"

info "Enabling root SSH login..."
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin yes/' "$SSHD_CONFIG"
grep -q "^PermitRootLogin" "$SSHD_CONFIG" || echo "PermitRootLogin yes" >> "$SSHD_CONFIG"

# Handle systemd socket-based SSH (Ubuntu 24.04+ / 26.04)
if systemctl list-units --full --all | grep -q "ssh.socket"; then
    info "Updating SSH systemd socket to listen on port ${SSH_PORT}..."
    mkdir -p /etc/systemd/system/ssh.socket.d
    cat > /etc/systemd/system/ssh.socket.d/override.conf << EOF
[Socket]
ListenStream=
ListenStream=${SSH_PORT}
EOF
    systemctl daemon-reload
fi

log "SSH configured on port ${SSH_PORT}."

# Prevent cloud-init from overriding SSH settings
echo "ssh_pwauth: True" >> /etc/cloud/cloud.cfg.d/99_custom.cfg 2>/dev/null || true

# =============================================================================
hdr "STEP 9 — Install & Configure Cockpit on Port 45001"
# =============================================================================

COCKPIT_PORT=45001

info "Installing Cockpit..."
DEBIAN_FRONTEND=noninteractive apt-get install -y cockpit

info "Setting Cockpit to listen on port ${COCKPIT_PORT}..."
mkdir -p /etc/systemd/system/cockpit.socket.d
cat > /etc/systemd/system/cockpit.socket.d/listen.conf << EOF
[Socket]
ListenStream=
ListenStream=${COCKPIT_PORT}
EOF

systemctl daemon-reload
systemctl enable cockpit.socket
systemctl restart cockpit.socket 2>/dev/null || true
log "Cockpit installed and configured on port ${COCKPIT_PORT}."

# =============================================================================
hdr "STEP 10 — Remove UFW and Install firewalld"
# =============================================================================

info "Removing UFW if installed..."
if dpkg -l | grep -q "^ii.*ufw"; then
    ufw disable 2>/dev/null || true
    apt-get remove -y ufw
    apt-get purge -y ufw
    log "UFW removed."
else
    log "UFW not installed — nothing to remove."
fi

info "Installing firewalld..."
DEBIAN_FRONTEND=noninteractive apt-get install -y firewalld

info "Enabling and starting firewalld..."
systemctl enable firewalld
systemctl start firewalld

info "Configuring firewall rules..."

# Add SSH on custom port
firewall-cmd --permanent --add-port=${SSH_PORT}/tcp
log "Allowed SSH port ${SSH_PORT}/tcp"

# Add Cockpit on custom port
firewall-cmd --permanent --add-port=${COCKPIT_PORT}/tcp
log "Allowed Cockpit port ${COCKPIT_PORT}/tcp"

# Remove default SSH port 22 from public zone (security hardening)
firewall-cmd --permanent --remove-service=ssh 2>/dev/null || true
# Remove default cockpit service entry (we use custom port)
firewall-cmd --permanent --remove-service=cockpit 2>/dev/null || true

# Reload to apply
firewall-cmd --reload

log "firewalld configured. Active rules:"
firewall-cmd --list-all

# =============================================================================
hdr "STEP 11 — Restart SSH Service"
# =============================================================================

info "Restarting SSH..."
systemctl restart ssh 2>/dev/null || systemctl restart sshd 2>/dev/null || true

# Quick verification
sleep 2
if ss -tlnp | grep -q ":${SSH_PORT}"; then
    log "SSH is listening on port ${SSH_PORT}."
else
    warn "SSH port ${SSH_PORT} not detected yet — it may need a moment or a reboot."
fi

# =============================================================================
hdr "STEP 12 — Disable cloud-init (Option B — No datasource needed)"
# =============================================================================

info "Disabling cloud-init..."
touch /etc/cloud/cloud-init.disabled

# Also tell cloud-init to use NoCloud/None so it never waits on boot
mkdir -p /etc/cloud/cloud.cfg.d
cat > /etc/cloud/cloud.cfg.d/99-datasource.cfg << 'EOF'
datasource_list: [ None ]
EOF

log "cloud-init disabled — VM will boot without waiting for a datasource."

# =============================================================================
hdr "STEP 13 — Template Cleanup"
# =============================================================================

info "Cleaning cloud-init state..."
cloud-init clean --logs 2>/dev/null || true

info "Removing SSH host keys (regenerated per-VM on first boot)..."
rm -f /etc/ssh/ssh_host_*

info "Resetting machine-id..."
truncate -s 0 /etc/machine-id
rm -f /var/lib/dbus/machine-id
ln -sf /etc/machine-id /var/lib/dbus/machine-id

info "Clearing apt cache..."
apt-get clean

info "Clearing shell history..."
history -c
cat /dev/null > ~/.bash_history
[[ -f /home/ubuntu/.bash_history ]]  && cat /dev/null > /home/ubuntu/.bash_history
[[ -f /home/vnadmin/.bash_history ]] && cat /dev/null > /home/vnadmin/.bash_history

info "Clearing temp files..."
rm -rf /tmp/* /var/tmp/* 2>/dev/null || true

info "Clearing logs..."
find /var/log -type f -exec truncate -s 0 {} \; 2>/dev/null || true

log "Cleanup complete."

# =============================================================================
hdr "SETUP COMPLETE — Summary"
# =============================================================================

echo ""
echo -e "${BOLD}  Configuration Summary${NC}"
echo -e "  ─────────────────────────────────────────"
echo -e "  ${GREEN}Timezone${NC}        Australia/Brisbane (AEST)"
echo -e "  ${GREEN}SSH Port${NC}        ${SSH_PORT}"
echo -e "  ${GREEN}Cockpit Port${NC}    ${COCKPIT_PORT}"
echo -e "  ${GREEN}Users${NC}           root, ubuntu, vnadmin (sudo)"
echo -e "  ${GREEN}Password Auth${NC}   Enabled"
echo -e "  ${GREEN}Root Login${NC}      Enabled"
echo -e "  ${GREEN}/VN Directory${NC}   Created (chmod 1777)"
echo -e "  ${GREEN}Firewall${NC}        firewalld — ports ${SSH_PORT}, ${COCKPIT_PORT} open"
echo -e "  ${GREEN}cloud-init${NC}      Disabled (no boot hang)"
echo -e "  ${GREEN}SSH host keys${NC}   Cleared (regenerated per clone)"
echo -e "  ─────────────────────────────────────────"
echo ""
echo -e "${YELLOW}  ⚠  Remember: After this shutdown, go to vSphere and${NC}"
echo -e "${YELLOW}     right-click the VM → Template → Convert to Template${NC}"
echo ""

# =============================================================================
# Shutdown Prompt
# =============================================================================

echo -ne "${BOLD}  Shut down the VM now to prepare for templating? [y/N]: ${NC}"
read -r SHUTDOWN_ANSWER
echo ""

if [[ "${SHUTDOWN_ANSWER,,}" == "y" || "${SHUTDOWN_ANSWER,,}" == "yes" ]]; then
    log "Shutting down in 5 seconds... Press Ctrl+C to cancel."
    sleep 5
    shutdown -h now
else
    info "Shutdown skipped. Run 'sudo shutdown -h now' when ready."
    info "Then in vSphere: right-click VM → Template → Convert to Template."
fi
