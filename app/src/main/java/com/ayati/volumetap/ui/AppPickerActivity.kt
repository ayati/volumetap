package com.ayati.volumetap.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.lifecycleScope
import com.ayati.volumetap.R
import com.ayati.volumetap.data.AppProfile
import com.ayati.volumetap.data.AppSettings
import com.ayati.volumetap.data.SettingsRepository
import kotlinx.coroutines.launch

/**
 * アプリごとの割り当てを追加する画面（design-pageturn.md §7.1、design-pwa-picker.md §3）。
 *
 * 複数のアプリを選んで、同じ割り当てでまとめて追加できる。割り当ては初期値（−で送る／＋で戻す）か、
 * 設定済みのアプリの割り当てを写す。1 件だけ追加したときは、今までどおりそのアプリの設定画面へ進む。
 * 設定済みのアプリを押したときは設定画面を開くだけで、この画面は閉じない（選んだ状態を残すため）。
 */
class AppPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = SettingsRepository(this)
        setContent {
            VolumeTapTheme {
                val settings by repository.settings.collectAsState(initial = AppSettings())
                val apps by produceState<List<InstalledApp>?>(initialValue = null) {
                    value = InstalledApps.launchable(this@AppPickerActivity)
                }
                AppPickerScreen(
                    onBack = ::finish,
                    apps = apps,
                    profiles = settings.profiles,
                    onOpen = { pkg ->
                        startActivity(ProfileEditActivity.intent(this@AppPickerActivity, pkg))
                    },
                    onAdd = { packages, template ->
                        lifecycleScope.launch {
                            repository.addProfiles(packages, template)
                            packages.singleOrNull()?.let {
                                startActivity(ProfileEditActivity.intent(this@AppPickerActivity, it))
                            }
                            finish()
                        }
                    },
                )
            }
        }
    }
}

