#!/usr/bin/env bash
# Creates the app signing key once and stores it as GitHub secrets for the build.
# Keep a backup of keystore-backup/: without this key, updates need a reinstall (leads lost).
set -euo pipefail
cd "$(dirname "$0")"

if ! gh auth status >/dev/null 2>&1; then
  echo "Bitte zuerst bei GitHub anmelden (Browser öffnet sich):"
  gh auth login -h github.com -p https -w
fi

if gh secret list | grep -q KEYSTORE_B64; then
  echo "Schlüssel ist schon bei GitHub hinterlegt, nichts zu tun."
  exit 0
fi

mkdir -p keystore-backup
PW=$(openssl rand -hex 16)
# Relative paths only, and no path rewriting, so "/CN=..." is not turned into a
# Windows path by Git Bash.
MSYS_NO_PATHCONV=1 openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout keystore-backup/k.pem -out keystore-backup/c.pem \
  -days 10000 -subj "/CN=Lead Dialer" 2>/dev/null
openssl pkcs12 -export -inkey keystore-backup/k.pem -in keystore-backup/c.pem -name dialer \
  -out keystore-backup/dialer.jks -passout "pass:$PW"
rm -f keystore-backup/k.pem keystore-backup/c.pem
printf %s "$PW" > keystore-backup/passwort.txt

base64 -w0 keystore-backup/dialer.jks | gh secret set KEYSTORE_B64
printf %s "$PW" | gh secret set KEYSTORE_PASSWORD

echo "Fertig. Schlüssel ist bei GitHub gespeichert, Backup liegt in keystore-backup/."
