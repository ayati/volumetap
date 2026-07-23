#!/usr/bin/env bash
# 既存の署名鍵を Play App Signing 用に書き出す（PEPK）。
#
#   ./export_upload_key.sh <pepk.jar> <暗号化キー>
#
# 引数:
#   pepk.jar     Play Console「アプリの署名」画面からダウンロードする
#   暗号化キー   同じ画面に表示される。次のどちらかの形式:
#                  - .pem ファイルのパス（新しい形式）
#                  - 長い16進文字列（従来形式。eb10fe8f7c7c9df7... のような値）
#
# 出力: upload_key.zip → Play Console にアップロードする
#
# ⚠️ Play Console に表示される公式のコマンドが最優先。
#    画面の指示と食い違う場合はそちらに従うこと。
set -eu

PEPK="${1:-}"
ENCKEY="${2:-}"
KEYSTORE=volumetap-release.jks
ALIAS=volumetap
OUT=upload_key.zip

if [ -z "$PEPK" ] || [ -z "$ENCKEY" ]; then
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
fi
[ -f "$PEPK" ] || { echo "pepk.jar が見つかりません: $PEPK" >&2; exit 1; }
[ -f "$KEYSTORE" ] || { echo "$KEYSTORE が見つかりません。" >&2; exit 1; }
[ -e "$OUT" ] && { echo "$OUT が既に存在します。退避してから再実行してください。" >&2; exit 1; }

read -rsp "キーストアのパスワード: " PASS; echo

if [ -f "$ENCKEY" ]; then
    # 新しい形式: 公開鍵の .pem ファイル
    java -jar "$PEPK" \
        --keystore="$KEYSTORE" --alias="$ALIAS" \
        --output="$OUT" --include-cert \
        --rsa-aes-encryption --encryption-key-path="$ENCKEY" \
        --signing-keystore-password="$PASS" --signing-key-password="$PASS"
else
    # 従来形式: 16進の暗号化キー
    java -jar "$PEPK" \
        --keystore="$KEYSTORE" --alias="$ALIAS" \
        --output="$OUT" --include-cert \
        --encryptionkey="$ENCKEY" \
        --signing-keystore-password="$PASS" --signing-key-password="$PASS"
fi

echo
echo "作成しました: $OUT"
echo "Play Console の「アプリの署名」画面でアップロードしてください。"
echo
echo "この zip には暗号化された秘密鍵が入っています。"
echo "アップロード後は削除してかまいません（.gitignore 済み）。"
