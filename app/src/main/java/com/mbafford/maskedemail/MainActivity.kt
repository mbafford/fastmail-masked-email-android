package com.mbafford.maskedemail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.text.format.DateUtils
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors) {
        var token by remember { mutableStateOf(TokenStore.load(context)) }
        var changingToken by rememberSaveable { mutableStateOf(false) }
        var browsing by rememberSaveable { mutableStateOf(false) }
        // The address filling the whole screen in big print, if any.
        var bigEmail by rememberSaveable { mutableStateOf<String?>(null) }
        val current = token
        val big = bigEmail
        // Keeps each screen's fields and scroll position while another screen is in front of it.
        val screens = rememberSaveableStateHolder()
        if (current == null || changingToken) {
            TokenScreen(
                hasToken = current != null,
                onSaved = { token = it; changingToken = false },
                onRemoved = { token = null; changingToken = false },
                onCancel = { changingToken = false },
            )
        } else if (big != null) {
            BigPrint(email = big, onClose = { bigEmail = null })
        } else if (browsing) {
            screens.SaveableStateProvider("browse") {
                BrowseScreen(token = current, onBigPrint = { bigEmail = it }, onBack = { browsing = false })
            }
        } else {
            screens.SaveableStateProvider("main") {
                MainScreen(
                    token = current,
                    onChangeToken = { changingToken = true },
                    onBrowse = { browsing = true },
                    onBigPrint = { bigEmail = it },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    token: String,
    onChangeToken: () -> Unit,
    onBrowse: () -> Unit,
    onBigPrint: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()

    var description by rememberSaveable { mutableStateOf("") }
    var pending by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(History.load(context)) }
    // The address shown large at the top: the one just made, or one picked from the list.
    var shownEmail by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = history.firstOrNull { it.email == shownEmail }

    fun create() {
        if (busy) return
        busy = true
        error = null
        focus.clearFocus()
        val wantedDescription = description.trim()
        val wantedPending = pending
        scope.launch {
            try {
                // NonCancellable: once Fastmail has made the address it must reach the history,
                // even if this screen goes away while the request is in flight.
                val entry = withContext(Dispatchers.IO + NonCancellable) {
                    val made = Fastmail.create(token, wantedDescription, wantedPending)
                    Entry(made.email, wantedDescription, System.currentTimeMillis(), wantedPending, made.id)
                        .also { History.add(context, it) }
                }
                history = History.load(context)
                shownEmail = entry.email
                description = ""
                copy(context, entry.email)
                listState.animateScrollToItem(0)
            } catch (e: Fastmail.Failure) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    // An address made by mistake can be taken back for an hour.
    var confirmingDelete by remember { mutableStateOf<Entry?>(null) }

    fun delete(entry: Entry) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO + NonCancellable) {
                    Fastmail.delete(token, entry.id, entry.email)
                    History.remove(context, entry.email)
                }
                history = History.load(context)
            } catch (e: Fastmail.Failure) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    confirmingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete this address?") },
            text = { Text("${entry.email} will stop working. Anyone you gave it to can no longer reach you with it.") },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = null; delete(entry) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") } },
        )
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Masked Email") },
                actions = {
                    TextButton(onClick = onBrowse) { Text("Search") }
                    TextButton(onClick = onChangeToken) { Text("API token") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Who is it for? (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { create() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Delete it automatically if no mail arrives within 24 hours",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                    Switch(checked = pending, onCheckedChange = { pending = it })
                }
            }
            item {
                Button(onClick = { create() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Text("New address", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            shown?.let { entry ->
                item {
                    ShownAddress(
                        entry,
                        onBigPrint,
                        onDelete = { if (canDelete(entry)) confirmingDelete = entry },
                    )
                }
            }
            if (history.isNotEmpty()) {
                item {
                    Text(
                        "Made on this phone",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                items(history, key = { it.email }) { entry ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                shownEmail = entry.email
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(entry.email, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            about(entry),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ShownAddress(entry: Entry, onBigPrint: (String) -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer {
                Text(entry.email, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            }
            Text(
                about(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AddressActions(entry.email, onBigPrint)
            if (canDelete(entry)) {
                TextButton(onClick = onDelete) {
                    Text("Delete this address", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private const val DELETE_WINDOW_MS = 60 * 60 * 1000L

private fun canDelete(entry: Entry) = System.currentTimeMillis() - entry.createdAt < DELETE_WINDOW_MS

@Composable
private fun AddressActions(email: String, onBigPrint: (String) -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { copy(context, email) }) { Text("Copy") }
        FilledTonalButton(onClick = { share(context, email) }) { Text("Share") }
        FilledTonalButton(onClick = { onBigPrint(email) }) { Text("Big print") }
    }
}

/** Every address on the Fastmail account, with a search box. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseScreen(token: String, onBigPrint: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(Fastmail.latest) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var includeDeleted by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    fun refresh() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                records = withContext(Dispatchers.IO) { Fastmail.list(token) }
            } catch (e: Fastmail.Failure) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val needle = search.trim().lowercase()
    val matches = records.orEmpty().filter { record ->
        (includeDeleted || record.state != "deleted") &&
            (needle.isEmpty() || "${record.email} ${record.forDomain} ${record.description}".lowercase().contains(needle))
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("All addresses") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { refresh() }) { Text("Refresh") }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search address, site or description") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (records == null) "" else if (matches.size == 1) "1 address" else "${matches.size} addresses",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text("Include deleted", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
                Switch(checked = includeDeleted, onCheckedChange = { includeDeleted = it })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(matches, key = { it.id }) { record ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openId = if (openId == record.id) null else record.id }
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(record.email, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            about(record),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (openId == record.id) AddressActions(record.email, onBigPrint)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private val BIG_PRINT_DIGIT = Color(0xFFC62828)
private val BIG_PRINT_SYMBOL = Color(0xFF1565C0)

/**
 * Only the address, as large as fits, on white at full brightness and always in landscape,
 * for someone else to read off the screen. Tap or go back to leave.
 */
@Composable
private fun BigPrint(email: String, onClose: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val window = activity.window
    BackHandler(onBack = onClose)
    DisposableEffect(Unit) {
        val orientationBefore = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val brightnessBefore = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        onDispose {
            activity.requestedOrientation = orientationBefore
            bars.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = brightnessBefore }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .clickable(interactionSource = null, indication = null, onClick = onClose)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Two lines, split before the @, so each part can be as wide as the screen.
        // Letters are black, digits red and symbols blue, and the font has a dotted zero,
        // so 0/O and 1/l cannot be mistaken for each other.
        val text = buildAnnotatedString {
            for (c in email.replace("@", "\n@")) {
                val color = when {
                    c.isLetter() -> Color.Black
                    c.isDigit() -> BIG_PRINT_DIGIT
                    else -> BIG_PRINT_SYMBOL
                }
                withStyle(SpanStyle(color = color)) { append(c) }
            }
        }
        BasicText(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily(Font(R.font.jetbrains_mono_bold)),
                textAlign = TextAlign.Center,
            ),
            softWrap = false,
            maxLines = 2,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 400.sp, stepSize = 1.sp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TokenScreen(
    hasToken: Boolean,
    onSaved: (String) -> Unit,
    onRemoved: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entered by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = hasToken, onBack = onCancel)

    fun save() {
        val candidate = entered.trim()
        if (busy || candidate.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Fastmail.check(candidate)
                    TokenStore.save(context, candidate)
                }
                onSaved(candidate)
            } catch (e: Fastmail.Failure) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { TopAppBar(title = { Text("Fastmail API token") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Paste a Fastmail API token that has the \"Masked Email\" scope. " +
                    "You can make one in Fastmail under Settings → Privacy & Security → " +
                    "Manage API tokens. It is stored encrypted on this phone and sent only to Fastmail."
            )
            OutlinedTextField(
                value = entered,
                onValueChange = { entered = it },
                label = { Text(if (hasToken) "New API token" else "API token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { save() },
                enabled = !busy && entered.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Text("Check and save")
                }
            }
            if (hasToken) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { TokenStore.clear(context); onRemoved() }) {
                        Text("Remove token from this phone", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun about(record: Fastmail.Masked): String = listOfNotNull(
    record.state.takeIf { it != "enabled" },
    record.forDomain.ifEmpty { null },
    record.description.ifEmpty { null },
    record.createdAt.take(10).ifEmpty { null },
).joinToString(" · ")

private fun about(entry: Entry): String {
    val parts = listOfNotNull(
        entry.description.ifEmpty { null },
        DateUtils.getRelativeTimeSpanString(
            entry.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        ).toString(),
        if (entry.pending) "deleted if unused for 24 hours" else null,
    )
    return parts.joinToString(" · ")
}

// Android shows its own "copied" confirmation, so no toast here.
private fun copy(context: Context, email: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("Masked email", email))
}

private fun share(context: Context, email: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, email)
    context.startActivity(Intent.createChooser(send, null))
}
