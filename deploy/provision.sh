#!/usr/bin/env bash
# One-time setup of a fresh Hetzner CPX22 on Ubuntu 26.04 or 24.04 LTS (E1-T7, D34 as amended 2026-10-18), run as root from the Hetzner console:
# Docker, rclone, automatic security updates, a swap file, UTC, the repository, the env-file directory, the
# nightly backup timer — and Tailscale, which becomes the only way in (D34 R5): once `tailscale up` has run,
# remove the firewall's SSH rule so the box has no public inbound port at all.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
timedatectl set-timezone UTC
apt-get update -q
apt-get install -y -q ca-certificates curl git unattended-upgrades
# rclone from rclone.org, not Ubuntu's package: 24.04 ships v1.60 (2022), whose first request to R2 is refused with
# 501 NotImplemented and only lands on rclone's retry (seen on the first box, 2026-10-18)
command -v rclone > /dev/null || curl -fsSL https://rclone.org/install.sh | bash
dpkg-reconfigure -f noninteractive unattended-upgrades
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list
apt-get update -q
apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-compose-plugin
if [ ! -f /swapfile ]; then
    fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
[ -d /opt/tradebench/.git ] || git clone --quiet https://github.com/amfshr/tradebench.git /opt/tradebench
install -d -m 0700 /etc/tradebench
install -m 0644 /opt/tradebench/deploy/systemd/tradebench-backup.service /etc/systemd/system/
install -m 0644 /opt/tradebench/deploy/systemd/tradebench-backup.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now tradebench-backup.timer
curl -fsSL https://tailscale.com/install.sh | sh
echo
echo "provisioned. Next, by hand:"
echo "  1. tailscale up --ssh       (log in; then reach the box as root@<its tailscale name>)"
echo "  2. remove the Hetzner firewall's SSH rule — no public inbound port remains"
echo "  3. write /etc/tradebench/{market-data,postgres,backup}.env from deploy/env/*.example (chmod 600)"
echo "  4. /opt/tradebench/deploy/deploy.sh v<the release tag>"
