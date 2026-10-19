# volumetap Chrome の PWA の個別認識と、アプリの一括追加・検索・削除 設計書

- 対象: v0.15.0 想定
- 作成: 2026-10-19
- 状態: **実装済み・実機確認済み**（v0.15.0、2026-10-19。確認の結果は §2.1・§6.1・§9.8。残りは各節の「未確認」）
- 2026-10-19 決定: 写し元は「そのまま全部写す」。プライバシーポリシーは変えない。
  README は「Chrome の PWA ではそのアプリ名も参照」と書く（ユーザー決定）
- 2026-10-19 追加: 割り当ての削除（横フリック・すべて削除）を §9 に足す。v0.15.0 に含める
- 関連: `design.md` §4.1・§5.4、`design-pageturn.md` §6.4・§7.1

---

## 0. 結論

- **Chrome でインストールした PWA（WebAPK）を、Chrome とは別のアプリとして扱う。**
  前面が Chrome の PWA 用の画面（`…chrome.browser.webapps.` の Activity）のとき、
  ウィンドウのタイトル（＝PWA のアプリ名）を、インストール済みの WebAPK の名前と照らし合わせて
  前面アプリを WebAPK のパッケージ名に置き換える（§2）
- **PWA に割り当てが無ければ、Chrome の割り当てを使う。** 今 Chrome に割り当てて
  yomikake を読んでいる利用者の挙動は変わらない（§2.4）
- 判定できないとき（名前が一致しない・同名の PWA が 2 つある・Chrome 以外のブラウザ）は、
  **今までどおり Chrome として扱う**。判定を誤ったときに起きるのは「今の挙動に戻る」だけになるようにする
- **「アプリを追加」で複数のアプリを選び、まとめて追加できるようにする。**
  追加するときの割り当ては、初期値（−で送る／＋で戻す）か、設定済みのアプリの割り当てを写す（§3.2）
- **アプリの検索は、一覧の絞り込みにする。** 絞り込んでも、選んだ状態は保つ（§3.3）
- **設定画面の一覧で、割り当てを横フリックで削除できるようにする。** 消したあとに「元に戻す」を出す。
  「割り当てをすべて削除」は上のバーのメニューに置き、確認ダイアログと「元に戻す」で守る（§9）
- 画面の内容を読み取る能力（`canRetrieveWindowContent`）は今までどおり宣言しない。ただし
  「前面アプリのパッケージ名のみ参照する」という README の説明は、
  「Chrome の PWA に限り、そのアプリ名（ウィンドウのタイトル）も参照する」に改める（§5）

---

## 1. 背景

### 1.1 PWA

volumetap はもともと yomikake（PWA）を読むために作ったアプリである。ところが PWA は
Chrome のプロセスの上で動き、前面アプリが `com.android.chrome` として見える。
このため PWA だけに別の割り当てをすることができず、`design-pageturn.md` §6.4 では
「Chrome の割り当てをドラッグにして、yomikake と一般サイトの両方を読む」形にしていた。

あわせて、アプリを追加する画面の注意書き（`app_picker_pwa_hint`）と README は
「PWA はこの一覧に出ない」と説明しているが、これは**事実と違う**。Chrome でインストールした PWA は
WebAPK という独立したアプリとして入り、ランチャーの入口を持つので一覧に出る。
選んでも効かない（Chrome の割り当てが使われる）ので、利用者が混乱する。

### 1.2 アプリの追加の手間

アプリを追加する画面では、1 件選ぶたびにそのアプリの設定画面に進み、戻ってきて次を選ぶことになる。
ブラウザ・SNS・RSS リーダーなど、同じ割り当てで足したいアプリが何件もあると手間が大きい。
一覧はアプリ名順に並ぶだけで検索もできないので、目的のアプリを探してスクロールする手間もかかる。

---

## 2. Chrome の PWA の個別認識

### 2.1 実機で確かめたこと（2026-10-19、Redmi Note 15 5G・HyperOS 3.0）

| 確認したこと | 結果 |
|---|---|
| PWA の実体 | yomikake は `org.chromium.webapk.aa98b755d5fa3d28e_v2`。ランチャーの入口（`H2OOpaqueMainActivity`）を持ち、`<queries>` の範囲で見える |
| WebAPK のメタデータ | `org.chromium.webapk.shell_apk.runtimeHost` = `com.android.chrome`、`runtimeHostApplicationName` = `Chrome`。ほかに `startUrl`・`scope` など |
| 前面にあるときの見え方 | フォーカスのあるウィンドウは `com.android.chrome/…webapps.SameTaskWebApkActivity`。アプリ履歴のタスクは WebAPK のパッケージ |
| `TYPE_WINDOW_STATE_CHANGED` の中身（PWA） | パッケージ `com.android.chrome`、ClassName `org.chromium.chrome.browser.webapps.SameTaskWebApkActivity`、Text `[yomikake]` |
| 同（Chrome のタブ） | ClassName `org.chromium.chrome.browser.ChromeTabbedActivity`、Text `[Chrome: ページのタイトル]` |
| Text はページのタイトルか、アプリ名か | **アプリ名**。Qiita の PWA でページを動かしても `[Qiita]` のまま。yomikake・Qiita とも、WebAPK のアプリ名（`application-label`）と一致 |
| PWA の中で届く、それ以外のイベント | `TYPE_WINDOW_CONTENT_CHANGED` などで、ClassName は `android.widget.FrameLayout` など一般的なもの。Text は空 |
| **volumetap 自身に Text が届くか** | **届く**。`canRetrieveWindowContent` を宣言していないままの volumetap に一時的にログを足して確認した（検証後に削除）。`com.android.chrome \| …SameTaskWebApkActivity \| [yomikake]` |

