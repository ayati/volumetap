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
 * ⚠️ **「前に出たパッケージ＝ユーザーが使っているアプリ」ではない。**
 * エッジパネル・フローティングランチャーのような透過オーバーレイは、
 * ユーザーが対象アプリを見たまま操作しているのに自分のパッケージ名で
 * イベントを流す。これを前面アプリとして記録すると絞り込みから外れ、
 * 「Chrome を読んでいるのにときどきタップにならず音量が動く」という
 * 取りこぼしになる（2026-08-18 に対策）。
 *
 * 対策として [setLaunchablePackages] で渡されたランチャー起動可能なアプリ
 * 以外は前面アプリの変化として扱わず、直前のアプリを維持する。
 * 通知シェード・クイック設定（`com.android.systemui`）も同じ理由で無視する。
 *
 * ランチャーは無視しない。ホームに戻ったことは「対象アプリから離れた」という
 * 意味のある遷移なので、そのまま記録してよい。
 */
class ForegroundTracker {

    var current: String? = null
        private set

    /**
     * ランチャーから起動できるアプリのパッケージ名。
     * null は未取得を意味し、そのあいだは絞り込まない（取得は非同期で、
     * 揃う前に届いたイベントを捨てると前面アプリが空のままになるため）。
     */
    private var launchable: Set<String>? = null

    /**
     * ランチャー起動可能なアプリの一覧を差し替える。
     * アプリのインストール・アンインストールのたびに呼び直す。
     * 空集合は「取得できなかった」とみなし、絞り込みを行わない。
     */
    fun setLaunchablePackages(packages: Set<String>) {
        launchable = packages.ifEmpty { null }
    }

    /**
     * ウィンドウ関連イベントを受け取る。
     *
     * @return 実際に前面アプリが更新されたら true。
     */
    fun onEvent(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        if (packageName in TRANSIENT_PACKAGES) return false
        // ランチャーに出ないパッケージ＝オーバーレイ類とみなして無視する。
        launchable?.let { if (packageName !in it) return false }
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
         *
         * ランチャーには出ないので一覧取得後は自動的に弾かれるが、取得が済むまでの
         * あいだも確実に無視したいので明示しておく。
         */
        val TRANSIENT_PACKAGES = setOf("com.android.systemui")
    }
}
