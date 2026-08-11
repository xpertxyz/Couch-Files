package com.xpertxyz.sharetotv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.xpertxyz.sharetotv.ui.theme.ShareToTVTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {

    private val sharedUris = mutableStateListOf<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always-dark app: force light system-bar icons regardless of system theme
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Only consume the launch intent once - a restored activity redelivers the original
        // ACTION_SEND and would silently re-upload the same files
        if (savedInstanceState == null) collectShared(intent)
        setContent {
            ShareToTVTheme {
                AppScreen(sharedUris)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        collectShared(intent)
    }

    private fun collectShared(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { sharedUris.add(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { sharedUris.addAll(it) }
        }
    }
}

enum class Status { RUNNING, DONE, FAILED }

class TransferUi(val name: String, val total: Long, val toTv: Boolean) {
    var bytes by mutableLongStateOf(0L)
    var status by mutableStateOf(Status.RUNNING)

    /** Read from the IO thread mid-copy; set on disconnect. */
    @Volatile
    var cancelled = false
}

private fun parseQr(text: String): Triple<String, Int, String>? {
    val uri = text.toUri()
    if (uri.scheme != "sharetotv") return null
    val host = uri.getQueryParameter("host") ?: return null
    val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 8899
    return Triple(host, port, uri.getQueryParameter("name") ?: host)
}

@Composable
fun AppScreen(sharedUris: MutableList<Uri>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var client by remember { mutableStateOf<TvClient?>(null) }
    var tvName by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var listing by remember { mutableStateOf(TvClient.Listing(emptyList(), emptyList())) }
    val transfers = remember { mutableStateListOf<TransferUi>() }
    val uploadLock = remember { Mutex() }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun refresh() {
        val c = client ?: return
        val p = path
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { c.list(p) } }
                .onSuccess { listing = it }
                .onFailure { toast("Could not load folder: ${it.message}") }
        }
    }

    fun connect(host: String, port: Int) {
        scope.launch {
            val candidate = TvClient(host, port)
            runCatching {
                withContext(Dispatchers.IO) { candidate.ping() to candidate.list("") }
            }.onSuccess { (name, rootListing) ->
                client = candidate
                tvName = name
                path = ""
                listing = rootListing
            }.onFailure { toast("Could not reach $host:$port — ${it.message}") }
        }
    }

    fun disconnect() {
        transfers.forEach { if (it.status == Status.RUNNING) it.cancelled = true }
        client = null
        tvName = ""
        path = ""
        transfers.clear()
        listing = TvClient.Listing(emptyList(), emptyList())
    }

    fun uploadAll(uris: List<Uri>) {
        val c = client ?: return
        val target = path
        scope.launch {
            uploadLock.withLock {
                for (uri in uris) {
                    if (client !== c) break // disconnected mid-batch: drop the rest
                    val meta = runCatching {
                        withContext(Dispatchers.IO) { resolveMeta(context, uri) }
                    }.getOrNull()
                    if (meta == null) {
                        toast("Could not read a selected file")
                        continue
                    }
                    val item = TransferUi(meta.name, meta.size, toTv = true)
                    transfers.add(0, item)
                    runCatching {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(meta.uri)!!.use { input ->
                                c.upload(input, meta.name, meta.size, target, { item.cancelled }) {
                                    item.bytes = it
                                }
                            }
                        }
                    }.onSuccess { item.status = Status.DONE }
                        .onFailure {
                            item.status = Status.FAILED
                            if (!item.cancelled) toast("Failed to send ${meta.name}: ${it.message}")
                        }
                }
            }
            refresh()
        }
    }

    fun download(file: TvClient.RemoteFile) {
        val c = client ?: return
        if (transfers.any { !it.toTv && it.name == file.name && it.status == Status.RUNNING }) return
        val item = TransferUi(file.name, file.size, toTv = false)
        transfers.add(0, item)
        val p = path
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    c.downloadToDownloads(context, p, file, { item.cancelled }) { item.bytes = it }
                }
            }.onSuccess {
                item.status = Status.DONE
                toast("${file.name} saved to Downloads")
            }.onFailure {
                item.status = Status.FAILED
                if (!item.cancelled) toast("Download failed: ${it.message}")
            }
        }
    }

    // Heartbeat: tells the TV a phone is connected (it hides its QR), and detects the TV
    // going away - three consecutive failures drop the session with a message
    LaunchedEffect(client) {
        val c = client ?: return@LaunchedEffect
        var failures = 0
        while (true) {
            val ok = runCatching { withContext(Dispatchers.IO) { c.ping() } }.isSuccess
            failures = if (ok) 0 else failures + 1
            if (failures >= 3) {
                toast("Lost connection to ${tvName.ifEmpty { "the TV" }}")
                disconnect()
                break
            }
            delay(5000.milliseconds)
        }
    }

    // Files arriving via the system Share sheet auto-send once connected
    LaunchedEffect(client, sharedUris.size) {
        if (client != null && sharedUris.isNotEmpty()) {
            val batch = sharedUris.toList()
            sharedUris.clear()
            uploadAll(batch)
        }
    }

    LaunchedEffect(path) { refresh() }

    if (client == null) {
        ConnectScreen(sharedUris.size, ::connect)
    } else {
        BrowserScreen(
            tvName = tvName,
            address = "${client!!.host}:${client!!.port}",
            path = path,
            listing = listing,
            transfers = transfers,
            onNavigate = { path = it },
            onRefresh = { refresh() },
            onDownload = { download(it) },
            onSend = { uris -> if (uris.isNotEmpty()) uploadAll(uris) },
            onMkdir = { name ->
                val c = client ?: return@BrowserScreen
                val newPath = if (path.isEmpty()) name else "$path/$name"
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { c.mkdir(newPath) } }
                        .onSuccess { refresh() }
                        .onFailure { toast("Could not create folder: ${it.message}") }
                }
            },
            onDisconnect = { disconnect() },
        )
    }
}

