#!/usr/bin/env bash
# AAB（Play へアップロードする成果物）を検証する。
#
#   ./verify_bundle.sh [aab]
#
# bundletool で「Play が実際に配信する APK」を生成し、その中身を確認する。
# AAB のままでは権限やリソースを直接読めないため、この手順が必要。
#
# bundletool: https://github.com/google/bundletool/releases
#   既定の探索先 $ANDROID_HOME/../bundletool.jar または $BUNDLETOOL
set -eu
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/36.0.0"
BUNDLETOOL="${BUNDLETOOL:-$(dirname "$SDK")/bundletool.jar}"
AAB="${1:-app/build/outputs/bundle/release/app-release.aab}"

[ -f "$AAB" ] || { echo "AAB が見つかりません: $AAB" >&2; echo "./gradlew :app:bundleRelease を先に実行してください。" >&2; exit 1; }
echo "対象: $AAB"
echo

fail=0
check() { if [ "$2" = "$3" ]; then printf '  ✅ %s\n' "$1"; else printf '  ❌ %s\n' "$1"; fail=1; fi; }

echo "== 署名（AAB は JAR 署名）=="
if jarsigner -verify "$AAB" >/dev/null 2>&1; then
    keytool -printcert -jarfile "$AAB" 2>/dev/null | grep -E "Owner:|SHA-?256:|Valid from" | sed 's/^/     /'
    check "署名済み" ok ok
    if [ -f release-fingerprint.txt ]; then
        want=$(tr -d '[:space:]' < release-fingerprint.txt | tr 'a-f' 'A-F' | sed 's/\(..\)/\1:/g;s/:$//')
        got=$(keytool -printcert -jarfile "$AAB" 2>/dev/null | grep -m1 -E "SHA-?256:" | awk '{print $2}')
        [ "$want" = "$got" ] && fp=ok || fp=ng
        check "アップロード鍵の指紋が release-fingerprint.txt と一致" ok "$fp"
    fi
else
    check "署名済み" ok ng
fi

[ -f "$BUNDLETOOL" ] || { echo; echo "  bundletool が無いため中身の検証をスキップします（$BUNDLETOOL）"; exit "$fail"; }

echo
echo "== Play が配信する APK の中身 =="
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
STOREPASS=$(grep '^storePassword=' keystore.properties | cut -d= -f2-)
java -jar "$BUNDLETOOL" build-apks --bundle="$AAB" --output="$tmp/u.apks" --mode=universal \
    --ks=volumetap-release.jks --ks-key-alias=volumetap \
    --ks-pass="pass:$STOREPASS" --key-pass="pass:$STOREPASS" >/dev/null
unzip -q "$tmp/u.apks" -d "$tmp/uni"
APK="$tmp/uni/universal.apk"

"$BT/aapt2" dump badging "$APK" 2>/dev/null | grep -E "^package:|uses-permission|targetSdkVersion" | sed 's/^/     /'
perms=$("$BT/aapt2" dump badging "$APK" 2>/dev/null | grep uses-permission || true)
echo "$perms" | grep -q "permission.INTERNET" && net=ng || net=ok
check "INTERNET を要求していない" ok "$net"
echo "$perms" | grep -q "QUERY_ALL_PACKAGES" && qap=ng || qap=ok
check "QUERY_ALL_PACKAGES を要求していない" ok "$qap"

n=$(python3 - "$APK" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1]); n = 0
for i in z.infolist():
    if i.filename.startswith("res/") and (b"Apache License" in z.read(i.filename)[:400] or b"MIT License" in z.read(i.filename)[:400]):
        n += 1
print(n)
PY
)
[ "$n" = 2 ] && lic=ok || lic=ng
check "MIT / Apache-2.0 の全文が配信物に含まれている" ok "$lic"

echo
[ "$fail" = 0 ] && echo "すべて通過しました。Play Console にアップロードできます。" || echo "❌ 未達の項目があります。"
exit "$fail"
