# volumetap 設計書

ボリュームボタンの短押しで、設定した位置をタップする。それだけのアプリ。
長押しは本来の音量調整として温存する。

- パッケージ名: `com.ayati.volumetap`
- ライセンス: MIT（yomikake に準拠）／名称・アイコンは対象外（§13.1）
- プライバシーポリシー: https://www.ayati.com/privacy.html
- 想定初出: https://github.com/ayati/yomikake の補助ツールとして APK 直配布

---

## 1. 目的と設計思想

ePub リーダー yomikake をはじめとする「画面タップでページを送る」アプリを、
ボリュームボタンで操作できるようにする。片手持ち・手袋・寝転び読書・
指の可動が制限される状況での読書を想定する。

Play ストアには多機能なボタンリマッパーが広告付き・有償で複数登録されている。
本アプリはそれらと機能競争をしない。**明確に差別化するのは以下の 4 点**。

| 軸 | 本アプリ |
|---|---|
| 機能 | ボリュームキー短押し → 指定座標タップ、**それだけ** |
| 広告 | なし。`INTERNET` 権限そのものを持たない |
| 課金 | なし。全機能無料 |
| ソース | オープンソース（MIT） |

既存アプリの不満点への回答:

| 既存アプリの不満 | 本アプリの方針 |
|---|---|
| 広告視聴が煩雑 | 広告なし。ネットワーク権限を持たないので技術的に不可能 |
| 思ったようにタップしない | 座標を比率で保存し、向き・解像度が変わっても追従。誤爆防止のアプリ絞り込みを用意 |
| タップ機能だけ別途有料 | 完全無料・課金なし |
| 設定項目が多すぎて分からない | 設定は 1 画面に収まる。概念は「短押し＝タップ / 長押し＝音量」だけ |

### 1.1 スコープ外（意図的に実装しないもの）

機能追加の誘惑に対する歯止めとして明記しておく。

- **PageUp / PageDown 相当のスクロール操作**
  他アプリへ任意の `KeyEvent` を注入する公開 API は存在せず（`INJECT_EVENTS` は
  signature 権限）、`ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_BACKWARD` による代替は
  対象アプリの実装に依存して無反応になる。**ユーザーから見て「効いたり効かなかったりする」
  機能は、単機能アプリの信頼性を損なう**ため採用しない
- 電源キー・その他ハードキーのリマップ
- ダブルタップ、長押しへの機能割り当て（長押しは音量固定）
- スワイプ・ピンチ等、タップ以外のジェスチャ注入
- マクロ、複数座標の連続タップ
- プロファイル切り替え、アプリごとの別座標

## 2. 対象環境

| 項目 | 値 |
|---|---|
| minSdk | 26 (Android 8.0) |
| targetSdk / compileSdk | 36 (Android 16) |
| 言語 | Kotlin |
| UI | Jetpack Compose (Material 3) |
| 永続化 | Jetpack DataStore (Preferences) |
| ビルド | Gradle 8.14.x + AGP (compileSdk 36 対応版) |

minSdk 26 は、E-ink リーダー端末（Android 8〜11 が多い）を切り捨てないための下限。
タップ注入に使う `dispatchGesture()` は API 24 からなので、この下限で問題ない。

開発環境は WSL2。エミュレータは実用的でないため、実機を usbipd-win 経由の USB か
Wi-Fi デバッグ（`adb pair`）で接続してテストする。

### 2.1 ビルド構成（実装時点）

バージョンは `gradle/libs.versions.toml` に集約する。

| 項目 | バージョン |
|---|---|
| AGP | 8.13.2 |
| Kotlin | 2.4.10 |
| Gradle | 8.14.5（wrapper） |
| Compose BOM | 2026.06.01 |
| DataStore Preferences | 1.2.1 |
| JVM ターゲット | 17 |

```bash
./gradlew :app:testDebugUnitTest   # 状態機械の単体テスト
./gradlew :app:assembleDebug       # デバッグ APK
./gradlew installDebug             # 実機へインストール
```

## 3. 機能要件

### 3.1 コア機能

音量＋ / 音量− のそれぞれに対し、**短押し時の動作**を独立に設定できる。

| モード | 動作 |
|---|---|
| `NORMAL` | 本来の音量調整（キーを横取りせず OS に素通し） |
| `TAP` | 登録済み座標を 1 回タップ |

**長押しは常に本来の音量調整**（`TAP` モードではキーを消費するため自前で再現する。3.3 参照）。

初期状態は両キーとも `NORMAL`。つまり**インストール直後・ユーザー補助を有効にした直後は
何も起きない**。ユーザーが明示的に割り当てるまで、端末の挙動は一切変わらない。

### 3.2 座標登録

- 全画面の白い Activity を表示し、ユーザーが 1 回タップした位置を登録する
- 登録値は**画面サイズに対する比率 (0.0〜1.0)** で保存する
- 登録時の画面の向き（`PORTRAIT` / `LANDSCAPE`）も併せて保存する
- 縦持ち・横持ちで別々の座標を登録できる（横持ち座標が未登録なら縦の比率を流用）
- 登録直後に確認とテスト発火を出す

### 3.3 音量フォールバック

`TAP` モードではキーイベントを消費してしまうため、長押し時の音量調整を
自前で実装する。システム標準に寄せるため以下を使う。

```kotlin
audioManager.adjustSuggestedStreamVolume(
    direction,                              // ADJUST_RAISE / ADJUST_LOWER
    AudioManager.USE_DEFAULT_STREAM_TYPE,   // 再生中ストリームを OS と同じ規則で選択
    AudioManager.FLAG_SHOW_UI               // 標準の音量ポップアップを表示
)
```

`USE_DEFAULT_STREAM_TYPE` を使うことで、音楽再生中は STREAM_MUSIC、それ以外は
着信音、といった OS の切り替えをそのまま踏襲できる。

## 4. アーキテクチャ

```
com.ayati.volumetap
├── service/
│   ├── VolumeTapService.kt      AccessibilityService 本体
│   ├── VolumeTapTileService.kt  クイック設定タイル（一時停止）
│   ├── KeyPressArbiter.kt       短押し/長押し判定の状態機械（純ロジック・テスト対象）
│   ├── ForegroundTracker.kt     前面アプリ追跡（純ロジック・テスト対象）
│   └── GestureDispatcher.kt     dispatchGesture ラッパー
├── data/
│   ├── Settings.kt              設定データモデル
│   └── SettingsRepository.kt    DataStore 読み書き
├── ui/
│   ├── MainActivity.kt          設定画面
│   ├── PickPointActivity.kt     座標登録用の全画面 Activity
│   ├── LicensesActivity.kt      オープンソースライセンス表示（§13）
│   └── components/
└── res/raw/
    ├── license_mit.txt          MIT 全文（自アプリ用）
    └── license_apache_2_0.txt   Apache-2.0 全文（同梱ライブラリ共通）
```