`uiautomator events`（全権限）で見えるだけでなく、宣言を変えていない volumetap にも届くことを確かめた。
これがこの設計の前提である。

### 2.2 判定の規則

**WebAPK の一覧**（サービスが持つ）:

ランチャー起動可能なアプリのうち、メタデータ `org.chromium.webapk.shell_apk.runtimeHost` を持つものを
WebAPK とみなし、`(パッケージ名, ホストのパッケージ名, アプリ名)` を控える。
パッケージ名の接頭辞（`org.chromium.webapk.`）ではなくメタデータで判定するのは、ホストのブラウザが
分かるようにするためと、接頭辞が変わっても追従するため。

**イベントごとの判定**（`ForegroundTracker`）:

| 届いたイベント | 前面アプリ |
|---|---|
| ① `STATE_CHANGED`、パッケージがホスト H、ClassName が `org.chromium.chrome.browser.webapps.` で始まる、Text の 1 件目が H の WebAPK の名前と**ちょうど 1 件**一致 | その WebAPK（W） |
| ② ①と同じだが、一致しない・2 件以上一致する | H（今までどおり） |
| ③ 現在 W（ホスト H）で、H から `STATE_CHANGED` が届き、ClassName が H 自身の画面（`org.chromium.` か `com.google.android.apps.chrome.` で始まり、`.webapps.` を含まない）。例: `ChromeTabbedActivity` | H |
| ④ 現在 W（ホスト H）で、H から③以外のイベントが届いた（`CONTENT_CHANGED`、ダイアログなど） | **W のまま** |
| ⑤ それ以外 | 今までどおり（パッケージ名で決める。systemui・ランチャーに出ないパッケージは無視） |

- ④が要る理由: PWA を表示しているあいだも、Chrome のパッケージで `CONTENT_CHANGED` が絶えず届く。
  これで Chrome に戻ってしまうと、PWA を認識した直後に Chrome の割り当てに戻る
- ③を「Chrome 自身の画面」に限る理由: PWA の中で出るダイアログ（権限の確認、`alert()` など）の
  `STATE_CHANGED` で Chrome に戻らないようにするため。ダイアログの ClassName は §6.1 で確認する
- ②のうち、**タイトルが空で、現在がそのホストの PWA なら、PWA のまま**にする。タイトルがあって一致しないときは、
  ショートカットとして追加した PWA（WebAPK でないもの）など別の画面の可能性があるので H に倒す（コードレビューの指摘、2026-10-19）
- **WebAPK 自身の画面（起動時のスプラッシュなど）のイベントで前面が W になったときも、W のホストを決める**
  （WebAPK の一覧から引く）。PWA の起動時は、W 自身のイベント → H の再描画 → PWA 用の画面のイベントの順に届く。
  ホストが決まっていないと④が効かず、H の再描画で H に戻ってしまう。実機で、PWA の上での調整を始めた直後に
  「アプリから離れた」として閉じた（2026-10-19、利用者の実機確認で発見）
- Text を見るのは①②の場合（Chrome の PWA 用の画面のとき）だけ。Chrome のタブの Text（ページのタイトル）は
  見ない・保持しない・ログに出さない

**ホームを挟まずにアプリ履歴から切り替えたとき**:

`ForegroundTracker` の注記どおり、端末によってはアプリ履歴から戻ったときに `STATE_CHANGED` が来ない。
このとき次のことが起きうる。

| 切り替え | 起きること | 影響 |
|---|---|---|
| Chrome のタブ → PWA | Chrome のまま | 今の挙動と同じ（Chrome の割り当て）。悪化はしない |
| PWA → Chrome のタブ | **PWA のまま**（④） | Chrome のタブで PWA の割り当てが使われる。**新たに生じる誤り** |

後者は §6.1 で頻度を確かめる。試作機では、アプリ履歴から PWA に戻ったときに `STATE_CHANGED` が届いた。
目立つようなら、「W のまま」にする時間に上限を設ける案などを検討する（§8）。

### 2.3 データの流れ

```
VolumeTapService
  refreshLaunchablePackages()      ← 起動時・パッケージの追加/削除/更新時（既存）
    ├ launchable: Set<String>       （既存）
    └ webApks: List<WebApk>         （追加）getApplicationInfo(GET_META_DATA) でホストを、
                                     getApplicationLabel で名前を読む
  onAccessibilityEvent(event)
    └ foreground.onEvent(pkg, className, title, stateChanged)
         title は「pkg がホスト かつ ClassName が webapps」のときだけ event.text から取り出す
  resolve(key)
    └ ProfileResolver.resolve(settings, foreground.current, foreground.host, …)
         割り当て = profiles[current] ?: profiles[host] ?: defaultProfile
```

