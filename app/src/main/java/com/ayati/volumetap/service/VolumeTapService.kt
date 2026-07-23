package com.ayati.volumetap.service

import android.accessibilityservice.AccessibilityService
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
import com.ayati.volumetap.data.AppSettings
import com.ayati.volumetap.data.KeyAction
import com.ayati.volumetap.data.ScreenOrientation
import com.ayati.volumetap.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        arbiter = KeyPressArbiter(this, HandlerScheduler(handler))
        gestures = GestureDispatcher(this)
        vibrator = resolveVibrator()
        scope.launch {
            SettingsRepository(this@VolumeTapService).settings.collectLatest { settings = it }
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
        if (foreground.onEvent(event.packageName?.toString())) {
            Log.i(TAG, "foreground -> ${foreground.current}")
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // PENDING を抱えたまま停止するとキーを消費した記録だけが残る（設計書 §5.1）。
        arbiter.reset()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        arbiter.reset()
        foreground.reset()
        scope.cancel()
        super.onDestroy()
    }

    // --- ArbiterHost -------------------------------------------------------

    /** 設計書 §5.1「介入しない条件」。1 つでも該当すれば素通しする。 */
    override fun shouldIntercept(key: VolumeKey): Boolean {
        val s = settings
        // クイック設定タイルからの一時停止。最優先で素通しする。
        if (s.paused) return false
        if (s.actionFor(key) != KeyAction.TAP) return false

        // 自アプリの設定画面・座標登録画面では介入しない。
        val front = foreground.current
        if (front == packageName) return false

        // アプリ絞り込みが設定されていれば、対象外のアプリでは介入しない。
        val allowed = s.enabledPackages
        if (allowed.isNotEmpty() && front !in allowed) return false

        // 「TAP に設定したが座標が未登録」で音量まで効かなくなる状態を作らない。
        return s.tapPointFor(key, currentOrientation()) != null
    }

    override fun longPressMs(): Long = settings.longPressMs.toLong()

    override fun onShortPress(key: VolumeKey) {
        // shouldIntercept を通っている以上ここで null にはならないが、
        // 押下中に設定が変わる可能性があるので取り直して確認する。
        val point = settings.tapPointFor(key, currentOrientation())
        if (point == null) {
            Log.w(TAG, "short press: $key but tap point disappeared")
            return
        }
        if (gestures.tap(point.ratioX, point.ratioY) && settings.hapticFeedback) {
            performHapticFeedback()
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
    }
}
