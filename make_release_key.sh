#!/usr/bin/env bash
# リリース署名鍵を作成する。初回のみ実行する。
#
#   ./make_release_key.sh
#
# ⚠️ この鍵は一度作ったら変更できない。
#   - 鍵を変えると、野良配布した APK からの上書き更新ができなくなる
#   - Play App Signing に登録したあとも、アップロード鍵として使い続ける
#   - 紛失すると同一アプリとして更新を出せなくなる
#
# 作成される volumetap-release.jks と keystore.properties は
# .gitignore 済み。**必ず別の場所にバックアップすること。**
set -eu

KEYSTORE=volumetap-release.jks
ALIAS=volumetap
PROPS=keystore.properties

if [ -e "$KEYSTORE" ]; then
    echo "$KEYSTORE は既に存在します。上書きすると既存の APK を更新できなくなります。" >&2
    echo "作り直す場合は手動で退避してから再実行してください。" >&2
    exit 1
fi

read -rsp "キーストアのパスワード（8文字以上）: " STORE_PASS; echo
read -rsp "もう一度入力: " STORE_PASS2; echo
if [ "$STORE_PASS" != "$STORE_PASS2" ]; then
    echo "パスワードが一致しません。" >&2
    exit 1
fi
if [ ${#STORE_PASS} -lt 8 ]; then
    echo "8文字以上にしてください。" >&2
    exit 1
fi

keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 \
    -validity 10950 \
    -storepass "$STORE_PASS" \
    -keypass "$STORE_PASS" \
    -dname "CN=N.Aono, O=ayati, C=JP"

umask 077
cat > "$PROPS" <<PROPS_EOF
storeFile=$KEYSTORE
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$STORE_PASS
PROPS_EOF

echo
echo "作成しました: $KEYSTORE / $PROPS"
echo "有効期限は 30 年（Play の要件は 2033-10-22 以降まで有効なこと）。"
echo
echo "⚠️ $KEYSTORE と $PROPS を安全な場所にバックアップしてください。"
echo "   紛失すると、このアプリの更新を出せなくなります。"
echo
echo "次: ./gradlew :app:assembleRelease で署名済み APK を作成できます。"