- PWA のインストール・削除では `PACKAGE_ADDED` / `PACKAGE_REMOVED` が届く（WebAPK は普通のアプリなので）。
  既存の `packageChangeReceiver` で一覧が取り直される
- `ForegroundTracker` は Android に依存しない純ロジックのまま保つ（単体テストのため）。
  `WebApk` は `data class WebApk(val packageName: String, val host: String, val label: String)`
- `foreground.host` は「現在が WebAPK のとき、そのホスト」。それ以外は null

### 2.4 割り当ての選び方

| 前面 | PWA の割り当て | Chrome の割り当て | 使われる割り当て |
|---|---|---|---|
| PWA（認識できた） | あり | （問わない） | PWA |
| PWA（認識できた） | なし | あり | **Chrome** |
| PWA（認識できた） | なし | なし | その他のアプリ |
| PWA（認識できなかった）／Chrome のタブ | — | あり | Chrome |

2 行目が要点。PWA を認識しても、利用者が PWA を追加するまでは何も変わらない。

### 2.5 画面

- **アプリを追加する画面**: WebAPK の行は、パッケージ名（`org.chromium.webapk.aa98b7…`）の代わりに
  「PWA（Chrome）」と出す（ホスト名は `runtimeHostApplicationName`）。パッケージ名は利用者に意味がないため
- **注意書き**（`app_picker_pwa_hint`）を書き換える（§3.4）
- 設定画面（MainActivity）の一覧・アプリの設定画面は変更しない。名前とアイコンは普通のアプリと同じに取れる
- **「アプリの上で調整・登録」**: `getLaunchIntentForPackage(WebAPK)` で PWA が開く。調整中の
  「対象アプリから離れたか」は `foreground.current` で判定しているので（`CalibrationSession.onForeground`）、
  §2.2 の判定が効けば変更は要らない（§4 の整合性の確認を参照）

### 2.6 範囲外

- **Chrome 以外のブラウザ**（Edge・Brave・Samsung Internet・Firefox）。①の ClassName に一致しなければ
  ⑤になり、今までどおりブラウザとして扱う。Chromium 系のブラウザで偶然一致する可能性はあるが、確かめない
- **ショートカットとして追加された PWA**（WebAPK にならなかったもの）。アプリとして存在しないので一覧に出ず、
  ブラウザとして扱われる。注意書きで案内する
- **URL での判定**（`design-pageturn.md` §6.4 案 C）。引き続き不採用

---

## 3. アプリの一括追加と検索

### 3.1 画面

```
┌──────────────────────────────┐
│ ← アプリを追加                │
│ ┌──────────────────────────┐ │
│ │ 🔍 アプリを検索         ✕ │ │ ← 一覧の上に固定
│ └──────────────────────────┘ │
├──────────────────────────────┤
│ 💡 PWA の注意書き             │ ← 一覧の先頭の項目。スクロールで流れる
│ 💡 Discover の注意書き        │   検索欄が空のときだけ出す
├──────────────────────────────┤
│ ☑ [icon] Chrome               │
│ ☐ [icon] Feedly               │
│   [icon] Google      設定済み › │ ← 選べない。押すと設定画面
│ ☑ [icon] yomikake             │
│          PWA（Chrome）         │
│ ☐ [icon] X                    │
├──────────────────────────────┤
│ 写し元 [初期値（−で送る／＋で戻す）▼] │ ← 1 件以上選んだときだけ出す
│                   [ 追加（2） ] │
└──────────────────────────────┘
```

### 3.2 選択と追加

- **行を押すと選択が切り替わる**（チェックボックス）。設定済みのアプリはチェックボックスを出さず、
  押すとそのアプリの設定画面を開く（今と同じ）。**このときアプリを追加する画面は閉じない**
  （今は閉じている）。戻ってきたときに選択が残るようにするため
- **写し元**（新しく追加するアプリの割り当て）:
  - 「初期値（−で送る／＋で戻す）」= `AppProfile.NEW_APP`（既定）
  - 設定済みのアプリ（アプリ名順）。そのアプリの割り当てを**そのまま写す**
    （キーの割り当て・タップ位置・ドラッグの調整をすべて含む）
  - 「その他のアプリ」は候補に出さない。既定は音量のままなので、写すと何もしないアプリが増えるだけになる
- **そのまま写す理由**: どこまで写すかを利用者に考えさせないため。タップ位置は画面に対する比率なので、
  電子書籍アプリどうしなら「右端の中ほど」がそのまま通じることも多い。タップを割り当てたアプリから
  タップ位置を写さないと、「タップと表示されているのに何も起きない（素通し）」アプリが増えてしまう。
  ドラッグの調整を写すと、写した先にも「調整済み」の印が付く（事実として保存値があるので正しい）
- **追加（N）を押したとき**:
  - 1 件なら、今と同じくそのアプリの設定画面へ進み、アプリを追加する画面を閉じる
  - 2 件以上なら、アプリを追加する画面を閉じて設定画面（MainActivity）に戻る。追加したアプリが一覧に並ぶ
- 書き込みは 1 回の `DataStore.edit` で行う（`SettingsRepository.addProfiles`）。
  **既に割り当てのあるアプリは上書きしない**（別の画面で追加された場合に備える）