`KeyPressArbiter`（短押し/長押し判定）と `ForegroundTracker`（前面アプリ追跡）を
Android 非依存の純ロジックとして切り出し、単体テストで担保する。
実機で見つかった不具合はいずれもこの 2 つに起因しており、本アプリで最も
バグりやすい箇所。**実機で不具合を見つけたら、まずここに回帰テストを足す。**

### 4.1 AccessibilityService 宣言

`res/xml/accessibility_service_config.xml`:

```xml
<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRequestFilterKeyEvents"
    android:canPerformGestures="true"
    android:canRequestFilterKeyEvents="true"
    android:isAccessibilityTool="true"
    android:notificationTimeout="100"
    android:description="@string/accessibility_service_description" />
```

- `canRequestFilterKeyEvents` — `onKeyEvent()` を受け取る**能力の宣言**
- `accessibilityFlags="flagRequestFilterKeyEvents"` — 同機能の**有効化フラグ**。
  ⚠️ **両方が必要**。`canRequestFilterKeyEvents` だけではフラグが立たず、
  `onKeyEvent()` は一度も呼ばれない。エラーも警告も出ないため原因究明が難しい、
  本アプリで最も嵌まりやすい設定
- `canPerformGestures` — `dispatchGesture()` に必須（こちらは対応するフラグ不要）
- `isAccessibilityTool="true"` — 本アプリが真正のユーザー補助ツールであることの宣言。
  Play 審査で参照される。説明文には「画面タップが困難な利用者がボリュームキーで
  代替操作するためのツール」である旨を明記する
- `typeWindowStateChanged` — 前面アプリのパッケージ名の取得のみに使う（誤爆防止）

**`canRetrieveWindowContent` は宣言しない。** スクロール機能を落としたことで
画面内容のノードツリーを読む必要がなくなった。前面アプリの判定は
`AccessibilityEvent.getPackageName()` だけで足り、これは本フラグなしで取得できる。

これは単なる簡略化ではなく実利がある。**本アプリは画面に表示されている内容を
一切読み取れない**ことがマニフェスト上で保証され、Play 審査でもユーザーへの説明でも
「何を見ているのか」という最大の懸念に技術的な回答を返せる。

## 5. 詳細設計

### 5.1 キーイベント状態機械

`onKeyEvent(event: KeyEvent): Boolean` は、音量＋/− のキーごとに独立した状態を持つ。
戻り値 `true` = 消費（OS に渡さない）、`false` = 素通し。

```
[IDLE]
  │ ACTION_DOWN (repeatCount == 0)
  │   介入しない条件（下記）に該当 → return false（OS に素通し）
  │   それ以外 → LONG_PRESS_MS 後の runnable を予約、return true
  ▼
[PENDING]
  ├─ ACTION_UP が LONG_PRESS_MS 以内 → runnable 取消 → タップ注入 → [IDLE]
  └─ runnable 発火（LONG_PRESS_MS 経過） → 音量リピート開始 → [LONG_PRESS]
  ▼
[LONG_PRESS]
  └─ ACTION_UP → リピート停止 → [IDLE]

※ ACTION_DOWN の repeatCount > 0（OS のオートリピート）は [PENDING]/[LONG_PRESS] 中は
   すべて消費して無視する（判定は自前タイマーのみで行う）
※ [PENDING]/[LONG_PRESS] 中の ACTION_UP も必ず消費する（return true）。
   DOWN だけ消費して UP を通すと、OS 側に不整合なキー状態が残る
```

**介入しない条件**（いずれか 1 つでも満たせば `ACTION_DOWN` で即 `false`）:

1. `paused` が true（クイック設定タイル / 設定画面からの一時停止）
2. そのキーのモードが `NORMAL`
3. モードが `TAP` だが、現在の画面の向きに対応する座標が未登録
   （設定ミスで音量が効かなくなる状態を作らないためのフェイルセーフ）
4. 前面アプリが自アプリ（設定画面・座標登録画面）
5. `enabledPackages` が空でなく、前面アプリがそこに含まれない
   （「前面アプリ」の判定は透過オーバーレイを除いたもの。後述の §5.4 を参照）

条件 3 が重要。**「タップに設定したのに座標がなく、音量も効かない」という
最悪の状態を構造的に作れなくする。**

Phase 1 では一時的にこの安全弁を外すデバッグ設定を置いていたが、
座標登録が入った Phase 2 で削除済み。

パラメータ:

| 定数 | 既定値 | 備考 |
|---|---|---|
| `LONG_PRESS_MS` | 300 | 設定で 200/300/400/500 から選択可 |
| `VOLUME_REPEAT_MS` | 120 | 長押し中の音量刻み間隔 |
| `TAP_DURATION_MS` | 60 | 注入するタップの押下時間 |
| `LONG_PRESS_TIMEOUT_MS` | 10000 | `[LONG_PRESS]` の上限。超過で強制 `IDLE` |

**取りこぼし対策**: `ACTION_UP` が届かないまま画面が切り替わるケースに備え、
`LONG_PRESS` 状態には上限タイマーを置き、超過したら強制的に `IDLE` へ戻す。

**タイマー実行スレッド**: `Handler(Looper.getMainLooper())` を使用。`onKeyEvent` は
メインスレッドで呼ばれるため、状態変数の排他は不要。

**サービス停止時の後始末**: `onUnbind` / `onDestroy` で全タイマーを取り消す。
`[PENDING]` を抱えたままサービスが落ちると、キーを消費した記録だけが残る。

### 5.2 タップ注入

```kotlin
val bounds = windowManager.currentWindowMetrics.bounds   // API 30+
// API 26〜29 は DisplayMetrics で代替
val x = ratioX * bounds.width()
val y = ratioY * bounds.height()

val path = Path().apply { moveTo(x, y) }
val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
dispatchGesture(
    GestureDescription.Builder().addStroke(stroke).build(),
    callback,   // onCompleted / onCancelled をログに残す
    null
)
```

`dispatchGesture` の座標は**ディスプレイ全体の座標系**である点に注意。
ステータスバー・ナビゲーションバーを含んだ実寸を取る必要があるため、
`currentWindowMetrics.bounds` を使う（`getSize()` 系は不可視領域を除外するため誤差が出る）。

### 5.3 設定データモデル

`android.provider.Settings` との衝突を避けるため、実装上の型名は
`AppSettings` / `KeyAction` / `ScreenOrientation` とする。

