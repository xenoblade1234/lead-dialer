#!/usr/bin/env bash
# Creates the app signing key once and stores it as GitHub secrets for the build.
# Keep a backup of keystore-backup/: without this key, updates need a reinstall (leads lost).
set -euo pipefail
cd "$(dirname "$0")"

if gh secret list | grep -q KEYSTORE_B64; then
  echo "Schlüssel ist schon bei GitHub hinterlegt, nichts zu tun."
  exit 0
fi

mkdir -p keystore-backup
PW=$(openssl rand -hex 16)
TMP=$(mktemp -d)
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$TMP/k.pem" -out "$TMP/c.pem" \
  -days 10000 -subj "/CN=Lead Dialer" 2>/dev/null
openssl pkcs12 -export -inkey "$TMP/k.pem" -in "$TMP/c.pem" -name dialer \
  -out keystore-backup/dialer.jks -passout "pass:$PW"
rm -rf "$TMP"
printf %s "$PW" > keystore-backup/passwort.txt

base64 -w0 keystore-backup/dialer.jks | gh secret set KEYSTORE_B64
printf %s "$PW" | gh secret set KEYSTORE_PASSWORD

echo "Fertig. Schlüssel ist bei GitHub gespeichert, Backup liegt in keystore-backup/."