- 選択・検索語・写し元は `rememberSaveable` で持つ。画面を回転しても消えない
- 「追加（N）」は複数形の文言（`plurals`）にしない。英語で `Add 1 app` / `Add 2 apps` を出し分ける必要が生じ、
  `StringResourcesTest` が `<plurals>` を見ていないため。「追加（%d）」の形で 4 言語とも済ませる

### 3.3 検索（絞り込み）

- 検索欄に入れた文字で、一覧を**絞り込む**。アプリ名またはパッケージ名に含まれれば残す
- 比べる前に、両方を次のようにそろえる（純関数 `AppSearch.normalize`、単体テストする）:
  1. NFKC で正規化する（全角英数を半角に、半角カナを全角に）
  2. 小文字にする（`Locale.ROOT`）
  3. ひらがなをカタカナにする（「くろーむ」でも「クローム」に一致させる。ひらがなの範囲を一定の差でずらすだけ）
- **選んだ状態は絞り込みに関係なく保つ。** 「chr」で Chrome を選ぶ → ✕ で消す → 「feed」で Feedly を選ぶ
  → 追加（2）と続けられる。絞り込みで見えなくなった選択も、件数には含まれる
- 一致するものが無ければ「一致するアプリはありません」と出す
- キーボードの「完了」でキーボードを閉じる。一覧をスクロールし始めたときにも閉じる
- 注意書き 2 つは、検索欄が空のときだけ出す。キーボードが出ると一覧が狭くなるため
- 注意書きは一覧（`LazyColumn`）の先頭の項目にし、アプリと一緒にスクロールさせる。
  実装当初は検索欄の下に固定していたが、試作機で一覧が画面の半分ほどしか使えなかった（2026-10-19）
- **日本語キーボードのかな入力では、英字のアプリ名に一致させられない。** 「yomi」と打つと
  「よみ」に変換され、「yomikake」には一致しない。英字で探すときは、キーボードを英字入力に切り替えてもらう。
  ローマ字とかなの相互変換はしない（規則が多く、誤って一致させる弊害もあるため）

### 3.4 文言（4 言語）

| キー | 日本語 | 備考 |
|---|---|---|
| `app_picker_pwa_hint`（改） | 💡 Chrome でホーム画面に追加した PWA（yomikake など）は、この一覧から選べます。割り当てが無いあいだは Chrome の割り当てが使われます。一覧に無いもの（ショートカットとして追加されたものや、ほかのブラウザのもの）は、そのブラウザを選んでください。 | |
| `app_picker_search`（新） | アプリを検索 | 検索欄の案内 |
| `app_picker_search_clear`（新） | 検索語を消す | ✕ の読み上げ |
| `app_picker_no_match`（新） | 一致するアプリはありません | |
| `app_picker_add_selected`（新） | 追加（%d） | |
| `app_picker_copy_from`（新） | 写し元 | |
| `app_picker_copy_initial`（新） | 初期値（−で送る／＋で戻す） | |
| `app_picker_webapk`（新） | PWA（%s） | %s はブラウザ名 |

英語・簡体字・繁体字は `design-i18n.md` §6.1 の用語集に従う。追加・変更は 4 言語同時に行う。

---

## 4. 現行コードとの整合性の確認