```kotlin
enum class KeyAction { NORMAL, TAP }
enum class ScreenOrientation { PORTRAIT, LANDSCAPE }

data class TapPoint(
    val ratioX: Float,          // 0.0 .. 1.0
    val ratioY: Float,
    val orientation: ScreenOrientation
)

data class AppSettings(
    val volumeUpAction: Action = Action.NORMAL,
    val volumeDownAction: Action = Action.NORMAL,
    val tapPointUpPortrait: TapPoint? = null,
    val tapPointUpLandscape: TapPoint? = null,
    val tapPointDownPortrait: TapPoint? = null,
    val tapPointDownLandscape: TapPoint? = null,
    val longPressMs: Int = 300,
    val hapticFeedback: Boolean = true,
    val enabledPackages: Set<String> = emptySet()   // 空 = 全アプリで有効（Phase 4）
)
```

`enabledPackages` が空でない場合、前面アプリがそこに含まれるときだけ介入する。
含まれないときは `onKeyEvent` で即 `false` を返し、完全に素通しする。

### 5.4 誤爆防止

- 自アプリ（設定画面・座標登録画面）が前面のときは介入しない
- **有効にするアプリの絞り込み** — `enabledPackages` が空なら全アプリ（既定）、
  選択されていればそのアプリが前面のときだけ横取りする
- **クイック設定タイル**（`TileService`）で一時停止をワンタップ切り替え。
  タイルが点灯＝動作中、消灯＝一時停止中。設定画面にも同じスイッチを置き、
  タイルを追加していないユーザーからも到達できるようにする
- タップ発火時に短い触覚フィードバック（`VIBRATE` 権限、設定でオフ可）

#### ⚠️ 前面アプリの追跡は `TYPE_WINDOW_STATE_CHANGED` だけでは足りない

2026-07-23 の実機確認（Galaxy S23 / One UI）で発見。以下の手順で機能しなくなっていた。

1. クイック設定で一時停止 → 対象アプリで音量が普通に動く（正しい）
2. クイック設定を開いて再開に戻す
3. **アプリ履歴から対象アプリへ戻る → 機能しない**
4. ホームに戻ってアイコンから起動し直すと復帰する

原因は、アプリ履歴から対象アプリに戻ったときに `TYPE_WINDOW_STATE_CHANGED` が
発火しないこと。アクティビティは再生成されず再開されるだけなので、
前面アプリが古いまま固まり、アプリ絞り込みに一致しなくなる。
ホームアイコンからの起動では新しくイベントが飛ぶため復帰する。

**対策**: 購読するイベントを広げる。

```xml
android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeWindowsChanged"
```

対象アプリが再描画した時点で追従できるようになり、実機で解消を確認した。

- **どのイベント種別が決め手だったかは未確定。** 実機ログを取れなかったため、
  3 種類を購読したままにしている。減らすなら実機ログで確認してから
  （`./collect_log.sh` で `foreground -> ...` の遷移が記録できる）
- `TYPE_WINDOW_CONTENT_CHANGED` は発火頻度が高いが、ハンドラは
  「null 判定 → 集合参照 → 文字列比較」だけで、パッケージ名が変わらなければ
  即 return する。`notificationTimeout=100` でも間引かれる
- あわせて通知シェード・クイック設定（`com.android.systemui`）は無視する。
  これらは「ユーザーが使っているアプリ」ではないため
- **ランチャーは無視しない。** ホームに戻ったことは「対象アプリから離れた」と
  いう意味のある遷移であり、無視するとホーム画面で誤爆する

**この不具合は単体テストでは捕まえられない。** どのイベントが配信されるかは
プラットフォーム側の挙動であり、純ロジックの外にある。`ForegroundTrackerTest` が
担保しているのは「systemui を無視する」「同一パッケージで更新しない」といった
判定規則だけで、購読イベントの過不足は**実機でしか検証できない**。

#### ⚠️ 透過オーバーレイを前面アプリと誤認しない

2026-08-18 に報告。Chrome だけを絞り込みに入れて使っていると、**ときどきタップに
ならず音量が動いてしまう**。Chrome は表示されたままなので原因が分かりにくい。

原因は、エッジパネル・フローティングランチャーのような**透過オーバーレイ**が
前に出たときに、それが自分のパッケージ名でウィンドウイベントを流すこと。
ユーザーは Chrome を見て操作しているのに前面アプリがオーバーレイ側に書き換わり、
`enabledPackages` に一致しなくなって素通しになる。

**対策**: `ForegroundTracker` は**ランチャーから起動できるアプリ以外を
前面アプリの変化として扱わない**（直前のアプリを維持する）。
一覧は `VolumeTapService` が `<queries>`（`ACTION_MAIN` / `CATEGORY_LAUNCHER`）で
取得し、`setLaunchablePackages()` で渡す。アプリ絞り込み画面の一覧と同じ範囲で、
新しい権限は不要。`QUERY_ALL_PACKAGES` も `canRetrieveWindowContent` も要らない。

- 一覧の取得は非同期。**未取得のあいだ（および取得に失敗して空だったとき）は
  絞り込まない。** ここで捨てると前面アプリが空のまま何も動かなくなる
- インストール・アンインストール・更新で一覧を取り直す
  （`ACTION_PACKAGE_ADDED` などを `registerReceiver`）。取り直さないと、
  サービス起動後に入れたアプリがオーバーレイ扱いのまま無視され続ける
- `com.android.systemui` はランチャーに出ないので一覧取得後は自動的に弾かれるが、
  取得が済むまでのあいだも確実に無視したいので `TRANSIENT_PACKAGES` は残す

**代償**: ランチャーに出ない画面が対象アプリの上に出たとき（共有シートなど）、
前面アプリは対象アプリのまま＝介入が続くため、**登録座標へのタップがその画面に
飛ぶ**。誤爆の方向としては「めくれない」より重いが、対象アプリを絞り込んでいる
ときにしか起きず、座標も 1 点に限られるため許容した。

**逆向きの設計（除外リスト）は採らなかった。** 「一覧のアプリでは動作しない、
それ以外では動作する」にすれば同じ症状は消えるが、判定を外したときに
**未知のアプリへタップが飛ぶ**——安全側ではなく危険側に転ぶ。
`enabledPackages` を許可リストにしているのは、外したときに音量へ倒れるため。

#### アプリ一覧の取得と `QUERY_ALL_PACKAGES`

**`QUERY_ALL_PACKAGES` は要求しない。** マニフェストの `<queries>` で
ランチャー起動可能なアプリ（`ACTION_MAIN` + `CATEGORY_LAUNCHER`）だけに
可視性を絞る。

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

