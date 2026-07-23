#!/usr/bin/env bash
# volumetap の動作ログを収集する。
#
#   ./collect_log.sh
#
# 実行したまま端末で問題の手順を再現し、終わったら Ctrl+C で停止する。
# volumetap_log.txt に記録が残る。
#
# 出力される主なログ:
#   foreground -> <package>   前面アプリが切り替わった
#   short press / gesture     タップ注入の結果
#
# WSL2 で `adb devices` に端末が出ない場合:
#   - Windows 側 PowerShell（管理者）で usbipd list / usbipd attach --busid <id>
#   - または端末の「ワイヤレスデバッグ」を有効にして
#       adb pair <IP>:<ペアリングポート>
#       adb connect <IP>:<ポート>
set -u
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"

if ! "$ADB" devices | grep -qw device; then
    echo "端末が認識されていません。上のコメントの手順を試してください。" >&2
    "$ADB" devices >&2
    exit 1
fi

"$ADB" logcat -c
echo "記録中… 問題の手順を再現してください（終了は Ctrl+C）"
"$ADB" logcat -v time VolumeTapService:V GestureDispatcher:V '*:S' | tee volumetap_log.txt