| # | 箇所 | 現状 | 影響と対応 |
|---|---|---|---|
| 1 | `ForegroundTracker.onEvent(packageName)` | パッケージ名だけを受け取る | 引数に `className`・`title`・`stateChanged` を足す（既定値付き。既存のテストはそのまま通る）。`setWebApks()` と `host` を足す。ランチャー起動可能かの絞り込みは、WebAPK もランチャーに出るのでそのまま使える |
| 2 | `ForegroundTracker` の「同じパッケージなら更新しない」 | パッケージ名で比べる | 判定後の前面アプリ（W または H）で比べる。H → W、W → H の変化を `true` で返し、調整中の判定（`onForegroundChanged`）に伝える |
| 3 | `VolumeTapService.onAccessibilityEvent` | パッケージ名を渡し、変化をログに出す | ClassName・Text・種類を渡す。Text を取り出すのは「ホスト かつ webapps の ClassName」のときだけ。**ログはパッケージ名のまま**（タイトルは出さない） |
| 4 | `VolumeTapService.refreshLaunchablePackages` | ランチャー起動可能なパッケージの集合だけ作る | 同じ場所で WebAPK の一覧を作る（IO スレッド）。メタデータを読むのはランチャー起動可能なアプリだけで、`QUERY_ALL_PACKAGES` は要らない。数百件の `getApplicationInfo(GET_META_DATA)` になるので、`org.chromium.webapk.` で始まるものだけに絞ってから読むか、計測して決める |
| 5 | `ProfileResolver.resolve` / `AppSettings.profileFor` | `profiles[foreground] ?: defaultProfile` | `profileFor(packageName, fallback)` にし、`profiles[W] ?: profiles[H] ?: defaultProfile`。`resolve` に `foregroundHost` を足す（既定 null）。自アプリなら素通しする判定・一時停止は変わらない |
| 6 | 調整中の値の上書き（`CalibrationOverride`） | `packageName == foreground` で効かせる | 前面が W として認識されていれば一致する。変更不要 |
| 7 | `CalibrationSession.onForeground` | 対象のパッケージと前面を比べる | 同上。変更不要。ただし §2.2 ③の判定を誤ると、PWA の上での調整中にダイアログで「離れた」と判断されて閉じる。§6.1 で確認する |
| 8 | `CalibrationSession.isOwnOverlayEvent` | 自パッケージのイベントを見分ける | 関係しない。変更不要。重ね表示のイベントは今までどおり前面アプリの判定より先に捨てる |
| 9 | `ProfileEditActivity.startCalibration` | `getLaunchIntentForPackage` で起動 | WebAPK でも入口が返り、PWA が開く（`monkey` で同じ入口から起動して確認済み）。変更不要 |
| 10 | `PickPointActivity`（白紙の上での登録） | `settings.profileFor(packageName)` | 編集対象のアプリ自身の割り当てを読む用途なので、ホストへの切り替えは要らない。`fallback` を渡さない |
| 11 | `InstalledApps.load` | 名前とアイコンを取る | WebAPK ならホスト名も取る（`InstalledApp.webApkHost: String?`）。アプリを追加する画面で「PWA（Chrome）」を出すのに使う |
| 12 | `ProfileCodec` | パッケージ名に `\|` と `,` が無い前提 | WebAPK のパッケージ名は英数字・`.`・`_` だけ。変更不要 |
| 13 | `AppPickerActivity` | 1 件押すと作成して設定画面へ進み、閉じる | §3 のとおり作り直す。設定済みのアプリを押したときに閉じないように変える |
| 14 | `SettingsRepository` | `updateProfile` は 1 件ずつ | `addProfiles(packages, template)` を足す。どれを足すかの判断は純関数（`AppSettings` 側）に置いて単体テストする |
| 15 | `StringResourcesTest` | `<string>` だけを見る | `plurals` を使わないので変更不要（§3.2） |
| 16 | `accessibility_service_config.xml` | `canRetrieveWindowContent` を宣言しない | **変更しない**。イベントの種類も今のままで足りる（`typeWindowStateChanged` を既に購読している） |
| 17 | `accessibility_service_description`（4 言語） | 「画面に表示されている内容を読み取りません」 | §5 のとおり、変えなくても偽りにはならない。変えるなら Play の審査対象なので慎重に |
| 18 | README「yomikake と組み合わせる」・「プライバシー」 | PWA は一覧に出ない／パッケージ名のみ参照 | 書き換える（§5） |
| 19 | `design-pageturn.md` §6.4 | 「パッケージ名でしか区別できない」 | 本書への参照を足し、前提が変わったことを書く |
| 20 | `design.md` §4.1・§5.4 | 前面アプリはパッケージ名のみ | 本書への参照を足す |

食い違いで実装を止めるものは見つからなかった。注意が要るのは #4（読み込みの量）と #7（ダイアログの扱い）で、
どちらも §6.1 の確認項目にした。

---

## 5. プライバシーと説明文

- 新たに見るのは、**Chrome の PWA 用の画面のウィンドウのタイトル**だけである。中身はその PWA のアプリ名
  （ホーム画面のアイコンの名前と同じ）で、ページのタイトルではない（§2.1 で確認済み）。
  WebAPK の名前と照らし合わせるだけで、保存もログ出力もしない
- Chrome のタブのウィンドウのタイトル（`Chrome: ページのタイトル`）は**読まない**。
  ClassName を先に見て、webapps の画面でなければ Text に触れない
- 権限・宣言は増えない。データセーフティの回答（収集なし）も変わらない

| 文書 | 対応 |
|---|---|
| README「プライバシー」 | 「前面アプリのパッケージ名のみ…参照します」を「前面アプリのパッケージ名と、Chrome の PWA ではそのアプリ名（ウィンドウのタイトル）を、割り当ての切り替えのために参照します」に改める |
| README「yomikake と組み合わせる」 | PWA を直接選べること、割り当てが無ければ Chrome の割り当てが使われることに書き換える |
| ユーザー補助の説明文・Play の掲載文 | 「画面に表示されている内容を読み取りません」は、アプリ名だけを見るので偽りにはならない。**変えない**ことを提案する |
| プライバシーポリシー（https://www.ayati.com/privacy.html） | 「パッケージ名のみ」と書いてあれば、README と同じ趣旨に改める（利用者が更新する） |

---

## 6. 検証計画

### 6.1 実機で先に確かめること（実装の前半で行う）

**結果（2026-10-19、Redmi Note 15 5G・HyperOS 3.0）**:

| # | 結果 |
|---|---|
| 1 | **未確認**（利用者の操作が要る） |
| 2 | **未確認** |
| 3 | アプリのアイコンから直接切り替える形（ホームを挟まない）は、PWA → Chrome → PWA → 別の PWA のどれも正しく追えた。**アプリ履歴の画面からの切り替えは未確認** |
| 4 | **未確認**。yomikake・Qiita はアプリ名とタイトルが一致 |
| 5 | **未確認**（PWA を消すと利用者のデータが失われうるので、試作機では行っていない） |
| 6 | 起動時の一覧作成は 20〜71 ms（ランチャーに出るアプリ 173 件、WebAPK 3 件） |