**`queryIntentActivities` の flags は 0 にする。`MATCH_DEFAULT_ONLY` を付けてはいけない。**
付けると、ランチャー用の宣言に `CATEGORY_DEFAULT` を持たないアプリ（Poweramp など）が
すべて落ちる。v0.10.0 まではこの誤りで、試作機では 172 件中 42 件しか取れていなかった。
一覧だけでなく、透過オーバーレイ対策（前面アプリの判定）にも同じ一覧を使っているため、
漏れたアプリでは前面アプリの判定まで狂っていた（v0.10.1 で修正）。

`QUERY_ALL_PACKAGES` は Play で用途説明を求められる強い権限であり、
インストール済みアプリの全体像を取得できてしまう。本アプリに必要なのは
「ユーザーが読書に使うアプリを選ばせる」ことだけなので、`<queries>` で足りる。
`canRetrieveWindowContent` を宣言しない判断（§4.1）と同じ方針。

## 6. 画面設計

### 6.1 設定画面（MainActivity）

```
┌─────────────────────────────┐
│ volumetap                   │
├─────────────────────────────┤
│ ⚠ ユーザー補助が無効です     │  ← 未有効時のみ表示
│    [設定を開く]              │
├─────────────────────────────┤
│ 短押しの割り当て             │  ← 節見出し
│ 短押し＝タップ / 長押し＝音量 │
│ ┌─────────────────────────┐ │
│ │ 音量 ＋ の短押し         │ │
│ │  ○ 本来の音量調整        │ │
│ │  ● 指定位置をタップ      │ │
│ │    縦 (0.85, 0.50) [解除]│ │
│ │    横 未登録             │ │
│ │    [登録]                │ │
│ └─────────────────────────┘ │
│ ┌ 音量 − の短押し（同上）─┐ │
├─────────────────────────────┤
│ 動作する範囲                 │  ← 節見出し
│ 有効にするアプリ  すべてのアプリ >│
│ 一時停止                  □  │
├─────────────────────────────┤
│ 詳細設定                     │  ← 節見出し
│ 長押し判定  [200][300][400][500]│
│ 触覚フィードバック         ■ │
├─────────────────────────────┤
│ このアプリについて         > │
└─────────────────────────────┘
```

**節見出しで 3 つに分ける。** 「割り当て」「範囲」「詳細」の順に、
使う頻度が高いものを上に置く。設定を開く目的のほとんどはキーの割り当て変更なので、
それが常に最上部（サービス状態カードの直下）に来る。

当初は「1 画面・スクロールなしに収める」ことを目標にしていたが、
誤爆防止（アプリ絞り込み・一時停止）が入った時点で現実的でなくなった。
目標を **「主要操作であるキー割り当てが最初の 1 画面に収まる」** に改める。
項目の追加を検討するときは、この基準を壊さないかで判断する。

### 6.1.1 このアプリについて（AboutActivity）

- バージョン（`versionName` / `versionCode`）
- アプリの 1 行説明（広告なし・課金なし・ネットワーク接続なしを明記）
- ソースコード（GitHub）へのリンク
- プライバシーポリシーへのリンク
- ライセンスの要約（本文は Phase 5 のライセンス画面 §6.3 へ）

リンクは `ACTION_VIEW` で外部ブラウザに投げる。本アプリ自身はネットワーク権限を
持たないため、表示はブラウザ側の責任になる。ブラウザ不在の端末に備えて
`ActivityNotFoundException` を握り、トーストで知らせる。

### 6.1.2 子画面には必ず戻るボタンを置く

システムの戻るジェスチャだけでも戻れるが、画面上に手がかりが無いと不親切
（2026-07-23 の実機確認で指摘）。`TopAppBar` の `navigationIcon` に統一して置く。

戻る矢印のアイコンは `material-icons` ライブラリを追加せず、自前のベクター
（`res/drawable/ic_arrow_back.xml`）で持つ。**依存を 1 つ増やすと
ライセンス表示の維持対象も 1 つ増える**（§13.4）。アイコン 1 個のために
その負債を負う価値はない。

### 6.2 座標登録画面（PickPointActivity）

- 白背景・全画面（edge-to-edge、`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`）
- 中央に「押したい位置をタップしてください」の淡いガイド文
- 既存の登録座標があれば半透明マーカーで重ねて表示
- タップで `event.rawX / rawY` を取得 → ディスプレイ実寸で割って比率化
- 直後にマーカーと「やり直す / 決定」を表示
- 戻るキー / 端末の戻るジェスチャでキャンセル

**⚠️ 捕捉レイヤーを操作ボタンの親にしてはいけない。** 親に置くとタップが常に
親側に取られ、決定・やり直す・キャンセルのいずれも押せなくなる（マーカーが
移動するだけで先に進めない）。捕捉レイヤーとボタン群は**兄弟**に並べ、
ボタンを上に重ねる。Compose はヒットテストを最前面の子から行い最初に当たった
時点で止まるため、ボタン上のタップはボタンだけが、それ以外は捕捉レイヤーだけが
受け取る。ボタン以外の余白は捕捉レイヤーに素通しするので、画面下端の座標も
問題なく登録できる。

操作バーは、選んだ位置が画面下半分なら上端へ、そうでなければ下端へ寄せる。
これがないと下端付近を選んだときにマーカーがバーに隠れる。

**ボタンを 1 行に並べない。** 「決定 / やり直す / キャンセル」を横一列にすると、
幅の狭い端末（Galaxy S23 など）で末尾のボタンが圧縮され、ただの縦線になって
押せなくなる。`Row` は空きが足りないと子を縮めるため、端末幅に依存した壊れ方をする。
主操作（決定）は独立した行に全幅で置き、副操作（キャンセル / やり直す）だけを
横並びにする。全幅ボタンはタブレットで間延びしないよう最大幅を 320dp に制限する。

実装メモ: 生の `MotionEvent` が必要なため `Modifier.pointerInteropFilter` を使う。
Compose のローカル座標ではウィンドウ配置の影響を受けうるが、`rawX / rawY` なら
`dispatchGesture` と同じディスプレイ座標系になり、ずれが原理的に発生しない。
描画にはローカル座標（`event.x / event.y`）を使い、保存には raw を使う。

### 6.3 オープンソースライセンス画面（LicensesActivity）

同梱する Apache-2.0 ライブラリの表示義務を満たすための画面。根拠と運用は §13 に記す。