/** 選んだアプリ（パッケージ名の集合）を、画面の回転をまたいで保つ。 */
private val SelectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerScreen(
    onBack: () -> Unit,
    apps: List<InstalledApp>?,
    profiles: Map<String, AppProfile>,
    onOpen: (String) -> Unit,
    onAdd: (List<String>, AppProfile) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet<String>()) }
    /** 写し元のパッケージ名。null は初期値（−で送る／＋で戻す）。 */
    var templateOf by rememberSaveable { mutableStateOf<String?>(null) }

    // 選んだあとで別の画面から追加されたアプリは、件数にも追加にも含めない。
    val pending = selected - profiles.keys
    val shown = remember(apps, query) {
        apps?.filter { AppSearch.matches(query, it.label, it.packageName) }
    }
    // 一括選択の対象（design-key-options.md §3.2）。表示中で、まだ割り当てのないアプリ。
    val selectable = remember(shown, profiles) {
        shown.orEmpty().map { it.packageName }.filterTo(HashSet()) { it !in profiles }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_app)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (selectable.isNotEmpty()) {
                        SelectionMenu(
                            onSelectAll = { selected = PickerSelection.selectAll(selected, selectable) },
                            onClearAll = { selected = PickerSelection.clearAll(selected, selectable) },
                            onInvert = { selected = PickerSelection.invert(selected, selectable) },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (pending.isNotEmpty()) {
                AddBar(
                    count = pending.size,
                    templateOf = templateOf?.takeIf { it in profiles },
                    candidates = profiles.keys
                        .map { pkg -> pkg to (apps?.find { it.packageName == pkg }?.label ?: pkg) }
                        .sortedBy { it.second },
                    onTemplateChange = { templateOf = it },
                    onAdd = {
                        val template = templateOf?.let { profiles[it] } ?: AppProfile.NEW_APP
                        // 一覧の並び（アプリ名順）で追加する。
                        val ordered = apps?.map { it.packageName }?.filter { it in pending } ?: pending.toList()
                        onAdd(ordered, template)
                    },
                )
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
        ) {
            SearchField(query = query, onQueryChange = { query = it })

            if (shown == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                if (shown.isEmpty()) {
                    Text(
                        text = stringResource(R.string.app_picker_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                val listState = rememberLazyListState()
                val keyboard = LocalSoftwareKeyboardController.current
                // 一覧をスクロールし始めたら、キーボードを閉じて一覧を広く見せる。
                LaunchedEffect(listState.isScrollInProgress) {
                    if (listState.isScrollInProgress) keyboard?.hide()
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    // 注意書きは一覧の先頭に置き、スクロールで流れるようにする（上に固定すると一覧が半分ほどになる）。
                    // 検索しているときは出さない。
                    if (query.isBlank()) {
                        item(key = "hints") { PickerHints() }
                    }
                    items(shown, key = { it.packageName }) { app ->
                        val configured = app.packageName in profiles
                        AppRow(
                            app = app,
                            configured = configured,
                            checked = app.packageName in selected,
                            onClick = {
                                if (configured) {
                                    onOpen(app.packageName)
                                } else {
                                    selected = if (app.packageName in selected) {
                                        selected - app.packageName
                                    } else {
                                        selected + app.packageName
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 上のバーの ⋮。表示中のアプリをまとめて選ぶ・外す・反転する（design-key-options.md §3.1）。 */
@Composable
private fun SelectionMenu(onSelectAll: () -> Unit, onClearAll: () -> Unit, onInvert: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((label, action) in listOf(
                R.string.app_picker_select_all to onSelectAll,
                R.string.app_picker_clear_all to onClearAll,
                R.string.app_picker_invert to onInvert,
            )) {
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        expanded = false
                        action()
                    },
                )
            }
        }
    }
}

/** アプリを追加する画面の注意書き。一覧の先頭に置く。 */
@Composable
private fun PickerHints() {
    Column {
        // Chrome でインストールした PWA は WebAPK として一覧に出て、Chrome とは別に割り当てられる
        // （design-pwa-picker.md §2）。それ以外の PWA はブラウザとして扱われる。
        Text(
            text = stringResource(R.string.app_picker_pwa_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        // ホーム画面の左（-1 画面）の Discover はランチャーの上に重なった Google アプリの
        // ウィンドウで、前面アプリは Google アプリとして観測される。名前からは分からない。
        Text(
            text = stringResource(R.string.app_picker_discover_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        )
        HorizontalDivider()
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.app_picker_search)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.app_picker_search_clear),
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
    )
}

/** 1 件以上選んだときに出す、写し元の選択と「追加（N）」。 */
@Composable
private fun AddBar(
    count: Int,
    templateOf: String?,
    candidates: List<Pair<String, String>>,
    onTemplateChange: (String?) -> Unit,
    onAdd: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // 見出しは上の行に置き、写し元の名前に横幅を使う（「初期値（−で送る／＋で戻す）」が長いため）。
            Text(
                text = stringResource(R.string.app_picker_copy_from),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            run {
                var expanded by remember { mutableStateOf(false) }
                val initial = stringResource(R.string.app_picker_copy_initial)
                val current = candidates.find { it.first == templateOf }?.second ?: initial
                Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(text = current, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text(initial) },
                            onClick = {
                                onTemplateChange(null)
                                expanded = false
                            },
                        )
                        for ((pkg, label) in candidates) {
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onTemplateChange(pkg)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
            Button(
                onClick = onAdd,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.app_picker_add_selected, count))
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, configured: Boolean, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = if (configured) Role.Button else Role.Checkbox, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (configured) {
            // チェックボックスの幅だけ空けて、アイコンの位置をそろえる。
            Spacer(Modifier.size(48.dp))
        } else {
            // 行全体で切り替えるので、チェックボックス自身は押せなくする（読み上げが二重にならない）。
            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.size(48.dp))
        }
        AppIcon(app)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                // PWA のパッケージ名（org.chromium.webapk.…）は利用者に意味がないので、ブラウザ名を出す。
                text = app.webApkHost?.let { stringResource(R.string.app_picker_webapk, it) } ?: app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (configured) {
            Text(
                text = stringResource(R.string.app_picker_configured),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun AppIcon(app: InstalledApp?) {
    val icon = app?.icon
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(40.dp),
        )
    } else {
        Spacer(Modifier.size(40.dp))
    }
}
