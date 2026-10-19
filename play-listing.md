# Play の掲載情報（英語・簡体字・繁体字）

design-i18n.md §8 の段階 7。Play Console の「ストアの掲載情報」に、言語ごとに貼る文面。
日本語（ja-JP）の掲載情報は Play Console にある既存のものをそのまま使う。

| Play の言語 | 使う節 |
|---|---|
| 英語（米国）en-US | 「English」 |
| 中国語（簡体字）zh-CN | 「简体中文」 |
| 中国語（繁体字）zh-TW | 「繁體中文」 |
| 中国語（香港）zh-HK | 「繁體中文」と同じ内容 |

- 文字数の上限: アプリ名 30・短い説明 80・詳しい説明 4000・リリースノート 500
- 詳しい説明は、ユーザー補助の用途を冒頭に書く（design.md §11.2。審査で参照される）。
  内容はアプリ内のユーザー補助の説明文（design-i18n.md §6.3）と食い違わないようにする
- 末尾に「誤りの指摘を歓迎する」を書く（design-i18n.md §6.5）
- アプリ名は全言語で「volumetap」
- 訳語は design-i18n.md §6.1 の用語集に合わせる。母語話者の確認はしていない

---

## English

### 短い説明

```
Short press a volume key to scroll a screen or tap a spot. Long press = volume.
```

### 詳しい説明

```
volumetap is for people who find touching the screen difficult. A short press of a volume key scrolls the screen one page at a time, or taps a position you set in advance. A long press still adjusts the volume as usual.

It helps when reading one-handed or lying down, or when finger movement is limited: web pages in your browser, social media timelines, Google Discover, RSS readers, and e-book apps that turn pages with a tap.

■ How it works
• Add the apps you want to use. In other apps, the volume keys work as usual.
• For each app, choose what a short press of each volume key does: swipe to the next screen, swipe back to the previous screen, tap a set position, or adjust the volume.
• Adjust the swipe position and distance, or set the tap position, right on top of the app's actual screen.
• Pause anytime from the Quick Settings tile, for example while watching videos.

■ Accessibility service and privacy
• volumetap uses the Accessibility service only to receive volume key presses and to perform swipes and taps.
• It does not read what is shown on the screen.
• It has no permission to access the internet, so no information is ever sent off the device.
• No ads, no purchases. Open source (MIT License): https://github.com/ayati/volumetap

■ Xiaomi / Redmi / POCO devices
Power saving may stop volumetap in the background. The app shows which settings to change.

■ Translations
The developer's first language is Japanese, and the English and Chinese text includes machine translation. Corrections and suggestions for more natural wording are welcome. Please let us know through GitHub Issues (https://github.com/ayati/volumetap/issues) or by email, with the screen and the wording you would suggest.
```

### リリースノート（v0.15.0）

```
volumetap 0.15.0
• PWAs installed from Chrome (such as yomikake) can now have their own settings, separate from Chrome. Until you add one, Chrome's settings apply to it.
• Add several apps at once, copying the settings of an app you already set up. Search the app list by name.
• Swipe an app left in the list to remove its settings, with Undo. Remove all app settings from the ⋮ menu.
• Fixed: on some devices (such as Xiaomi), returning to the home screen kept the previous app's settings.
```

---

## 简体中文

### 短い説明

```
短按音量键逐屏滚动屏幕，或点击设置好的位置。长按仍为音量调节。
```

### 詳しい説明

```
volumetap 适用于触摸屏幕有困难的情况：短按音量键，即可逐屏滚动屏幕，或点击预先设置的位置。长按音量键时，仍像平常一样调节音量。

适合单手持机、躺着阅读，或手指活动受限时使用：浏览器中的网页、社交媒体的时间线、Google Discover、RSS 阅读器，以及点击翻页的电子书应用。

■ 使用方法
• 添加要使用的应用。在其他应用中，音量键照常工作。
• 可以按应用分别选择短按每个音量键的动作：滑动到下一屏、滑动回到上一屏、点击指定位置，或调节音量。
• 可以直接在应用的实际屏幕上，调整滑动的位置和滚动距离，或设置点击的位置。
• 观看视频时等，可以随时通过快捷设置图块暂停。

■ 无障碍服务与隐私
• volumetap 使用无障碍服务，仅用于接收音量键的按键，以及执行滑动和点击。
• 本应用不会读取屏幕上显示的内容。
• 本应用没有访问互联网的权限，因此不会向设备外发送任何信息。
• 无广告、无付费。开源（MIT 许可证）：https://github.com/ayati/volumetap

■ 小米 / Redmi / POCO 设备
省电功能可能会停止在后台运行的 volumetap。应用内会说明需要更改哪些设置。

■ 关于翻译
开发者的母语是日语，中文文本包含机器翻译。欢迎指出错误或不自然的表达。请通过 GitHub Issues（https://github.com/ayati/volumetap/issues）或电子邮件告诉我们，并注明对应的页面和您建议的表达。
```