あわせて、**以前からの不具合**を見つけて直した。HyperOS のホーム（`com.miui.home`）は
LAUNCHER の入口を持たず、「ランチャーから起動できるアプリ」の一覧に入っていなかった。
そのため前面アプリの判定で無視され、ホームに戻っても直前のアプリの割り当てが残っていた
（Chrome からホームに戻ると、ホーム画面でもドラッグが効く）。`<queries>` に HOME の入口を足し、
ホーム画面アプリも一覧に加えた（`design.md` §5.4）。修正後、PWA・Chrome からホームに戻ったことを記録できた。

**利用者による実機確認（2026-10-19）**: yomikake（PWA）の上でのタップ位置の登録とドラッグの調整を、最後まで行えた。
調整の帯（やめる／始める）は出たままで、yomikake が再描画しても「アプリから離れた」とは判定されなかった。
最初の確認では、調整を始めた直後に閉じた（原因と対策は §2.2 の「WebAPK 自身の画面のイベント」）。

⚠️ 確認の際に `uiautomator events` を使うと、終わったあとにユーザー補助サービスが接続し直され、
しばらくイベントが届かないことがあった。サービスの判定を確かめるときは、volumetap のログ
（`foreground -> …`）だけで見る。

**確認の手順（当初の計画）**:

| # | 確認すること | 確かめ方 | 結果で変わること |
|---|---|---|---|
| 1 | PWA の中のダイアログの ClassName | yomikake でファイルを開く・`alert()` を出すページ・通知の権限の確認などで `STATE_CHANGED` を記録する | §2.2 ③の「Chrome 自身の画面」の条件 |
| 2 | PWA の範囲外へのリンク | PWA から範囲外の URL を開く（Chrome は上部にバーを付けて同じ画面で開くか、カスタムタブで開く） | 範囲外のページで W と H のどちらの割り当てを使うか。カスタムタブ（`customtabs`）なら③で H になる |
| 3 | アプリ履歴から直接切り替えたとき | PWA ⇄ Chrome のタブを、ホームを挟まずに何度か切り替える | §2.2 の「新たに生じる誤り」の頻度 |
| 4 | アプリ名とタイトルが違う PWA | マニフェストの `name` と `short_name` が違う PWA を入れて比べる | 一致しない場合は、Chrome として扱う（悪化はしない）。多ければ照合の方法を見直す |
| 5 | PWA を入れ直したときのパッケージ名 | yomikake をアンインストールして入れ直す | 変わるなら、割り当てが消えたように見えることを README に書く |
| 6 | WebAPK の一覧を作る時間 | `refreshLaunchablePackages` の所要時間をログで測る | §4 #4 の絞り方 |

### 6.2 単体テスト（追加分）

- `ForegroundTrackerTest`
  - webapps の画面・名前が一致 → W になる。`host` は H
  - 名前が一致しない・同名が 2 件 → H のまま
  - W のあいだに H の `CONTENT_CHANGED`・ダイアログの `STATE_CHANGED` → W のまま
  - W のあいだに `ChromeTabbedActivity` の `STATE_CHANGED` → H に戻る
  - W からランチャー → ランチャー。systemui → W のまま（既存の規則が効く）
  - WebAPK の一覧が空のとき → 今までどおり
- `ProfileResolverTest`: W の割り当てあり／なし（H にフォールバック）／どちらもなし（その他のアプリ）
- `AppSearchTest`: 大文字小文字・全角英数・半角カナ・ひらがな/カタカナ・パッケージ名での一致
- 一括追加の純関数: 既存のアプリを上書きしない、写し元をそのまま写す

### 6.3 実機テスト

- PWA（yomikake）だけをタップ、Chrome をドラッグに割り当て、行き来して切り替わること
- PWA の割り当てを消すと、PWA で Chrome の割り当てが効くこと
- PWA の上で「アプリの上で調整」「タップ位置を登録」が最後までできること
- 3 件選んで写し元を変えて追加し、設定画面の一覧に並ぶこと。1 件なら設定画面に進むこと
- 検索で絞り込み、✕ で戻しても選択が残ること。画面を回転しても残ること
- 4 言語で文言が崩れないこと（「追加（N）」・「PWA（Chrome）」）

---

## 7. 実装の手順

1. §6.1 の確認（#1〜#6）。結果を本書の §2.2・§6.1 に書き足す
2. `ForegroundTracker`・`ProfileResolver`・`AppSettings.profileFor` とそのテスト
3. `VolumeTapService`（WebAPK の一覧・イベントの受け渡し）、実機で PWA の切り替えを確認
4. `InstalledApps`（ホスト名）、`AppSearch` とそのテスト
5. `AppPickerActivity` の作り直し、`SettingsRepository.addProfiles`
6. 文言（4 言語）・README・CHANGELOG・`design-pageturn.md` §6.4・`design.md` への参照
7. 実機テスト（§6.3）、v0.15.0

PWA の認識（2〜3）と一括追加・検索（4〜5）は独立しているので、どちらを先にしてもよい。

---

## 8. 未決事項

| 事項 | 案 |
|---|---|
| ~~写し元から写す範囲~~ | **決定（2026-10-19）**: そのまま全部写す |
| アプリ履歴から直接切り替えたときの誤り（§2.2） | §6.1 #3 の結果次第。目立てば、W のまま保つ時間に上限を設けるなどを検討する |
| ~~プライバシーポリシーの文言~~ | **決定（2026-10-19）**: 変えない。README だけを改める |
| 設定済みのアプリをまとめて変更・削除 | 本書の範囲外。追加画面の改善で足りなければ検討する |

