package com.ayati.volumetap.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ayati.volumetap.R
import com.ayati.volumetap.data.AppSettings
import com.ayati.volumetap.data.DragLane
import com.ayati.volumetap.data.ScreenOrientation
import com.ayati.volumetap.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ボリュームキーを横取りする [AccessibilityService]（設計書 §4.1）。
 *
 * `res/xml/accessibility_service_config.xml` で `canRequestFilterKeyEvents` と
 * `accessibilityFlags="flagRequestFilterKeyEvents"` の**両方**を宣言しないと
 * [onKeyEvent] は一度も呼ばれない。
 */
class VolumeTapService : AccessibilityService(), ArbiterHost {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var audioManager: AudioManager
    private lateinit var arbiter: KeyPressArbiter
    private lateinit var gestures: GestureDispatcher
    private var vibrator: Vibrator? = null

    /**
     * [onKeyEvent] は同期的に真偽を返す必要があるため、設定は Flow から吸い上げて
     * ここに保持する。書き込み・読み出しともメインスレッド。
     */
    private var settings = AppSettings()

    /** 前面アプリの追跡。誤爆防止にのみ使う（画面内容は読まない）。 */
    private val foreground = ForegroundTracker()

    /**
     * アプリのインストール・削除・更新を受けて、ランチャー起動可能なアプリの
     * 一覧を取り直す。取り直さないと、サービス起動後に入れたアプリが
     * オーバーレイ扱いのまま無視され続ける。
     */
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshLaunchablePackages()
    }

    /** 対象アプリの上での調整（design-pageturn.md §7.3）。 */
    private lateinit var calibration: CalibrationSession
    private lateinit var overlay: CalibrationOverlay
    private lateinit var repository: SettingsRepository

    /** 画面が消えたら調整をやめる（§7.3.6）。 */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (calibration.isActive) closeCalibration(R.string.calibration_closed_screen_off)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        arbiter = KeyPressArbiter(this, HandlerScheduler(handler))
        gestures = GestureDispatcher(this)
        vibrator = resolveVibrator()
        repository = SettingsRepository(this)
        calibration = CalibrationSession(packageName)
        overlay = CalibrationOverlay(this, overlayListener)
        scope.launch {
            repository.settings.collectLatest { settings = it }
        }
        scope.launch {
            CalibrationRequests.requests.collect { request ->
                if (request != null) CalibrationRequests.take()?.let(::startCalibration)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "failed to watch screen off", it) }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            // ランチャーアイコンの有効・無効の切り替えもここに来る。
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // 受け取るのはシステムからのブロードキャストだけなので NOT_EXPORTED。
        // targetSdk 34 以降はフラグの明示が必要（ACTION_PACKAGE_* は
        // protected-broadcast なので免除される条件だが、依存すべきでない）。
        //
        // 万一失敗してもサービスごと落とさない。登録できない場合の影響は
        // 「サービス起動後に入れたアプリが次の起動まで一覧に反映されない」だけ。
        runCatching {
            ContextCompat.registerReceiver(
                this,
                packageChangeReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "failed to watch package changes", it) }
        refreshLaunchablePackages()
    }

    /**
     * ランチャーから起動できるアプリを列挙して [ForegroundTracker] に渡す
     * （設計書 §5.4「透過オーバーレイを前面アプリと誤認しない」）。
     *
     * `QUERY_ALL_PACKAGES` は使わない。マニフェストの `<queries>` で
     * ランチャー起動可能なアプリだけに可視性を絞ってあり、アプリを追加する画面の
     * 一覧と同じ範囲になる。
     */
    private fun refreshLaunchablePackages() {
        scope.launch {
            val packages: Set<String> = withContext(Dispatchers.IO) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                try {
                    packageManager
                        // MATCH_DEFAULT_ONLY は付けない（AppPickerActivity と同じ理由）。
                        .queryIntentActivities(intent, 0)
                        .mapTo(HashSet()) { it.activityInfo.packageName }
                } catch (e: RuntimeException) {
                    // 取得できなければ絞り込みを諦める（従来どおり全パッケージを追う）。
                    Log.w(TAG, "failed to list launchable packages", e)
                    emptySet()
                }
            }
            foreground.setLaunchablePackages(packages)
            Log.i(TAG, "launchable packages: ${packages.size}")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "service connected")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VolumeKey.UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeKey.DOWN
            else -> return false
        }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> arbiter.onKeyDown(key, event.repeatCount)
            KeyEvent.ACTION_UP -> arbiter.onKeyUp(key)
            else -> false
        }
    }

    /**
     * ウィンドウ関連イベントから前面アプリだけを拾う。画面の内容は読まない
     * （`canRetrieveWindowContent` を宣言していないため、そもそも読めない）。
     *
     * 遷移が起きたときだけログを出す。`TYPE_WINDOW_CONTENT_CHANGED` は
     * 発火頻度が高いため、1 イベントごとの出力はしない。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        // 調整の重ね表示のイベントで、前面アプリを volumetap に上書きさせない（§7.3.6）。
        if (calibration.isOwnOverlayEvent(pkg, event.className?.toString())) return
        if (foreground.onEvent(pkg)) {
            Log.i(TAG, "foreground -> ${foreground.current}")
            onForegroundChanged()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // PENDING を抱えたまま停止するとキーを消費した記録だけが残る（設計書 §5.1）。
        arbiter.reset()
        gestures.reset()
        calibration.close()
        overlay.hide()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageChangeReceiver) }
        runCatching { unregisterReceiver(screenOffReceiver) }
        calibration.close()
        overlay.hide()
        arbiter.reset()
        foreground.reset()
        scope.cancel()
        super.onDestroy()
    }

    // --- ArbiterHost -------------------------------------------------------

    /** design-pageturn.md §6.2「介入する条件」。実行できない送り方式は素通しする。 */
    override fun shouldIntercept(key: VolumeKey): Boolean = resolve(key) != null

    override fun longPressMs(): Long = settings.longPressMs.toLong()

    override fun onShortPress(key: VolumeKey) {
        // shouldIntercept を通っている以上ここで null にはならないが、
        // 押下中に設定や前面アプリが変わる可能性があるので取り直して確認する。
        val action = resolve(key)
        if (action == null) {
            Log.w(TAG, "short press: $key but nothing to do anymore")
            return
        }
        if (overlay.isShowing) {
            // 調整の重ね表示が出ているあいだは、重ね表示がジェスチャを受け取らないようにして出す。
            overlay.runUntouchable { done -> dispatch(action, done) }
            if (settings.hapticFeedback) performHapticFeedback()
            return
        }
        if (dispatch(action, null) && settings.hapticFeedback) performHapticFeedback()
    }

    private fun dispatch(action: ResolvedAction, onDone: (() -> Unit)?): Boolean = when (action) {
        is ResolvedAction.Tap -> gestures.tap(action.x, action.y, onDone)
        is ResolvedAction.Drag -> gestures.drag(action.fromX, action.fromY, action.toX, action.toY, onDone)
    }

    private fun resolve(key: VolumeKey): ResolvedAction? = ProfileResolver.resolve(
        settings = settings,
        foreground = foreground.current,
        ownPackage = packageName,
        key = key,
        orientation = currentOrientation(),
        calibration = calibration.override(),
    )

    // --- 対象アプリの上での調整（design-pageturn.md §7.3） ------------------

    private fun startCalibration(request: CalibrationRequests.Request) {
        Log.i(TAG, "calibration requested: $request")
        overlay.hide()
        calibration.start(request.packageName, request.direction)
        // 対象アプリがいつまでも前面に来なければ（起動に失敗した、など）やめる。
        handler.removeCallbacksAndMessages(WAIT_TOKEN)
        handler.postAtTime(
            {
                if (calibration.isActive && !calibration.seenTarget) {
                    Log.i(TAG, "calibration: target never came to front")
                    calibration.close()
                    overlay.hide()
                }
            },
            WAIT_TOKEN,
            SystemClock.uptimeMillis() + WAIT_FOR_TARGET_MS,
        )
        onForegroundChanged()
    }

    private fun onForegroundChanged() {
        if (!calibration.isActive) return
        if (calibration.onForeground(foreground.current)) {
            closeCalibration(R.string.calibration_closed_left)
            return
        }
        val target = calibration.target ?: return
        if (calibration.phase == CalibrationSession.Phase.WAITING && foreground.current == target) {
            overlay.showBand(appLabel(target))
        }
    }

    private fun closeCalibration(messageRes: Int?) {
        Log.i(TAG, "calibration closed")
        calibration.close()
        overlay.hide()
        handler.removeCallbacksAndMessages(WAIT_TOKEN)
        messageRes?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (calibration.onOrientation(currentOrientation())) {
            overlay.hide()
            handler.removeCallbacksAndMessages(WAIT_TOKEN)
            Toast.makeText(this, R.string.calibration_closed_rotated, Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayListener = object : CalibrationOverlay.Listener {
        override fun onBegin() {
            val target = calibration.target ?: return
            if (!calibration.begin(currentOrientation(), settings.profileFor(target))) return
            val lane = calibration.lane ?: return
            overlay.showAdjust(lane, calibration.direction, ScreenMetrics.dragStartRange(this@VolumeTapService))
        }

        override fun onCancel() = closeCalibration(null)

        override fun onSave() {
            val target = calibration.target ?: return
            val orientation = calibration.orientation ?: return
            val lane = calibration.laneToSave()
            scope.launch {
                repository.updateProfile(target) { it.withDragLane(orientation, lane) }
            }
            Log.i(TAG, "calibration saved: $target $orientation $lane")
            closeCalibration(R.string.calibration_saved)
        }

        override fun onTest() {
            val lane = calibration.lane ?: return
            val forward = calibration.direction == DragDirection.FORWARD
            overlay.runUntouchable { done ->
                if (forward) {
                    gestures.drag(lane.x, lane.forwardFrom, lane.x, lane.forwardTo, done)
                } else {
                    gestures.drag(lane.x, lane.backwardFrom, lane.x, lane.backwardTo, done)
                }
            }
        }

        override fun onReset() {
            calibration.resetToFallback()
            calibration.lane?.let { overlay.update(it, calibration.direction) }
        }

        override fun onSelectDirection(direction: DragDirection) {
            calibration.selectDirection(direction)
            calibration.lane?.let { overlay.update(it, direction) }
        }

        override fun onLaneChanged(lane: DragLane) {
            calibration.updateLane(lane)
            overlay.update(lane, calibration.direction)
        }
    }

    private fun performHapticFeedback() {
        val effect = VibrationEffect.createOneShot(
            HAPTIC_DURATION_MS,
            VibrationEffect.DEFAULT_AMPLITUDE,
        )
        vibrator?.vibrate(effect)
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

    override fun onVolumeStep(key: VolumeKey) {
        // 長押しで音量操作が始まったら、順番待ちの送りはもう要らない。
        gestures.dropPending()
        val direction = when (key) {
            VolumeKey.UP -> AudioManager.ADJUST_RAISE
            VolumeKey.DOWN -> AudioManager.ADJUST_LOWER
        }
        // USE_DEFAULT_STREAM_TYPE により、対象ストリームの選択を OS と同じ規則に委ねる。
        audioManager.adjustSuggestedStreamVolume(
            direction,
            AudioManager.USE_DEFAULT_STREAM_TYPE,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    private fun currentOrientation(): ScreenOrientation =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ScreenOrientation.LANDSCAPE
        } else {
            ScreenOrientation.PORTRAIT
        }

    /** [Handler] による [Scheduler] 実装。token 単位で予約を置き換え・取り消しする。 */
    private class HandlerScheduler(private val handler: Handler) : Scheduler {
        override fun postDelayed(token: Any, delayMs: Long, action: () -> Unit) {
            handler.removeCallbacksAndMessages(token)
            handler.postAtTime(action, token, SystemClock.uptimeMillis() + delayMs)
        }

        override fun cancel(token: Any) {
            handler.removeCallbacksAndMessages(token)
        }
    }

    companion object {
        private const val TAG = "VolumeTapService"

        /** タップ発火時の触覚フィードバックの長さ。 */
        private const val HAPTIC_DURATION_MS = 20L

        /** 調整を依頼してから、対象アプリが前面に来るのを待つ時間。 */
        private const val WAIT_FOR_TARGET_MS = 30_000L
        private val WAIT_TOKEN = Any()
    }
}