### リリースノート（v0.15.0）

```
volumetap 0.15.0
• 通过 Chrome 安装的 PWA（如 yomikake）现在可以单独设置，与 Chrome 分开。添加之前，将使用 Chrome 的设置。
• 可以一次添加多个应用，并复制已设置应用的设置。可以按名称搜索应用列表。
• 在列表中向左滑动应用即可删除其设置，并可撤消。可以从 ⋮ 菜单删除所有应用的设置。
• 修复：在部分设备（如小米）上，返回主屏幕后仍沿用上一个应用的设置。
```

---

## 繁體中文

### 短い説明

```
短按音量鍵逐頁捲動畫面，或輕觸設定好的位置。長按仍為音量調整。
```

### 詳しい説明

```
volumetap 適用於觸碰螢幕有困難的情況：短按音量鍵，即可逐頁捲動畫面，或輕觸預先設定的位置。長按音量鍵時，仍像平常一樣調整音量。

適合單手持機、躺著閱讀，或手指活動受限時使用：瀏覽器中的網頁、社群媒體的動態、Google Discover、RSS 閱讀器，以及輕觸翻頁的電子書應用程式。

■ 使用方式
• 新增要使用的應用程式。在其他應用程式中，音量鍵會照常運作。
• 可以依應用程式分別選擇短按每個音量鍵的動作：滑動到下一頁、滑動回到上一頁、輕觸指定位置，或調整音量。
• 可以直接在應用程式的實際畫面上，調整滑動的位置和捲動距離，或設定輕觸的位置。
• 觀看影片時等，可以隨時透過快速設定圖塊暫停。

■ 無障礙服務與隱私權
• volumetap 使用無障礙服務，僅用於接收音量鍵的按鍵，以及執行滑動和輕觸。
• 本應用程式不會讀取螢幕上顯示的內容。
• 本應用程式沒有存取網際網路的權限，因此不會將任何資訊傳送到裝置之外。
• 無廣告、無付費。開放原始碼（MIT 授權條款）：https://github.com/ayati/volumetap

■ 小米 / Redmi / POCO 裝置
省電功能可能會停止在背景執行的 volumetap。應用程式內會說明需要變更哪些設定。

■ 關於翻譯
開發者的母語是日語，中文文字包含機器翻譯。歡迎指出錯誤或不自然的表達。請透過 GitHub Issues（https://github.com/ayati/volumetap/issues）或電子郵件告訴我們，並註明對應的畫面和您建議的表達。
```

### リリースノート（v0.15.0）

```
volumetap 0.15.0
• 透過 Chrome 安裝的 PWA（例如 yomikake）現在可以單獨設定，與 Chrome 分開。新增之前，會使用 Chrome 的設定。
• 可以一次新增多個應用程式，並複製已設定應用程式的設定。可以依名稱搜尋應用程式清單。
• 在清單中向左滑動應用程式即可刪除其設定，並可復原。可以從 ⋮ 選單刪除所有應用程式的設定。
• 修正：在部分裝置（例如小米）上，返回主畫面後仍沿用上一個應用程式的設定。
```

---

## 日本語（ja-JP）のリリースノート（v0.15.0）

```
Chrome でインストールした PWA（yomikake など）を、Chrome とは別に設定できるようになりました。追加するまでは Chrome の設定が使われます。
・複数のアプリをまとめて追加でき、設定済みのアプリの設定を写せます。アプリ名で検索もできます。
・一覧でアプリを左へなぞると設定を削除します（元に戻せます）。「⋮」から全アプリの設定を削除できます。
・一部の端末（Xiaomi など）で、ホーム画面に戻っても直前のアプリの設定が効いていたのを直しました。
```
