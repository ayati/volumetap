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

### リリースノート（v0.14.0）

```
volumetap is now available in English and Chinese (Simplified and Traditional), in addition to Japanese.
• On Android 13 and later, you can choose the language for volumetap alone in Settings → Apps → volumetap → Language.
• On devices set to other languages, volumetap now appears in English.
The English and Chinese text includes machine translation. Corrections are welcome through GitHub Issues or by email.
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

### リリースノート（v0.14.0）

```
volumetap 新增英文和中文（简体、繁体）界面，原有日文界面不变。
• 在 Android 13 及以上版本中，可以在“设置 → 应用 → volumetap → 语言”中，单独为 volumetap 选择语言。
• 在设置为其他语言的设备上，volumetap 现在显示英文。
中文文本包含机器翻译。欢迎通过 GitHub Issues 或电子邮件指出错误。
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

### リリースノート（v0.14.0）

```
volumetap 新增英文和中文（簡體、繁體）介面，原有的日文介面不變。
• 在 Android 13 以上版本中，可以在「設定 → 應用程式 → volumetap → 語言」中，單獨為 volumetap 選擇語言。
• 在設定為其他語言的裝置上，volumetap 現在會顯示英文。
中文文字包含機器翻譯。歡迎透過 GitHub Issues 或電子郵件指出錯誤。
```

---

## 日本語（ja-JP）のリリースノート（v0.14.0）

```
英語と中国語（簡体字・繁体字）の表示に対応しました。日本語の端末での表示は変わりません。
・Android 13 以降では、端末の設定 → アプリ → volumetap →「言語」で、volumetap だけの表示の言語を選べます。
・対応していない言語の端末では、英語で表示するようになりました。
```
