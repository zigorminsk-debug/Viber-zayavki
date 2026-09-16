#!/usr/bin/env bash
# Создаёт keystore для подписи release-сборки (одноразово).
set -e
cd "$(dirname "$0")/.."
mkdir -p keystore
KS=keystore/viberlead.jks
if [ -f "$KS" ]; then
  echo "keystore уже существует: $KS"
  exit 0
fi
keytool -genkeypair -v \
  -keystore "$KS" \
  -alias viberlead \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass viberlead -keypass viberlead \
  -dname "CN=ViberLead, OU=Mobile, O=ViberLead, L=Minsk, ST=Minsk, C=BY"
echo "keystore создан: $KS"