```
┌─────────────────────────────┐
│ ← オープンソースライセンス   │
├─────────────────────────────┤
│ volumetap                   │
│ MIT License                 │
│ Copyright (c) 2026 N.Aono   │
│                             │
│ ※「volumetap」の名称および   │
│ 　アプリアイコンは MIT ライ  │
│ 　センスの対象外です         │
│                             │
│ [MIT License 全文を表示 ▾]   │
├─────────────────────────────┤
│ 使用しているライブラリ       │
│                             │
│ Kotlin Standard Library      │
│ JetBrains s.r.o.             │
│ Apache License 2.0        >  │
│                             │
│ kotlinx.coroutines           │
│ JetBrains s.r.o.             │
│ Apache License 2.0        >  │
│                             │
│ AndroidX (Core, Activity,    │
│ Lifecycle, DataStore)        │
│ The Android Open Source      │
│ Project                      │
│ Apache License 2.0        >  │
│                             │
│ Jetpack Compose / Material 3 │
│ The Android Open Source      │
│ Project                      │
│ Apache License 2.0        >  │
├─────────────────────────────┤
│ [Apache License 2.0 全文 ▾]  │
└─────────────────────────────┘
```

仕様:

- **単一画面・スクロールのみ**。ライブラリごとの詳細画面には遷移させない
  （ライセンス種別が 2 種類しかないため、全文を末尾に 1 回ずつ置けば足りる）
- 各エントリは「ライブラリ名 / 著作権者 / ライセンス名」の 3 行
- ライセンス全文は展開式（`ExpandableCard` 相当）で同一画面内に表示する
- **ライセンス全文は APK に同梱し、外部リンクで代替しない**（§13.2 の理由）
- 選択・コピー可能なテキストとして表示する（`SelectionContainer`）
- 画面自体はオフラインで完結する。ネットワークアクセスは一切発生しない

## 7. yomikake との連携

yomikake は既に画面タップによるページ送りを実装済み（設定「タップページ送り」で
L字／左右の端／上下の端／ボタン の 4 種）。**volumetap 側も yomikake 側も追加対応は不要で、
`TAP` モードの座標を「次」「前」の領域に置くだけで噛み合う**。

推奨セットアップ（縦書き・縦持ち）:

| キー | 登録位置 |
|---|---|
| 音量 − | 画面**左**端の中ほど（縦書きの「次」） |
| 音量 ＋ | 画面**右**端の中ほど（縦書きの「前」） |

- yomikake の中央タップは操作メニューなので、**画面中央を避けて登録する**
- 横書きの本では「次」が右端に切り替わるため、割り当ても左右逆になる
- yomikake のタップ領域設定は「左右の端」にしておくと、上下端の誤爆がなく安定する

#### ⚠️ yomikake は PWA なので、絞り込みでは「ブラウザ」を選ぶ

2026-07-23 の実機確認で判明。yomikake をホーム画面に追加して使う場合、
PWA（WebAPK）は**ブラウザのプロセス上で動く**ため、以下の 2 点が起きる。

1. アプリ絞り込みの一覧に「yomikake」として現れない
   （`ACTION_MAIN` + `CATEGORY_LAUNCHER` のクエリに WebAPK が出てこない）
2. 前面パッケージは `com.android.chrome` として観測される

したがって**絞り込みでは Chrome（使用しているブラウザ）を選ぶ**。
実機でも Chrome を選ぶことで yomikake・Chrome の双方で動作することを確認済み。

**トレードオフ**: ブラウザを選ぶと、yomikake だけでなく通常のブラウジング中も
ボリュームキーが横取りされる。パッケージ名でしか前面アプリを判別できない以上、
URL 単位の絞り込みは原理的に不可能（それには画面内容の読み取りが必要になり、
`canRetrieveWindowContent` を宣言しない方針と正面から衝突する）。

読書以外のブラウジング時はクイック設定タイルで一時停止する運用を案内する。
この事情はアプリ絞り込み画面にヒントとして常時表示している
（気づく手がかりが無いと、一覧に yomikake が無い時点で詰まるため）。

### 7.1 yomikake 側で検討しうる強化（任意・将来）

必須ではない。Phase 2 完了後に実機で使ってみてから判断する。

- **固定タップ座標モード** — 「画面の特定 1 点をタップしたら常に次ページ」という
  割り切ったモードを yomikake 設定に足すと、書字方向が縦書き・横書きで変わっても
  volumetap 側の割り当てを変えずに済む。縦書き・横書きの本を行き来する使い方で効く

## 8. 既知の制約

README と設定画面に明記する。

| 制約 | 内容 |
|---|---|
| セキュア画面 | `FLAG_SECURE` を立てた画面（銀行アプリ、一部の動画配信）にはタップを注入できない |
| システム UI | 通知シェード、クイック設定など OS の UI 上には注入できない |
| 画面消灯時 | 消灯中のキーイベント配信は端末依存で不安定。サポート対象外とする |
| ゲームパッド等 | 外部入力機器の音量キーは端末により挙動が異なる |
| メーカー独自 | 一部端末（特定の中華 ROM）は電源管理でユーザー補助サービスを停止することがある。Xiaomi 端末は §8.1 |

### 8.1 HyperOS（Xiaomi 端末）のプロセス凍結

2026-10-17 に Redmi Note 15 5G（HyperOS 3.0）で確認。詳細な調査は
`design-pageturn.md` §12.4。

- HyperOS の省電力機能（GreezeManager）は、裏に回った volumetap を数秒で凍結する。
  ユーザー補助サービスだと認識したうえで凍結する
- 凍結中の音量キーは処理されずに溜まり、解凍された瞬間（クイック設定や通知シェードを
  開いたとき）にまとめて処理される。利用者からは「アプリを切り替えると効かなくなり、
  タイルを触ると直る」ように見える
- **アプリ情報の「電源」→ バッテリーセーバー「制限なし」で凍結は止まる**
- ⚠️ この設定を変えると、HyperOS のセキュリティアプリがアプリを**強制停止**する。
  Android は強制停止されたアプリのユーザー補助サービスをオフにするため、
  **設定後にユーザー補助を有効にし直す必要がある**

**対応（v0.10.1）**: Xiaomi 端末（`Build.MANUFACTURER`）で、省電力の除外が
されていないときだけ、設定画面の先頭に案内カードとアプリ情報へのボタンを出す。

