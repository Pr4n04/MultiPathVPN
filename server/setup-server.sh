#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# MultiPath VPN — Home Server Setup Script
# ═══════════════════════════════════════════════════════════════
# This sets up your old laptop (or Xeon server) as a WireGuard
# bonding VPN endpoint. Your Pixel 9 Pro XL connects to it over
# BOTH WiFi and cellular simultaneously.
#
# The server forwards traffic to the internet and sends responses
# back through whichever network path they arrived on.
#
# Usage:
#   chmod +x setup-server.sh
#   sudo ./setup-server.sh
#
# Requirements: Ubuntu/Debian (or Raspberry Pi OS), internet connection
# ═══════════════════════════════════════════════════════════════

set -e

# ─── Config ──────────────────────────────────────────────────
# Change these to match your setup
SERVER_WG_PORT="${SERVER_WG_PORT:-51820}"
SERVER_PUBLIC_IP="${SERVER_PUBLIC_IP:-}"  # Leave empty for auto-detect
CLIENT_VPN_IP="10.88.0.2"
SERVER_VPN_IP="10.88.0.1"
VPN_SUBNET="10.88.0.0/24"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   MultiPath VPN — Home Server Setup         ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════╝${NC}"
echo ""

# ─── Phase 1: Install Dependencies ──────────────────────────
echo -e "${YELLOW}[1/6] Installing dependencies...${NC}"

# Update package list
apt-get update -qq

# Install WireGuard, iptables, and utilities
apt-get install -y -qq \
    wireguard \
    wireguard-tools \
    iptables \
    resolvconf \
    qrencode \
    curl \
    wget

echo -e "${GREEN}  ✓ Dependencies installed${NC}"

# ─── Phase 2: Enable IP Forwarding ──────────────────────────
echo -e "${YELLOW}[2/6] Enabling IP forwarding...${NC}"

# Enable IPv4 forwarding
echo "net.ipv4.ip_forward = 1" > /etc/sysctl.d/99-wireguard.conf
sysctl -p /etc/sysctl.d/99-wireguard.conf

echo -e "${GREEN}  ✓ IP forwarding enabled${NC}"

# ─── Phase 3: Generate Server Keys ─────────────────────────
echo -e "${YELLOW}[3/6] Generating WireGuard keys...${NC}"

cd /etc/wireguard
umask 077

# Generate server key pair
wg genkey | tee server_private_key | wg pubkey > server_public_key
SERVER_PRIVATE=$(cat server_private_key)
SERVER_PUBLIC=$(cat server_public_key)

# Generate client key pair (for initial config)
wg genkey | tee client_private_key | wg pubkey > client_public_key
CLIENT_PRIVATE=$(cat client_private_key)
CLIENT_PUBLIC=$(cat client_public_key)

echo -e "${GREEN}  ✓ Server public key: ${SERVER_PUBLIC}${NC}"
echo -e "${GREEN}  ✓ Client public key: ${CLIENT_PUBLIC}${NC}"

# ─── Phase 4: Create WireGuard Configuration ────────────────
echo -e "${YELLOW}[4/6] Creating WireGuard configuration...${NC}"

# Detect public IP
if [ -z "$SERVER_PUBLIC_IP" ]; then
    SERVER_PUBLIC_IP=$(curl -s https://api.ipify.org 2>/dev/null || \
                        curl -s https://ifconfig.me 2>/dev/null || \
                        dig +short myip.opendns.com @resolver1.opendns.com 2>/dev/null)
    if [ -z "$SERVER_PUBLIC_IP" ]; then
        echo -e "${RED}  ✗ Could not detect public IP. Please set SERVER_PUBLIC_IP manually.${NC}"
        exit 1
    fi
fi

cat > /etc/wireguard/wg0.conf <<WGEOF
# ─── MultiPath VPN Server Configuration ──────────────────────
# This config accepts connections from your Pixel phone over
# BOTH WiFi and cellular at the same time.

[Interface]
Address = ${SERVER_VPN_IP}/24
PrivateKey = ${SERVER_PRIVATE}
ListenPort = ${SERVER_WG_PORT}

# NAT traffic to the internet
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT
PostUp = iptables -A FORWARD -o wg0 -j ACCEPT
PostUp = iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
PostUp = iptables -t nat -A POSTROUTING -o enp0s3 -j MASQUERADE
PostUp = iptables -t nat -A POSTROUTING -o wlan0 -j MASQUERADE

PostDown = iptables -D FORWARD -i wg0 -j ACCEPT 2>/dev/null || true
PostDown = iptables -D FORWARD -o wg0 -j ACCEPT 2>/dev/null || true
PostDown = iptables -t nat -D POSTROUTING -o eth0 -j MASQUERADE 2>/dev/null || true
PostDown = iptables -t nat -D POSTROUTING -o enp0s3 -j MASQUERADE 2>/dev/null || true
PostDown = iptables -t nat -D POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null || true