---

## 9. 割り当ての削除（2026-10-19 追加）

### 9.1 背景

割り当てを消すには、設定画面の一覧からアプリを開き、アプリの設定画面のいちばん下まで
スクロールして「このアプリの割り当てを削除」を押す必要がある。一括追加（§3）で割り当てが
増えやすくなったので、消す手間も減らす。すべての割り当てを消す手段は、OS の設定で
アプリのデータを消す以外に無い（それだと長押しの時間などの設定も消える）。

### 9.2 方針：Android の作法に従う

- **横フリックで消し、「元に戻す」を出す**（Gmail などと同じ）。Material 3 の `SwipeToDismissBox` を使う
- フリックで削除ボタンを出して止め、押させる形（iOS でよく見る形）にはしない。
  操作が 1 回多いうえ、Android 標準の部品に無いので自前で作ることになり、不具合の余地が増える。
  誤って消したときは「元に戻す」で取り戻せるので、ボタンで止める必要はない
- 「すべて削除」は、**置き場所（メニューの中）・確認ダイアログ・元に戻す**の 3 段で誤操作を防ぐ
- 確認の文字を打たせる、長押しで確定させる、といった重い守りはしない。消えるのは設定だけで
  取り戻せるうえ、指を動かしにくい利用者には負担になるため

### 9.3 1 件ずつの削除（横フリック）

```
┌──────────────────────────────┐
│ [icon] Chrome                │
│        −：送る　＋：戻す        │
└──────────────────────────────┘
        ← 左へフリック
┌──────────────────┬───────────┐
│ ome              │  🗑 削除   │ ← 赤（errorContainer）の背景
│ る　＋：戻す        │           │
└──────────────────┴───────────┘
        しきい値を越えて離すと消える
───────────────────────────────
│ Chrome を削除しました  [元に戻す] │ ← Snackbar（長め）
───────────────────────────────
```

- **左へのフリック（`EndToStart`）だけを有効にする。** 左端から右へなぞる動きはジェスチャナビの
  「戻る」と重なるため。なお右端から始めると「戻る」になるので、行の中ほどからなぞってもらう
- 消える位置（しきい値）は行の幅の 40%。部品の既定（56dp）は短く、縦にスクロールするつもりの
  斜めの動きでも消えやすい。速いフリックは部品の既定の速度のしきい値で消える
- 「その他のアプリ」の行はフリックできない（消す対象ではない）
- 消すと同時に保存する（サービスにもすぐ効く）。「元に戻す」は消す前の割り当てを書き戻す
- 続けて別の行を消したときは、前の「元に戻す」を閉じて新しいものを出す（Gmail と同じ。
  閉じないと Snackbar が順番待ちになり、目の前の削除を取り消せない）
- **フリック以外の手段を残す。** 本アプリは指を動かしにくい人のための道具であり、フリックだけに頼らない
  - アプリの設定画面の「このアプリの割り当てを削除」は残す
  - 行に、読み上げ（TalkBack など）の操作メニューの「削除」を付ける（`semantics { customActions }`）

### 9.4 すべて削除

```
┌ volumetap               ⋮ ┐
│              ┌──────────────────┐
│              │ 割り当てをすべて削除 │ ← 割り当てが 2 件以上あるときだけ出す
│              └──────────────────┘
        ↓
┌────────────────────────────┐
│ 割り当てをすべて削除しますか？   │
│                            │
│ 追加した 5 件のアプリ（Chrome、 │
│ FeedMe、Google ほか）の割り当て │
│ を削除します。「その他のアプリ」 │
│ や長押しの設定などは残ります。   │
│                            │
│        [キャンセル] [すべて削除] │ ← 「すべて削除」は error の色
└────────────────────────────┘
        ↓
│ 5 件を削除しました  [元に戻す] │
```

- 上のバー（`TopAppBar`）の右に「⋮」を置き、その中に置く。普段の画面には出さない。
  一覧のスクロールや「アプリを追加」の操作の延長で指が触れない
- 「⋮」は割り当てが 2 件以上あるときだけ出す。1 件ならフリックか設定画面で足りる。
  これにより、件数の文言は常に複数になり、英語の単数・複数を出し分けなくて済む（§3.2 と同じく `plurals` を使わない）
- ダイアログでは件数と、アプリ名を最大 3 件（アプリ名順）見せる。4 件以上なら「ほか」を付ける
- **消す範囲は、追加したアプリの割り当てだけ。** 「その他のアプリ」の割り当て・長押しの時間・
  触覚フィードバック・一時停止は残す。OS の設定でデータを消すのとは役割を分ける
- 消したあと、「N 件を削除しました［元に戻す］」を出す。元に戻すと、消したすべての割り当てを書き戻す

### 9.5 元に戻す

- Snackbar の表示時間は `SnackbarDuration.Long`（約 10 秒）。Material 3 の `SnackbarHost` は、
  OS のユーザー補助の「操作に要する時間」の設定に合わせて表示時間を延ばす
- 書き戻すのは、**そのときに割り当てが無いアプリだけ**（純関数 `AppSettings.profilesWithRestored`）。
  「元に戻す」を押す前に同じアプリを追加し直していた場合に、新しい割り当てを上書きしないため
