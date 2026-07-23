package com.ayati.volumetap.service

/**
 * 前面アプリのパッケージ名を追跡する（設計書 §5.4）。
 *
 * ウィンドウ関連イベントのパッケージ名だけを見る。画面の内容は読まない。
 *
 * ⚠️ **`TYPE_WINDOW_STATE_CHANGED` だけを購読してはいけない。**
 * アプリ履歴から対象アプリに戻ったとき、端末によっては state-changed が
 * 発火せず前面アプリが古いまま固まる。するとアプリ絞り込みに一致せず、
 * ホーム経由で起動し直すまで機能しない、という分かりにくい不具合になる
 * （2026-07-23 の実機確認で発見・修正）。
 * `typeWindowContentChanged` / `typeWindowsChanged` も併せて購読することで、
 * 対象アプリが再描画した時点で追従できる。
 *
 * 通知シェード・クイック設定は `com.android.systemui` として通知されるが、
 * これらは「ユーザーが使っているアプリ」ではないので無視する。
 *
 * ランチャーは無視しない。ホームに戻ったことは「対象アプリから離れた」という
 * 意味のある遷移なので、そのまま記録してよい。
 */
class ForegroundTracker {

    var current: String? = null
        private set

    /**
     * ウィンドウ関連イベントを受け取る。
     *
     * @return 実際に前面アプリが更新されたら true。
     */
    fun onEvent(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        if (packageName in TRANSIENT_PACKAGES) return false
        if (packageName == current) return false
        current = packageName
        return true
    }

    fun reset() {
        current = null
    }

    companion object {
        /**
         * 一時的に前面へ出るだけで、ユーザーが「使っているアプリ」ではないもの。
         * 通知シェード・クイック設定・アプリ履歴はいずれもこのパッケージ。
         */
        val TRANSIENT_PACKAGES = setOf("com.android.systemui")
    }
}