- 「制限なし」は Android 標準の省電力除外にも反映されるため、
  `PowerManager.isIgnoringBatteryOptimizations()` で判定できる。設定済みなら出さない
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` で除外を直接求めることはしない。
  Play で用途が制限された権限で、かつ HyperOS 独自の判定（`NO_RESTRICT_APP`）に
  確実に反映される保証がない。設定画面へ案内するだけなら権限は増えない
- 案内カードはユーザー補助の状態カードより**上**に置く。強制停止でユーザー補助が
  オフになるので、「省電力 → ユーザー補助」の順に上から読めるようにする
- 「自動起動」は公開 API で読めないため判定には含めず、案内文で併記する。
  HyperOS 3.0.304 ではアプリ情報画面には無く、**設定 →「アプリ」→ 画面上部の
  「バックグラウンドでの自動起動」**にある（利用者が確認）
- フォアグラウンドサービスでの常駐は採らない。常駐通知と権限が増え、
  「権限は `VIBRATE` のみ」という説明を崩すため

## 9. テスト計画

### 9.1 単体テスト

`ForegroundTracker` に対し、以下を検証する。

- 通常のアプリ遷移を記録すること
- `com.android.systemui`（通知シェード・クイック設定・アプリ履歴）を無視すること
- **クイック設定を経由しても前面アプリが維持されること**（実機不具合の回帰テスト）
- ランチャーへの遷移は記録すること
- パッケージ名が無いイベントを無視すること

`KeyPressArbiter` に対し、以下の境界を検証する。

- 短押し（`LONG_PRESS_MS - 1`）でタップ発火、音量は動かない
- 長押し（`LONG_PRESS_MS + 1`）で音量リピート開始、タップは発火しない
- ちょうど境界値での挙動が一意に定まること
- `ACTION_UP` 欠落時に上限タイマーで `IDLE` に復帰すること
- `NORMAL` モードでは一切消費しないこと
- ＋と−を同時に押しても状態が混線しないこと

### 9.2 実機テスト

1. `NORMAL` のまま — 音量が完全に通常どおり動くこと（回帰の基準線）
2. `TAP` 有効 — yomikake でページが送られること
3. 長押し — 音量ポップアップが出て、連続で増減すること
4. 縦横回転 — 座標が追従すること
5. 他アプリ（Chrome、ホーム画面）で誤爆しないこと
6. サービス無効化 → 音量が即座に通常動作へ戻ること

## 10. 実装フェーズ

| Phase | 内容 | 完了条件 |
|---|---|---|
| **1** ✅完了 | プロジェクト雛形、AccessibilityService 骨格、キー状態機械、音量フォールバック | `NORMAL` と「消費するが音量は通常どおり」が実機で両立 ← 2026-07-23 実機確認済 |
| **2** ✅完了 | `TAP` モード、座標登録画面、比率保存 | yomikake でページ送りができる ← 2026-07-23 実機確認済 |
| **3** ✅完了 | 設定画面（Compose）、DataStore 永続化、このアプリについて | 設定が再起動後も保持される ← 2026-07-23 実機確認済 |
| **4** ✅完了 | 誤爆防止（アプリ絞り込み、QS タイル、触覚） | 他アプリで暴発しない ← 2026-07-23 実機確認済 |
| **5** ✅完了 | アイコン、README、ライセンス画面、署名、リリース検証スクリプト | 署名済み APK を実機確認済（2026-07-23）← 公開作業のみ |
| **6** 🔄進行中 | Play Console 登録（済）、AAB 作成（済）、クローズドテスト | 製品版公開 |

Phase 1 の完了条件を「音量が壊れないこと」に置いているのは、ここが最大の
体験リスクだから。キーを横取りしたまま音量が効かなくなる状態は、
ユーザーにとって最悪の不具合になる。

## 11. 配布計画

### 11.1 第一段階: APK 直配布

- GitHub Releases で署名済み APK を配布
- yomikake の README から導線を張り、テスターを募る
- リリース鍵は Phase 5 で作成し、**以後 Play 版まで同じ鍵を使い続ける**
  （鍵を変えると野良版からの上書き更新ができなくなる）

### 11.2 第二段階: Google Play

#### アプリ署名鍵の構成（2026-07-23 確定）

Play App Signing により、**署名鍵は 2 つに分かれている。**

| 鍵 | 持ち主 | 用途 | SHA-256 |
|---|---|---|---|
| アプリ署名鍵 | **Google** | Play 配信版の APK に署名 | `78:4E:0A:DE:…:10:AA` |
| アップロード鍵 | 自分（`volumetap-release.jks`） | AAB への署名。Play が本人確認に使う | `F3:D9:D7:49:…:7D:40` |

**Google 生成の鍵をそのまま使う。** 新規アプリは自動的に Google 生成の
「量子対応（従来の鍵＋ポスト量子暗号鍵のハイブリッド）」で登録される。
PEPK で自分の鍵に差し替えることもできるが、以下の理由で採用しない。

1. **ポスト量子暗号は自分の鍵では再現できない。** 署名鍵は 10 年以上使うため、ここは効く
2. **個人開発では鍵の紛失が現実的なリスク。** Google 管理ならバックアップ事故で詰まない
3. **GitHub 配布はテスター獲得の手段としては必須でなくなった。** Play の内部テストで
   100 人まで配信できるため

> 当初は「既存の鍵を Play にアップロードして署名を統一する」方針だったが、
> 実際の Play Console 画面（量子対応の表示）と、内部テストが使えるようになった
> ことを踏まえて撤回した。切り替えは製品版公開前ならいつでも可能。

**代償**: Play 配信版と GitHub 配布版は別の署名になる。利用者は一方から他方へ
上書き更新できず、切り替えには再インストールが必要で設定が消える。
この点は CHANGELOG とリリースノートに明記している。

デベロッパー確認（Android Developer Verification、2026-09-30 施行）には
アップロード鍵の指紋を登録済み。Play 配信分が別途必要になった場合は
アプリ署名鍵の指紋を追加登録する。

#### AAB の作成と検証

```bash
./gradlew :app:bundleRelease   # app/build/outputs/bundle/release/app-release.aab
./verify_bundle.sh             # 署名・権限・同梱リソースを検証
```

AAB のままでは権限やリソースを直接読めないため、`verify_bundle.sh` は
bundletool で「Play が実際に配信する APK」を生成してから中身を確認する。
AAB に入っているものが配信物に残っているとは限らないので、この確認が要る。

同梱されるネイティブライブラリは 2 つ。いずれも AOSP の Apache-2.0 で、
`THIRD_PARTY_LIBRARIES` の AndroidX / Compose の記載に含まれる。

- `libandroidx.graphics.path.so`（Compose UI の推移的依存）
- `libdatastore_shared_counter.so`（DataStore）

#### 準備物:

- [x] プライバシーポリシー URL — https://www.ayati.com/privacy.html
- [ ] Developer アカウント（$25、本人確認あり）
- [ ] AAB ビルド + Play App Signing 登録
- [ ] ストア掲載情報（アイコン、スクリーンショット、説明文）
- [ ] データセーフティ申告 → **収集なし・共有なし**（`INTERNET` 権限を持たないため）
- [ ] ユーザー補助 API の用途説明（`isAccessibilityTool` と整合させる）

注意点:

- **製品版公開の要件**（2026-07-23 に公式ドキュメントで確認）:
  - **12 人**のテスターが**継続して 14 日間**オプトインしていること
  - 対象は**クローズドテスト**。内部テストは要件に算入されない
  - 2023-11-13 以降に作成した**個人**アカウントのみが対象（組織アカウントは対象外）
  - 数え方は**オプトインした人数**であり、実際に使ったかは問われない。
    ただし途中でオプトアウトすると 14 日の連続性が切れる
  - 数値は改定されうるため、申請時に Play Console の表示を必ず確認する
- ユーザー補助サービスを使うアプリは審査が厳しい。ストア説明文の冒頭で
  「ボリュームキーで画面タップを代替するユーザー補助ツール」であることを
  明確に述べ、`isAccessibilityTool` の宣言と齟齬がないようにする。
  `canRetrieveWindowContent` を持たない点は、審査でもストア説明でも強調材料になる

### 11.3 Play 以外の配布経路

12 人 x 14 日の要件は個人開発者には重い。Play に依存しない経路も並行して確保しておく。

| 経路 | テスター要件 | 署名 | 備考 |
|---|---|---|---|
| Google Play | あり（12 人 / 14 日） | Google が管理 | 最大のリーチ |
| GitHub Releases | なし | 自前の鍵 | デベロッパー確認の登録が必要 |
| F-Droid | なし | F-Droid の鍵 | ソースからビルドされる |

**F-Droid は本アプリと相性が良い。** 掲載条件は「完全なオープンソースであること」
「プロプライエタリな依存を含まないこと」で、本アプリは以下をすべて満たす。

- MIT ライセンスでソース公開済み
- Google Play Services に依存しない
- 広告・トラッキング SDK を含まない
- ネットワーク権限すら持たない

加えて F-Droid の利用者層は「Play を避けたい・プライバシーを重視する」層であり、
本アプリの立ち位置と重なる。Play の要件が満たせない期間の受け皿になる。

ただし F-Droid は**独自の鍵で署名し直す**ため、Play 版・GitHub 版と合わせて
署名の identity が 3 つに分かれる。利用者はストアをまたいだ更新ができない。
この点は各配布ページに明記する。

## 12. 権限一覧

| 権限 | 用途 |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | サービス宣言に必須（システムが要求） |
| `VIBRATE` | タップ発火時の触覚フィードバック（設定でオフ可） |
| `BIND_QUICK_SETTINGS_TILE` | クイック設定タイルの宣言に必須（システムが要求） |

`INTERNET` は**要求しない**。これによりデータセーフティ申告が「収集なし」で完結し、
広告・トラッキングが技術的に不可能であることを権限一覧で証明できる。

`QUERY_ALL_PACKAGES` も**要求しない**（§5.4）。アプリ一覧は `<queries>` による
可視性宣言だけで取得する。

成果物の権限一覧にはもう 1 つ
`com.ayati.volumetap.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` が現れるが、
これは `androidx.core` のマニフェストが自動でマージするもので、
**自アプリが自分自身に定義する `signature` レベルの権限**。利用者には見えず、
外部のアプリには取得できない。v0.9.0 の時点で既に含まれている
（`app/build/outputs/logs/manifest-merger-release-report.txt` で出所を確認できる）。

## 13. ライセンス方針

### 13.1 自アプリのライセンス

- コード: **MIT License**、`Copyright (c) 2026 N.Aono`（yomikake と統一）
- リポジトリルートに `LICENSE` を置く

**名称とアイコンは MIT の対象外とする。** `LICENSE` の末尾と README に以下を明記する。

```
The name "volumetap" and the application icon are not covered by the MIT License.
「volumetap」の名称およびアプリアイコンは MIT ライセンスの対象外です。
```

理由: 本アプリの価値は「広告なし・無料・単機能」であり、MIT はフォークして広告を
付け再公開する行為を許す（これは GPL でも防げない。GPL はソース開示を要求するだけで
広告の追加自体は禁止できない）。ライセンスで防ぐのではなく、**名称とアイコンを
権利留保することで、同名・同アイコンでの再公開に商標および Play の
なりすましポリシーで対抗できる状態を作る**。フォーク自体は自由なので、
オープンソースとしての趣旨は損なわない。

### 13.2 同梱ライブラリに対して負う義務

APK に同梱されるサードパーティ成果物は以下。すべて Apache-2.0 で、
コピーレフト（GPL/LGPL 系）は 1 つも含まない。MIT で配布することと矛盾しない。

| ライブラリ | 著作権者 | ライセンス |
|---|---|---|
| Kotlin Standard Library | JetBrains s.r.o. | Apache-2.0 |
| kotlinx.coroutines | JetBrains s.r.o. | Apache-2.0 |
| AndroidX Core / Activity / Lifecycle | The Android Open Source Project | Apache-2.0 |
| AndroidX DataStore | The Android Open Source Project | Apache-2.0 |
| Jetpack Compose / Material 3 | The Android Open Source Project | Apache-2.0 |

Gradle・AGP・Kotlin コンパイラはビルド時のみで APK に含まれないため対象外。

**Apache-2.0 §4(a) は、再頒布物の受領者にライセンスの写しを渡すことを要求する。**
アプリ配布はこの「再頒布」にあたるため、アプリ内にライセンス表示画面（6.3）を置いて
これを満たす。ここが「自分のライセンス選択」とは独立して発生する義務であり、
見落としやすい点。

**外部リンクで代替しない理由**: §4(a) が求めるのは写しの提供であり、
URL の提示がこれを満たすかは解釈が分かれる。加えて本アプリは `INTERNET` 権限を
持たない方針であり、オフラインで完結しない表示は設計思想とも噛み合わない。
**全文を `res/raw/` に同梱する**（Apache-2.0 全文は約 11KB、1 部のみ）。

なお、上記ライブラリはいずれも `NOTICE` ファイルを配布していないため、
§4(d) の NOTICE 転記義務は発生しない。将来 NOTICE を持つ依存を追加した場合は
その内容もライセンス画面に転記する必要がある。

### 13.3 実装方針

`oss-licenses-plugin`（Google 提供の自動生成プラグイン）は**採用しない**。
Play Services への依存が増え、依存最小・ネットワーク権限なしという本アプリの
方針と衝突するため。依存が上表の 5 件しかないので、静的に持つほうが軽く確実。

```kotlin
// data/ThirdPartyLicenses.kt
enum class LicenseType(val displayName: String, @RawRes val textRes: Int) {
    APACHE_2_0("Apache License 2.0", R.raw.license_apache_2_0),
    MIT("MIT License", R.raw.license_mit)
}

