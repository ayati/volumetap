#!/usr/bin/env bash
# リリース前チェック（設計書 §13.5）を機械的に検証できる範囲で確認する。
#
#   ./verify_release.sh [apk]
#
# 既定では最新のリリース APK を対象にする。
set -eu
BT="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/36.0.0"
APK="${1:-}"
if [ -z "$APK" ]; then
    APK=$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -1 || true)
fi
[ -n "$APK" ] && [ -f "$APK" ] || { echo "APK が見つかりません。./gradlew :app:assembleRelease を先に実行してください。" >&2; exit 1; }

echo "対象: $APK"
echo

fail=0
check() { # 説明, 期待(ok|ng), 実際
    if [ "$2" = "$3" ]; then printf '  ✅ %s\n' "$1"; else printf '  ❌ %s\n' "$1"; fail=1; fi
}

echo "== 権限 =="
perms=$("$BT/aapt2" dump badging "$APK" | grep "uses-permission" || true)
echo "$perms" | sed 's/^/     /'
echo "$perms" | grep -q "android.permission.INTERNET" && net=ng || net=ok
check "INTERNET を要求していない" ok "$net"
echo "$perms" | grep -q "QUERY_ALL_PACKAGES" && qap=ng || qap=ok
check "QUERY_ALL_PACKAGES を要求していない" ok "$qap"

echo
echo "== ライセンス全文の同梱 =="
# リリースビルドではリソースパスが短縮されるため、内容で探す。
found=$(python3 - "$APK" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
n = 0
for i in z.infolist():
    if not i.filename.startswith("res/"):
        continue
    head = z.read(i.filename)[:400]
    if b"Apache License" in head or b"MIT License" in head:
        print(f"     {i.filename}  {i.file_size} bytes")
        n += 1
print(f"COUNT={n}")
PY
)
echo "$found" | grep -v '^COUNT=' || true
[ "$(echo "$found" | grep '^COUNT=' | cut -d= -f2)" = "2" ] && lic=ok || lic=ng
check "MIT / Apache-2.0 の全文が同梱されている" ok "$lic"

echo
echo "== 依存とライセンス一覧の整合 =="
deps=$(grep -cE '^\s+implementation\(libs\.' app/build.gradle.kts)
listed=$(grep -c '^    LibraryNotice(' app/src/main/java/com/ayati/volumetap/data/ThirdPartyLicenses.kt)
echo "     build.gradle.kts の implementation: $deps 件"
echo "     THIRD_PARTY_LIBRARIES の登録: $listed 件"
echo "     ※ 件数は 1 対 1 対応しない（BOM やまとめ表記があるため）。目視で確認すること。"

echo
echo "== 署名 =="
if "$BT/apksigner" verify --print-certs "$APK" >/dev/null 2>&1; then
    "$BT/apksigner" verify -v --print-certs "$APK" | sed 's/^/     /'
    check "署名済み" ok ok
    "$BT/apksigner" verify -v "$APK" | grep -q "v2 scheme (APK Signature Scheme v2): true" && v2=ok || v2=ng
    check "v2 署名が有効" ok "$v2"
    # v3 は既定で無効。将来の鍵ローテーションに必要なので明示的に有効化している。
    "$BT/apksigner" verify -v "$APK" | grep -q "v3 scheme (APK Signature Scheme v3): true" && v3=ok || v3=ng
    check "v3 署名が有効（鍵ローテーション対応）" ok "$v3"

    # 想定した鍵で署名されているか（鍵の取り違えを検出する）
    if [ -f release-fingerprint.txt ]; then
        want=$(cat release-fingerprint.txt)
        got=$("$BT/apksigner" verify --print-certs "$APK" | grep -m1 "SHA-256 digest" | awk "{print \$NF}")
        [ "$want" = "$got" ] && fp=ok || fp=ng
        check "署名鍵の指紋が release-fingerprint.txt と一致" ok "$fp"
        [ "$fp" = ng ] && echo "     期待: $want" && echo "     実際: $got"
    fi
else
    echo "     未署名"
    check "署名済み（./make_release_key.sh 実行後に再ビルド）" ok ng
fi

echo
[ "$fail" = 0 ] && echo "すべて通過しました。" || echo "❌ 未達の項目があります。"
exit "$fail"