// ---------------------------------------------------------------------------
// Connect screen
// ---------------------------------------------------------------------------

@Composable
private fun Eyebrow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
        color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun ConnectScreen(pendingShareCount: Int, onConnect: (String, Int) -> Unit) {
    val context = LocalContext.current
    val discovery = remember { TvDiscovery(context) }
    LaunchedEffect(Unit) { discovery.start() }
    DisposableEffect(Unit) { onDispose { discovery.stop() } }
    val tvs by discovery.tvs.collectAsState()
    var manual by remember { mutableStateOf("") }
    var confirmExit by remember { mutableStateOf(false) }

    BackHandler { confirmExit = true }
    if (confirmExit) {
        val activity = LocalActivity.current
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Exit Couch Files?") },
            confirmButton = {
                TextButton(onClick = { activity?.finish() }) { Text("Exit") }
            },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Stay") } },
        )
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text ->
            val parsed = parseQr(text)
            if (parsed == null) {
                Toast.makeText(context, "That QR is not from the Couch Files app", Toast.LENGTH_SHORT).show()
            } else {
                onConnect(parsed.first, parsed.second)
            }
        }
    }

    Scaffold { innerPadding ->
        Column(
            Modifier.padding(innerPadding).fillMaxSize().padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Eyebrow("COUCH FILES")
            Text(
                if (tvs.isEmpty()) "Find your TV" else "Share files to TV",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Light),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (tvs.isEmpty()) "Open the Couch Files app on the television first."
                else "Start sharing — tap a TV below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (pendingShareCount > 0) {
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(
                        "$pendingShareCount file(s) ready — they'll send as soon as you connect.",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            RadarRow(found = tvs.isNotEmpty())
            Spacer(Modifier.height(12.dp))

            tvs.forEach { tv ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onConnect(tv.host, tv.port) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(tv.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${tv.host}:${tv.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Point at the QR code on your TV")
                            .setBeepEnabled(false)
                            .setOrientationLocked(true)
                            .setCaptureActivity(BrandedScanActivity::class.java),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Scan the QR on the TV screen")
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Or type the address shown on the TV",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    label = { Text("192.168.1.20:8899") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = manual.isNotBlank(),
                    onClick = {
                        val host = manual.trim().removePrefix("http://").substringBefore(':').substringBefore('/')
                        val port = manual.trim().substringAfterLast(':', "").substringBefore('/').toIntOrNull() ?: 8899
                        onConnect(host, port)
                    },
                ) { Text("Connect") }
            }

            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(CircleShape)
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, "https://xpertxyz.in".toUri()),
                            )
                        }
                    }
                    .padding(12.dp),
            ) {
                Image(
                    painterResource(R.drawable.xpertxyz_logo),
                    contentDescription = "XpertXYZ",
                    modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Designed & developed by XpertXYZ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RadarRow(found: Boolean) {
    val pulse = rememberInfiniteTransition(label = "radar")
    val alpha by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "radarAlpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Tv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp).alpha(if (found) 1f else alpha),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (found) "TVs on your Wi-Fi" else "Searching your Wi-Fi…",
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

// ---------------------------------------------------------------------------
// Browser screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(
    tvName: String,
    address: String,
    path: String,
    listing: TvClient.Listing,
    transfers: List<TransferUi>,
    onNavigate: (String) -> Unit,
    onRefresh: () -> Unit,
    onDownload: (TvClient.RemoteFile) -> Unit,
    onSend: (List<Uri>) -> Unit,
    onMkdir: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var newFolder by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> onSend(uris) }

    BackHandler {
        if (path.isNotEmpty()) {
            onNavigate(path.substringBeforeLast('/', ""))
        } else {
            confirmDisconnect = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tvName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { newFolder = true }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { confirmDisconnect = true }) {
                        Icon(Icons.Outlined.LinkOff, contentDescription = "Disconnect")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null) },
                    text = { Text(if (path.isEmpty()) "Send here" else "Send to ${path.substringAfterLast('/')}") },
                )
            }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            val running = transfers.count { it.status == Status.RUNNING }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Files") })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(if (running > 0) "Transfers ($running)" else "Transfers") },
                )
            }
            if (tab == 0) {
                FilesTab(path, listing, transfers, onNavigate, onDownload)
            } else {
                TransfersTab(transfers)
            }
        }
    }

    if (confirmDisconnect) {
        val transferRunning = transfers.any { it.status == Status.RUNNING }
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect from $tvName?") },
            text = {
                Text(
                    if (transferRunning) "Disconnecting now cancels the running transfer."
                    else "You'll return to the TV search screen.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        onDisconnect()
                    },
                ) { Text(if (transferRunning) "Disconnect anyway" else "Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Stay") } },
        )
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Folder name") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onMkdir(name.trim())
                        newFolder = false
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FilesTab(
    path: String,
    listing: TvClient.Listing,
    transfers: List<TransferUi>,
    onNavigate: (String) -> Unit,
    onDownload: (TvClient.RemoteFile) -> Unit,
) {
    val context = LocalContext.current
    var confirmDownload by remember { mutableStateOf<TvClient.RemoteFile?>(null) }

    confirmDownload?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text("Download ${f.name}?") },
            text = {
                Text(
                    "${Formatter.formatFileSize(context, f.size)} — it will be saved to this " +
                        "phone's Downloads folder.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDownload(f)
                        confirmDownload = null
                    },
                ) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { confirmDownload = null }) { Text("Cancel") } },
        )
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { Breadcrumbs(path, onNavigate) }

        if (listing.dirs.isEmpty() && listing.files.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = null,
                            Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("This folder is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Use “Send here” to put files in it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(listing.dirs, key = { "d/$it" }) { dir ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onNavigate(if (path.isEmpty()) dir else "$path/$dir") }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(16.dp))
                    Text(dir, style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            items(listing.files, key = { "f/${it.name}" }) { f ->
                val active = transfers.firstOrNull { !it.toTv && it.name == f.name && it.status == Status.RUNNING }
                Column(
                    Modifier.fillMaxWidth().clickable { confirmDownload = f }.padding(vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            iconForName(f.name),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            f.name,
                            Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            Formatter.formatFileSize(context, f.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (active != null) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (active.total > 0) active.bytes.toFloat() / active.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            item { Spacer(Modifier.height(96.dp)) } // room for the FAB
    }
}

@Composable
private fun TransfersTab(transfers: List<TransferUi>) {
    if (transfers.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Send,
                contentDescription = null,
                Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(10.dp))
            Text("No transfers yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Sent files go to the TV's Downloads/CouchFiles folder; downloads land in this phone's Downloads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "To TV → Downloads/CouchFiles  •  From TV → this phone's Downloads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            items(transfers) { t -> TransferRow(t) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onNavigate("") }) { Text("Downloads/CouchFiles") }
        if (path.isNotEmpty()) {
            var acc = ""
            path.split('/').forEach { segment ->
                acc = if (acc.isEmpty()) segment else "$acc/$segment"
                val target = acc
                Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onNavigate(target) }) {
                    Text(segment, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun TransferRow(t: TransferUi) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (t.toTv) Icons.AutoMirrored.Outlined.Send else Icons.Outlined.Tv,
                contentDescription = null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(8.dp))
            Text(t.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(
                when (t.status) {
                    Status.RUNNING ->
                        "${Formatter.formatFileSize(context, t.bytes)} / ${Formatter.formatFileSize(context, t.total)}"
                    Status.DONE -> if (t.toTv) "Sent" else "Saved"
                    Status.FAILED -> "Failed"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (t.status == Status.RUNNING) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (t.total > 0) t.bytes.toFloat() / t.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun iconForName(name: String): ImageVector =
    when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> Icons.Outlined.Image
        "mp4", "mkv", "avi", "mov", "webm", "m4v", "ts" -> Icons.Outlined.Movie
        "mp3", "m4a", "flac", "wav", "ogg", "aac" -> Icons.Outlined.MusicNote
        "apk" -> Icons.Outlined.Android
        "pdf", "doc", "docx", "txt", "md" -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }

private class PickedFile(val uri: Uri, val name: String, val size: Long)

/** Name + size for a content Uri; buffers to cache when the provider won't report a size. */
private fun resolveMeta(context: Context, uri: Uri): PickedFile {
    var name = "file"
    var size = -1L
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
            if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
        }
    }
    if (size >= 0) return PickedFile(uri, name, size)
    // Rare: provider reports no size, but the TV needs Content-Length. Spool through cache once.
    val tmp = File.createTempFile("send", null, context.cacheDir)
    context.contentResolver.openInputStream(uri)!!.use { input ->
        tmp.outputStream().use { input.copyTo(it) }
    }
    return PickedFile(Uri.fromFile(tmp), name, tmp.length())
}