data class LibraryNotice(
    val name: String,
    val holder: String,
    val license: LicenseType
)

val THIRD_PARTY_LIBRARIES = listOf(
    LibraryNotice("Kotlin Standard Library", "JetBrains s.r.o.", LicenseType.APACHE_2_0),
    LibraryNotice("kotlinx.coroutines", "JetBrains s.r.o.", LicenseType.APACHE_2_0),
    LibraryNotice("AndroidX (Core, Activity, Lifecycle, DataStore)",
                  "The Android Open Source Project", LicenseType.APACHE_2_0),
    LibraryNotice("Jetpack Compose / Material 3",
                  "The Android Open Source Project", LicenseType.APACHE_2_0),
)
```

ライセンス全文はリソースから読むだけで、パース・整形は行わない（原文のまま表示）。

### 13.4 運用ルール

**依存を 1 つ追加したら、`THIRD_PARTY_LIBRARIES` に 1 行追加する。**
静的リストの唯一の弱点は更新漏れなので、これを README の
コントリビューション手順に明記し、リリース前チェックリストにも入れる。

新しい依存を検討する際は、ライセンスを事前に確認する。
**GPL / LGPL / AGPL 系のライブラリは採用しない**（MIT での配布と両立しないか、
または動的リンク要件など APK 配布で扱いが面倒になるため）。

### 13.5 リリース前チェックリスト

`./verify_release.sh` で機械的に検証できる範囲を自動化してある。

自動チェック:

- [ ] `INTERNET` を要求していない
- [ ] `QUERY_ALL_PACKAGES` を要求していない
- [ ] MIT / Apache-2.0 の全文が APK に同梱されている
- [ ] APK が署名済み（v2 + v3）
- [ ] 署名鍵の指紋が `release-fingerprint.txt` と一致する

> ⚠️ リリースビルドはリソースパスを短縮するため、ライセンス全文は
> `res/raw/license_*.txt` ではなく `res/0M.txt` のような名前になる。
> 存在確認はパスではなく**内容**で行う必要がある
> （`isShrinkResources = true` に削られていないかの確認も兼ねる）。

目視チェック:

- [ ] `THIRD_PARTY_LIBRARIES` が実際の依存と一致しているか
- [ ] ライセンス画面が実機で表示・スクロール・展開できるか
- [ ] `LICENSE` に名称・アイコンの権利留保文があるか
- [ ] README のライセンス節が `LICENSE` と矛盾していないか
- [ ] **リリースビルド（R8 有効）を実機で通しで動作確認したか**
      — デバッグビルドでは検出できない難読化・リソース圧縮の影響を確認する

### 13.6 署名鍵の運用

`./make_release_key.sh` で作成する（初回のみ）。RSA 4096bit・有効期限 30 年
（Play の要件は 2033-10-22 以降まで有効なこと）。

- `volumetap-release.jks` と `keystore.properties` は `.gitignore` 済み
- **鍵は一度作ったら変更できない。** 変えると野良配布した APK からの
  上書き更新ができなくなり、Play へのアップロード鍵としても使えなくなる
- **紛失すると同一アプリとして更新を出せなくなる。** 必ず別の場所にバックアップする
- `keystore.properties` が無い環境ではビルドは通り、未署名 APK になる
  （CI や他の開発者がクローンしてもビルドできるようにするため）

**署名スキーム**: v2 + v3 を有効にする。

- v1（JAR 署名）は不要。minSdk 26 なので対象端末はすべて v2 に対応している
- **v3 は AGP の既定では無効。明示的に有効化する。** v3 は署名鍵の
  ローテーションを可能にする唯一の仕組みで、鍵が漏洩・紛失しかけたときに
  更新を継続したまま新しい鍵へ移行できる。長く続けるアプリでは入れておく

**指紋の記録**: 証明書の SHA-256 指紋を `release-fingerprint.txt` に置き、
`verify_release.sh` が毎回突き合わせる。鍵の取り違え（別の鍵で署名した APK を
誤って配布する）を機械的に検出するため。このファイルは公開してよい。

## 14. アイコン

構図: 白のボリュームロッカー（＋／−）から伸びる線が、スマートフォン上の
タップ波紋に接続する。「ボリュームキー → 画面タップ」という単機能をそのまま図示する。

**決定稿**（2026-07-23 確定）。成果物は `icon/` 以下、生成スクリプトは `icon/build_icon.py`。
ドラフトからロングシャドウと円の輪郭を削除し、背景を単色ベタ `#1E88D8` に変更した。
ファイル一覧と幾何仕様は `icon/README.md`。以下は設計上の根拠。

