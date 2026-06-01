#!/bin/bash
# Ubuntu 26.04 vSphere Template Setup Script
# Usage: sudo bash vsphere-template-setup.sh

if [ "$EUID" -ne 0 ]; then
    echo "Please run as root: sudo bash $0"
    exit 1
fi

SSH_PORT=45000
COCKPIT_PORT=45001

echo ""
echo "=============================="
echo " Ubuntu vSphere Template Setup"
echo "=============================="
echo ""
echo "NOTE: Passwords will be visible as you type (vSphere console)"
echo ""

printf "Set password for ROOT: "
read ROOT_PASS
printf "Set password for UBUNTU: "
read UBUNTU_PASS
printf "Set password for VNADMIN: "
read VNADMIN_PASS

echo ""
echo "Passwords collected. Starting setup..."
echo ""

# Timezone and locale
echo "[1/10] Setting timezone to Australia/Brisbane..."
timedatectl set-timezone Australia/Brisbane
localectl set-locale LANG=en_AU.UTF-8 2>/dev/null || true

# Updates
echo "[2/10] Running system updates (this may take a while)..."
apt-get update -y -q
DEBIAN_FRONTEND=noninteractive apt-get upgrade -y -q
DEBIAN_FRONTEND=noninteractive apt-get dist-upgrade -y -q
apt-get autoremove -y -q
apt-get autoclean -y -q

# Set passwords
echo "[3/10] Setting passwords and enabling root..."
echo "root:${ROOT_PASS}" | chpasswd
if id "ubuntu" &>/dev/null; then
    echo "ubuntu:${UBUNTU_PASS}" | chpasswd
fi
passwd -u root 2>/dev/null || true

# Create vnadmin
echo "[4/10] Creating vnadmin user..."
if id "vnadmin" &>/dev/null; then
    echo "vnadmin already exists, updating password."
else
    useradd -m -s /bin/bash -c "VN Admin" vnadmin
fi
echo "vnadmin:${VNADMIN_PASS}" | chpasswd
usermod -aG sudo vnadmin

# Create /VN
echo "[5/10] Creating /VN directory..."
mkdir -p /VN
chmod 1777 /VN
chown root:root /VN

# Configure SSH
echo "[6/10] Configuring SSH on port $SSH_PORT..."
cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak

sed -i "s/^#*Port .*/Port ${SSH_PORT}/" /etc/ssh/sshd_config
grep -q "^Port" /etc/ssh/sshd_config || echo "Port ${SSH_PORT}" >> /etc/ssh/sshd_config

sed -i 's/^#*PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config
grep -q "^PasswordAuthentication" /etc/ssh/sshd_config || echo "PasswordAuthentication yes" >> /etc/ssh/sshd_config

sed -i 's/^#*PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
grep -q "^PermitRootLogin" /etc/ssh/sshd_config || echo "PermitRootLogin yes" >> /etc/ssh/sshd_config

sed -i 's/^#*KbdInteractiveAuthentication.*/KbdInteractiveAuthentication yes/' /etc/ssh/sshd_config

if systemctl list-units --full --all 2>/dev/null | grep -q "ssh.socket"; then
    mkdir -p /etc/systemd/system/ssh.socket.d
    cat > /etc/systemd/system/ssh.socket.d/override.conf << EOF
[Socket]
ListenStream=
ListenStream=${SSH_PORT}
EOF
    systemctl daemon-reload
fi

# Cockpit
echo "[7/10] Installing Cockpit on port $COCKPIT_PORT..."
DEBIAN_FRONTEND=noninteractive apt-get install -y -q cockpit
mkdir -p /etc/systemd/system/cockpit.socket.d
cat > /etc/systemd/system/cockpit.socket.d/listen.conf << EOF
[Socket]
ListenStream=
ListenStream=${COCKPIT_PORT}
EOF
systemctl daemon-reload
systemctl enable cockpit.socket
systemctl restart cockpit.socket 2>/dev/null || true

# Firewall
echo "[8/10] Installing firewalld..."
if dpkg -l 2>/dev/null | grep -q "^ii.*ufw"; then
    ufw disable 2>/dev/null || true
    DEBIAN_FRONTEND=noninteractive apt-get remove -y -q ufw
    DEBIAN_FRONTEND=noninteractive apt-get purge -y -q ufw
fi
DEBIAN_FRONTEND=noninteractive apt-get install -y -q firewalld
systemctl enable firewalld
systemctl start firewalld
firewall-cmd --permanent --remove-service=ssh 2>/dev/null || true
firewall-cmd --permanent --remove-service=cockpit 2>/dev/null || true
firewall-cmd --permanent --add-port=${SSH_PORT}/tcp
firewall-cmd --permanent --add-port=${COCKPIT_PORT}/tcp
firewall-cmd --reload

# Restart SSH
echo "[9/10] Restarting SSH..."
systemctl restart ssh 2>/dev/null || systemctl restart sshd 2>/dev/null || true

# Disable cloud-init
echo "[10/10] Disabling cloud-init and running cleanup..."
touch /etc/cloud/cloud-init.disabled
mkdir -p /etc/cloud/cloud.cfg.d
cat > /etc/cloud/cloud.cfg.d/99-datasource.cfg << EOF
datasource_list: [ None ]
EOF

cloud-init clean --logs 2>/dev/null || true
rm -f /etc/ssh/ssh_host_*
truncate -s 0 /etc/machine-id
rm -f /var/lib/dbus/machine-id
ln -sf /etc/machine-id /var/lib/dbus/machine-id
apt-get clean -q
history -c
cat /dev/null > ~/.bash_history
[ -f /home/ubuntu/.bash_history ]  && cat /dev/null > /home/ubuntu/.bash_history
[ -f /home/vnadmin/.bash_history ] && cat /dev/null > /home/vnadmin/.bash_history
rm -rf /tmp/* /var/tmp/* 2>/dev/null || true
find /var/log -type f -exec truncate -s 0 {} \; 2>/dev/null || true

echo ""
echo "=============================="
echo " Setup Complete"
echo "=============================="
echo " SSH port     : $SSH_PORT"
echo " Cockpit port : $COCKPIT_PORT"
echo " Timezone     : Australia/Brisbane"
echo " Users        : root, ubuntu, vnadmin (sudo)"
echo " /VN          : created"
echo " Firewall     : firewalld active"
echo " cloud-init   : disabled"
echo "=============================="
echo ""
printf "Shut down now for templating? [y/N]: "
read SHUTDOWN_ANSWER
echo ""
if [ "$SHUTDOWN_ANSWER" = "y" ] || [ "$SHUTDOWN_ANSWER" = "Y" ]; then
    echo "Shutting down in 5 seconds..."
    sleep 5
    shutdown -h now
else
    echo "Run 'sudo shutdown -h now' when ready, then convert to template in vSphere."
fi