- 消した割り当ては画面（`MainActivity`）が覚えておくだけで、保存しない。画面を閉じたら取り消せない
  （Snackbar も消える）
- 消す処理は `SettingsRepository` が 1 回の `DataStore.edit` の中で行い、**実際に消した割り当てを返す**。
  画面が持っている設定（`collectAsState`）は少し遅れることがあるので、それを控えにしない

### 9.6 現行コードとの整合性の確認

| # | 箇所 | 現状 | 対応 |
|---|---|---|---|
| 1 | `MainActivity` の一覧 | `Column` の `for` で `ProfileRow`（`Card`）を並べる | 各行を `key(パッケージ名)` と `SwipeToDismissBox` で包む。`key` が無いと、1 件消したときにフリックの状態が隣の行に引き継がれる |
| 2 | `MainActivity` の `Scaffold` | `topBar` だけ | `snackbarHost` と、`TopAppBar` の `actions`（「⋮」）を足す |
| 3 | 縦スクロール | `Column` の `verticalScroll` | 横のフリックとは方向が違うので両立する。Material 3 1.4.0 の `SwipeToDismissBox(state, backgroundContent, …, enableDismissFromStartToEnd, enableDismissFromEndToStart, gesturesEnabled, onDismiss, content)` を使う |
| 4 | `SettingsRepository.removeProfile` | 戻り値なし。`ProfileEditActivity` が使う | 消した割り当てを返すようにする（呼び出し側は戻り値を使わなくてよい）。`removeAllProfiles()`・`restoreProfiles()` を足す |
| 5 | `ProfileEditActivity` の削除 | 消して画面を閉じる | 変えない（元に戻すも出さない）。こちらは画面の下の専用ボタンで、誤って押しにくい |
| 6 | アイコン | `ic_arrow_back`・`ic_search`・`ic_close` を自前で持つ | `ic_delete`（ゴミ箱）・`ic_more_vert`（⋮）を足す。ライブラリは足さない |
| 7 | 文言 | — | 4 言語で足す（§9.7） |
| 8 | 調整中・登録中に消した場合 | サービスの調整は対象のプロファイルを読む | 設定画面に戻っている時点で調整は閉じている（設定画面に戻ると「離れた」になる。`design-pageturn.md` §7.3.6）。影響しない |

### 9.7 文言

| キー | 日本語 | 英語 |
|---|---|---|
| `delete` | 削除 | Remove |
| `profile_removed` | %1$s を削除しました | Removed %1$s |
| `undo` | 元に戻す | Undo |
| `more_options` | その他のオプション | More options |
| `delete_all_profiles` | 割り当てをすべて削除 | Remove all app settings |
| `delete_all_title` | 割り当てをすべて削除しますか？ | Remove all app settings? |
| `delete_all_message` | 追加した %1$d 件のアプリ（%2$s）の割り当てを削除します。「その他のアプリ」や長押しの設定などは残ります。 | This removes the settings for the %1$d apps you added (%2$s). “Other apps” and settings such as the long press time are kept. |
| `delete_all_confirm` | すべて削除 | Remove all |
| `delete_all_names_more` | %1$s ほか | %1$s, and more |
| `name_separator` | 、 | , （後ろに空白） |
| `profiles_removed` | %1$d 件を削除しました | Removed %1$d apps |
| `cancel` | キャンセル | Cancel |

簡体字・繁体字は `design-i18n.md` §6.1 の用語集に従う（`delete_profile` の訳「删除／刪除」に合わせる）。

### 9.8 テスト

**実機での確認（2026-10-19、Redmi Note 15 5G、表示は英語）**: 試験用に Camera を追加して確かめた。
途中まで引くと赤い背景に「Remove」が出て、戻して離すと元に戻る。右へのフリックでは消えない。
左へのフリックで消え、「Removed Camera［Undo］」が出て、Undo で戻る。「⋮」→「Remove all app settings」で、
件数（7）・アプリ名 3 件・「and more」のダイアログが出て、Cancel で何も消えない。
未確認: すべて削除の実行と元に戻す（利用者の割り当てを消すので行っていない。純関数は単体テスト済み）、
続けて 2 件消したときの Snackbar、TalkBack の操作メニュー、日本語・中国語の表示。

⚠️ Snackbar の表示中に `uiautomator dump` で画面を読むと、数秒かかるあいだに表示が消えることがある。
Undo を試すときは、消した直後に決まった位置を押す。

- 単体テスト（`AppSettingsTest`）: `profilesWithRestored` が、割り当てのあるアプリを上書きせずに書き戻すこと
- 実機テスト
  - 左へのフリックで消え、「元に戻す」でタップ位置・ドラッグの調整を含めて戻ること
  - 右へのフリック・縦のスクロールでは消えないこと
  - 続けて 2 件消すと、2 件目の「元に戻す」が出ること
  - 「⋮」→「割り当てをすべて削除」→ キャンセルで何も消えないこと。削除 → 元に戻すですべて戻ること
  - 「その他のアプリ」・長押しの時間などが残ること
  - TalkBack の操作メニューから「削除」できること
  - 4 言語でダイアログ・Snackbar が崩れないこと

