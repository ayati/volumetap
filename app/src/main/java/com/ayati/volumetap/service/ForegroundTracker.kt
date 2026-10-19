package com.ayati.volumetap.service

import android.content.pm.PackageManager

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
 * 意味のある遷移なので、そのまま記録してよい。ホーム画面アプリは LAUNCHER の入口を
 * 持たないことがある（HyperOS）ので、サービスは HOME の入口を持つアプリも一覧に加えて渡す。
 *
 * **Chrome の PWA（WebAPK）は、Chrome とは別のアプリとして扱う**（design-pwa-picker.md §2.2）。
 * PWA は Chrome のパッケージで表示されるので、パッケージ名だけでは Chrome と区別できない。
 * PWA 用の画面の `TYPE_WINDOW_STATE_CHANGED` に限り、ウィンドウのタイトル（＝PWA のアプリ名）を
 * [setWebApks] で渡された WebAPK の名前と照らし合わせる。判定できなければ Chrome のまま扱う。
 */
class ForegroundTracker {

    var current: String? = null
        private set

    /** [current] が WebAPK のとき、それを動かしているブラウザ。それ以外は null。 */
    var host: String? = null
        private set

    /** ホストのパッケージ名 → そのホストの WebAPK。 */
    private var webApks: Map<String, List<WebApk>> = emptyMap()

    /** WebAPK のパッケージ名 → WebAPK。 */
    private var webApkByPackage: Map<String, WebApk> = emptyMap()

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

    /** インストール済みの WebAPK を差し替える。[setLaunchablePackages] と同じ機会に呼び直す。 */
    fun setWebApks(apks: Collection<WebApk>) {
        webApks = apks.groupBy { it.host }
        webApkByPackage = apks.associateBy { it.packageName }
    }

    /** [className] がホストの PWA 用の画面か。タイトルを見てよいかの判定にも使う。 */
    fun isWebApkWindow(packageName: String?, className: String?): Boolean =
        packageName != null && packageName in webApks && className?.startsWith(WEBAPP_ACTIVITY_PREFIX) == true

    /**
     * ウィンドウ関連イベントを受け取る。
     *
     * @param className [stateChanged] のときの画面のクラス名。
     * @param title ウィンドウのタイトル。[isWebApkWindow] のときだけ渡す（ほかの画面では読まない）。
     * @param stateChanged `TYPE_WINDOW_STATE_CHANGED` か。
     * @return 実際に前面アプリが更新されたら true。
     */
    fun onEvent(
        packageName: String?,
        className: String? = null,
        title: String? = null,
        stateChanged: Boolean = false,
    ): Boolean {
        if (packageName.isNullOrEmpty()) return false
        if (packageName in TRANSIENT_PACKAGES) return false
        // ランチャーに出ないパッケージ＝オーバーレイ類とみなして無視する。
        launchable?.let { if (packageName !in it) return false }

        val webApk = when {
            stateChanged && isWebApkWindow(packageName, className) -> findWebApk(packageName, title)
                // タイトルの無いイベントは、表示中の PWA のものとみなす（別の PWA に移ったなら、タイトルが入る）。
                ?: if (title.isNullOrBlank() && packageName == host) return false else null
            // PWA を表示しているあいだも、ホストのパッケージで再描画やダイアログのイベントが絶えず届く。
            // ホスト自身の別の画面（タブなど）が出たときだけ、PWA から離れたとみなす。
            packageName == host && !(stateChanged && isHostScreen(className)) -> return false
            else -> null
        }
        val next = webApk?.packageName ?: packageName
        if (next == current) return false
        current = next
        // PWA の起動時は、WebAPK 自身の画面（スプラッシュなど）のイベントが、PWA 用の画面より先に届く。
        // このときもホストを決めておかないと、続くホストの再描画で前面がホストに戻ってしまう
        // （PWA の上での調整を始めた直後に閉じた。2026-10-19 に実機で発見）。
        host = (webApk ?: webApkByPackage[next])?.host
        return true
    }

    /** タイトルが一致する WebAPK。一致しない・2 件以上一致するときは決められないので null。 */
    private fun findWebApk(host: String, title: String?): WebApk? {
        if (title.isNullOrBlank()) return null
        return webApks[host].orEmpty().filter { it.label == title.trim() }.singleOrNull()
    }

    /** ダイアログなどではなく、ブラウザ自身の画面（Activity）か。PWA 用の画面は除く。 */
    private fun isHostScreen(className: String?): Boolean =
        className != null &&
            HOST_SCREEN_PREFIXES.any { className.startsWith(it) } &&
            !className.startsWith(WEBAPP_ACTIVITY_PREFIX)

    fun reset() {
        current = null
        host = null
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

        /** Chrome の PWA 用の画面（`SameTaskWebApkActivity` など）。2026-10-19 に実機で確認。 */
        const val WEBAPP_ACTIVITY_PREFIX = "org.chromium.chrome.browser.webapps."

        /** Chrome 自身の画面のクラス名（`ChromeTabbedActivity`、旧来の `…apps.chrome.Main` など）。 */
        val HOST_SCREEN_PREFIXES = listOf("org.chromium.", "com.google.android.apps.chrome.")
    }
}

/** インストール済みの PWA（WebAPK）1 件（design-pwa-picker.md §2.3）。 */
data class WebApk(
    val packageName: String,
    /** PWA を動かすブラウザ（WebAPK のメタデータ `runtimeHost`）。 */
    val host: String,
    /** アプリ名。PWA 用の画面のウィンドウのタイトルと一致する。 */
    val label: String,
) {
    companion object {
        /**
         * WebAPK なら、それを動かすブラウザのパッケージ名と名前。それ以外は null。
         * サービス（判定）と画面（一覧の表示）で共用する。
         */
        fun hostOf(pm: PackageManager, packageName: String): Pair<String, String?>? {
            if (!packageName.startsWith(PACKAGE_PREFIX)) return null
            val meta = runCatching { pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA) }
                .getOrNull()?.metaData ?: return null
            val host = meta.getString(HOST_META) ?: return null
            return host to meta.getString(HOST_NAME_META)
        }

        /** Chrome が作る WebAPK のパッケージ名の接頭辞。メタデータを読む前の絞り込みに使う。 */
        const val PACKAGE_PREFIX = "org.chromium.webapk."

        /** PWA を動かすブラウザのパッケージ名（例: `com.android.chrome`）。 */
        const val HOST_META = "org.chromium.webapk.shell_apk.runtimeHost"

        /** 同じくブラウザの名前（例: `Chrome`）。 */
        const val HOST_NAME_META = "org.chromium.webapk.shell_apk.runtimeHostApplicationName"
    }
}