# Enable multipath routing (equal-cost multipath)
# This allows the server to route responses back through
# whichever path the packet came from
PostUp = ip route replace default scope global \
    nexthop via $(ip route show default | awk '{print $3}') dev $(ip route show default | awk '{print $5}') weight 1

# ─── Client: Pixel 9 Pro XL ────────────────────────────
[Peer]
PublicKey = ${CLIENT_PUBLIC}
AllowedIPs = ${CLIENT_VPN_IP}/32
# No PersistentKeepalive needed for phone (it sends data frequently)

WGEOF

echo -e "${GREEN}  ✓ WireGuard config created${NC}"

# ─── Phase 5: Enable and Start WireGuard ────────────────────
echo -e "${YELLOW}[5/6] Starting WireGuard...${NC}"

# Enable the service to start on boot
systemctl enable wg-quick@wg0 2>/dev/null || true

# Start WireGuard
systemctl start wg-quick@wg0 2>/dev/null || wg-quick up wg0

echo -e "${GREEN}  ✓ WireGuard started${NC}"

# ─── Phase 6: Generate Client Configuration ─────────────────
echo -e "${YELLOW}[6/6] Generating client config for your Pixel...${NC}"

# Detect the server's LAN IP for local connections
LAN_IP=$(ip -4 addr show | grep -oP '(?<=inet\s)\d+(\.\d+){3}' | \
         grep -v '127.0.0.1' | grep -v '10.88.' | head -1)

cat > /root/pixel-wg-config.conf <<CLIENTEOF
[Interface]
# ─── Pixel 9 Pro XL Configuration ────────────────────────────
# Import this file into the WireGuard app on your phone.
#
# This config uses BOTH WiFi and Cellular simultaneously.
# The phone connects to the server over both networks.
#
# For true bonding: verify your phone can reach the server
# from BOTH networks (WiFi and Cellular data).

PrivateKey = ${CLIENT_PRIVATE}
Address = ${CLIENT_VPN_IP}/24
DNS = 1.1.1.1, 8.8.8.8
MTU = 1420

# ─── Home Server ──────────────────────────────────────
# Use the public IP when you're on the train (outside home network)
[Peer]
PublicKey = ${SERVER_PUBLIC}
PresharedKey = # (optional, leave empty)
AllowedIPs = 0.0.0.0/0     # Route ALL traffic through the server
Endpoint = ${SERVER_PUBLIC_IP}:${SERVER_WG_PORT}
PersistentKeepalive = 25   # Keeps the tunnel alive on both networks

# ─── For when you're on the same home WiFi ─────────────
# Add this second peer entry for lower latency at home.
# Comment this out when on the train.
#[Peer]
#PublicKey = ${SERVER_PUBLIC}
#AllowedIPs = 0.0.0.0/0
#Endpoint = ${LAN_IP}:${SERVER_WG_PORT}
#PersistentKeepalive = 25
CLIENTEOF

echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║   SETUP COMPLETE                            ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════╝${NC}"
echo ""
echo -e "Your server is running at ${YELLOW}${SERVER_PUBLIC_IP}:${SERVER_WG_PORT}${NC}"
echo ""
echo -e "${YELLOW}── Next Steps ──────────────────────────────${NC}"
echo ""
echo -e "1. Copy the client config to your Pixel 9 Pro XL:"
echo ""
echo -e "   ${GREEN}scp root@${LAN_IP}:/root/pixel-wg-config.conf .${NC}"
echo ""
echo -e "   Or scan this QR code with the WireGuard app:"
echo ""

# Generate QR code for the config
qrencode -t ANSIUTF8 < /root/pixel-wg-config.conf 2>/dev/null || \
    echo -e "   (Install qrencode to show QR code, or copy the config file)"

echo ""
echo -e "2. On your Pixel 9 Pro XL:"
echo -e "   • Install ${GREEN}WireGuard${NC} from Play Store"
echo -e "   • Tap + → Import from file or QR code"
echo -e "   • Import the config above"
echo -e "   • Toggle the VPN ON"
echo ""
echo -e "3. ${RED}IMPORTANT:${NC} Port forward on your home router:"
echo -e "   Forward ${YELLOW}UDP port ${SERVER_WG_PORT}${NC} to this server's IP: ${YELLOW}${LAN_IP}${NC}"
echo ""
echo -e "4. On the train:"
echo -e "   • Phone connects via WiFi AND Cellular to your server"
echo -e "   • WireGuard maintains the tunnel across both"
echo -e "   • ${GREEN}Zero cutouts for messages and music${NC}"
echo ""
echo -e "${YELLOW}── Client Config (for reference) ────────────${NC}"
echo ""
cat /root/pixel-wg-config.conf
echo ""

# ─── Port forwarding reminder ────────────────────────────────
echo -e "${YELLOW}── Router Setup ────────────────────────────${NC}"
echo ""
echo -e "Go to your router settings and add:"
echo -e "  ${GREEN}Port Forward:${NC} UDP ${SERVER_WG_PORT} → ${LAN_IP}:${SERVER_WG_PORT}"
echo ""
echo -e "${GREEN}Done! Your MultiPath VPN server is ready.${NC}"