### 14.1 アダプティブアイコン（必須）

Android 8.0 以降のランチャーはアイコンを任意形状のマスクで切り抜く。
108dp のうち**中央 66dp のみが安全領域**で、外側はマスクや視差効果で削られる。

- **背景レイヤー**: `#1E88D8` の単色ベタ（円ではなく全面）
- **前景レイヤー**: 白のグリフのみ。安全領域 66dp に収まるよう縮小
- **円の輪郭は前景に含めない**。マスクが円形とは限らないため、
  円形マスク環境では二重円に、角丸四角マスク環境では円が浮いて見える

**ロングシャドウ（右下への長い影）は持たせない。** 理由は 2 つ。

1. ランチャーがアダプティブアイコンに独自の影を付けるため二重になる
2. 円形以外のマスクでは影が斜めに断ち切られ、破綻して見える

### 14.2 モノクロレイヤー（Android 13+ テーマアイコン）

グリフが単色の白ベタなので、**前景レイヤーをそのまま
`android:monochrome` に流用できた**。追加コストなしで対応済み。

### 14.3 コントラスト

白のグリフ / 水色の地はコントラスト比が低く、ランチャー表示サイズ（48dp 前後）で
ロッカーの輪郭線や接続線のような細い要素が飛びやすかった。

対応: 地色をドラフトの `#74C0EE` から **`#1E88D8`** に変更（白とのコントラスト比
約 2.4 → 約 3.8）。ストローク幅は 3.4dp（波紋リングのみ 3.0dp）で統一。
やや紺寄りの落ち着いた色調になり、48dp 実寸でも細線が沈まないことを確認済み。

### 14.4 Play ストア用アイコン

- 512×512 px、32bit PNG、**透過なし**
- ストア側が角丸・影を自動付与するため、**画像自体に影や角丸を含めない**
- アダプティブアイコンとは別書き出しになる（全面に地色を敷いた版）

### 14.5 権利上の扱い

アイコンは §13.1 のとおり MIT の対象外。ソースリポジトリには含めるが、
`LICENSE` および README で対象外である旨を明記する。
