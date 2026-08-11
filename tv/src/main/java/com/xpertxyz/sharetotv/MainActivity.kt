package com.xpertxyz.sharetotv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xpertxyz.sharetotv.ui.theme.AerialTeal
import com.xpertxyz.sharetotv.ui.theme.AmberDeep
import com.xpertxyz.sharetotv.ui.theme.DeepHarbor
import com.xpertxyz.sharetotv.ui.theme.HarborEdge
import com.xpertxyz.sharetotv.ui.theme.HarborRaised
import com.xpertxyz.sharetotv.ui.theme.Mist
import com.xpertxyz.sharetotv.ui.theme.MistDim
import com.xpertxyz.sharetotv.ui.theme.Paper
import com.xpertxyz.sharetotv.ui.theme.ShareToTVTheme
import com.xpertxyz.sharetotv.ui.theme.SignalAmber
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.net.toUri
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity() {

    private var server: FileServer? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (needsStorageGrant()) {
            setContent {
                ShareToTVTheme {
                    Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                        GrantScreen {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    "package:$packageName".toUri(),
                                ),
                            )
                        }
                    }
                }
            }
            return
        }

        val base = baseDir()
        migrateLegacyFiles(base)
        val name = deviceName()
        val started = startServer(base, name)
        server = started
        started?.let { registerService(it.listeningPort, name) }

        setContent {
            ShareToTVTheme {
                Surface(modifier = Modifier.fillMaxSize(), shape = RectangleShape) {
                    if (started == null) {
                        Box(Modifier.fillMaxSize().padding(48.dp)) {
                            Text("Could not start the file server. Restart the app.")
                        }
                    } else {
                        TvApp(started, base, name, localIp(), started.listeningPort)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Came back from the all-files-access settings screen with the grant: start fresh
        if (server == null && !needsStorageGrant()) recreate()
    }

    private fun needsStorageGrant(): Boolean =
        Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()

    /** Public Downloads so every file manager sees the files; pre-Android 11 TVs keep the app dir. */
    private fun baseDir(): File =
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CouchFiles")
        } else {
            File(getExternalFilesDir(null) ?: filesDir, "SharedFiles")
        }.apply { mkdirs() }

    /** One-time move of files from pre-rename locations. Best-effort. */
    private fun migrateLegacyFiles(base: File) {
        val olds = listOf(
            File(getExternalFilesDir(null) ?: filesDir, "SharedFiles"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ShareToTV"),
        )
        for (old in olds) {
            if (!old.isDirectory || old.canonicalPath == base.canonicalPath) continue
            old.listFiles()?.forEach { child ->
                val target = File(base, child.name)
                // renameTo can't move directories out of the app sandbox on scoped storage
                if (!child.renameTo(target)) {
                    runCatching {
                        child.copyRecursively(target)
                        child.deleteRecursively()
                    }
                }
            }
            old.delete()
        }
    }

    /** Tell MediaStore so the file shows up immediately in media apps too. */
    private fun scan(file: File) {
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
    }

    /** Fixed port range so the address shown on screen is typeable; NSD advertises whichever bound. */
    private fun startServer(base: File, name: String): FileServer? {
        var result: FileServer? = null
        val t = Thread {
            for (port in 8899..8905) {
                try {
                    result = FileServer(port, base, name, onSaved = ::scan)
                        .also { it.start(SOCKET_TIMEOUT, false) }
                    break
                } catch (_: IOException) {
                }
            }
        }
        t.start()
        t.join()
        return result
    }

    private fun registerService(port: Int, name: String) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        nsdListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo?) {}
            override fun onRegistrationFailed(i: NsdServiceInfo?, err: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo?, err: Int) {}
        }
        nsdManager = getSystemService(NsdManager::class.java)
        nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener)
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    override fun onDestroy() {
        super.onDestroy()
        nsdListener?.let { runCatching { nsdManager?.unregisterService(it) } }
        server?.stop()
    }

    companion object {
        const val SERVICE_TYPE = "_sharetotv._tcp."
        const val SOCKET_TIMEOUT = 5000
    }
}

private fun localIp(): String =
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress ?: "?"

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

@Composable
fun GrantScreen(onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(DeepHarbor).padding(64.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Eyebrow("COUCH FILES")
        Spacer(Modifier.height(8.dp))
        Text(
            "One-time setup",
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Light),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Received files will be kept in Downloads/ShareToTV, where every file manager on this TV can see them. " +
                "Android asks you to allow file access once for that.",
            style = MaterialTheme.typography.bodyLarge,
            color = MistDim,
        )
        Spacer(Modifier.height(24.dp))
        BrandButton(onClick = onAllow) { Text("Allow file access") }
    }
}

@Composable
fun TvApp(server: FileServer, base: File, deviceName: String, ip: String, port: Int) {
    Row(Modifier.fillMaxSize().background(DeepHarbor)) {
        ConnectRail(server, base, deviceName, ip, port)
        Box(Modifier.width(1.dp).fillMaxHeight().background(HarborEdge))
        FileManager(server, base, Modifier.weight(1f))
    }
}

@Composable
private fun ConnectRail(server: FileServer, base: File, deviceName: String, ip: String, port: Int) {
    val context = LocalContext.current
    val transfer by server.incoming.collectAsState()
    val lastSeen by server.lastSeen.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000.milliseconds)
            now = System.currentTimeMillis()
        }
    }
    val phoneConnected = lastSeen > 0 && now - lastSeen < 15_000
    val qr = remember(ip, port) {
        qrBitmap(
            "sharetotv://connect?host=$ip&port=$port&name=${Uri.encode(deviceName)}",
            size = 400,
            fg = DeepHarbor.toArgb(),
            bg = Paper.toArgb(),
        )
    }

    Column(Modifier.width(340.dp).fillMaxHeight().padding(horizontal = 36.dp, vertical = 24.dp)) {
        Eyebrow("COUCH FILES")
        Spacer(Modifier.height(6.dp))
        Text(
            deviceName,
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Light),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text("http://$ip:$port", style = MaterialTheme.typography.titleMedium, color = SignalAmber)
        Spacer(Modifier.height(2.dp))
        val freeSpace = remember(transfer) {
            android.text.format.Formatter.formatFileSize(context, StatFs(base.absolutePath).availableBytes)
        }
        Text("$freeSpace free on this TV", style = MaterialTheme.typography.labelMedium, color = MistDim)
        Spacer(Modifier.height(14.dp))

        if (phoneConnected) {
            Column(
                Modifier.fillMaxWidth()
                    .background(HarborRaised, RoundedCornerShape(20.dp))
                    .padding(20.dp),
            ) {
                Eyebrow("PHONE CONNECTED", color = AerialTeal)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Browse and send from the Couch Files app on your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MistDim,
                )
            }
        } else {
            QrBadge(qr)
        }

        Spacer(Modifier.weight(1f))
        TransferStatus(transfer)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.xpertxyz_logo),
                contentDescription = "XpertXYZ",
                modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Designed & developed by XpertXYZ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MistDim,
                    maxLines = 1,
                )
                Text(
                    "xpertxyz.in",
                    style = MaterialTheme.typography.labelMedium,
                    color = MistDim,
                    maxLines = 1,
                )
            }

        }
    }
}

/** Brand button: quiet harbor surface, signal-amber when focused. */
@Composable
private fun BrandButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = HarborEdge,
            contentColor = Mist,
            focusedContainerColor = SignalAmber,
            focusedContentColor = AmberDeep,
        ),
        content = content,
    )
}

@Composable
private fun Eyebrow(text: String, color: androidx.compose.ui.graphics.Color = MistDim) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
        color = color,
    )
}

/** The beacon: a warm paper badge in the dark room. */
@Composable
private fun QrBadge(qr: Bitmap) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Paper, RoundedCornerShape(20.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            qr.asImageBitmap(),
            contentDescription = "Connection QR code",
            modifier = Modifier.fillMaxWidth().padding(4.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Scan from the phone app to connect",
            style = MaterialTheme.typography.labelLarge,
            color = DeepHarbor,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TransferStatus(transfer: Transfer?) {
    when {
        transfer == null -> {
            Eyebrow("READY TO RECEIVE", color = AerialTeal)
        }
        transfer.failed -> {
            Eyebrow("TRANSFER FAILED", color = SignalAmber)
            Spacer(Modifier.height(4.dp))
            Text(transfer.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MistDim)
        }
        transfer.done -> {
            Eyebrow("RECEIVED", color = AerialTeal)
            Spacer(Modifier.height(4.dp))
            Text(transfer.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MistDim)
        }
        else -> {
            val frac = if (transfer.total > 0) transfer.bytes.toFloat() / transfer.total else 0f
            Eyebrow("RECEIVING ${(frac * 100).toInt()}%", color = SignalAmber)
            Spacer(Modifier.height(6.dp))
            Text(transfer.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Mist)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).background(HarborEdge, RoundedCornerShape(3.dp))) {
                Box(
                    Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(5.dp)
                        .background(SignalAmber, RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// File manager
// ---------------------------------------------------------------------------

private data class Entry(val file: File, val isDir: Boolean)

@Composable
private fun FileManager(server: FileServer, base: File, modifier: Modifier) {
    val context = LocalContext.current
    val tick by server.filesChanged.collectAsState()
    var relPath by remember { mutableStateOf("") }
    var localBump by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<Entry>()) }
    var selected by remember { mutableStateOf<Entry?>(null) }
    var renaming by remember { mutableStateOf<Entry?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Entry?>(null) }
    var exitPrompt by remember { mutableStateOf(false) }
    var lastBack by remember { mutableLongStateOf(0L) }

    val transfer by server.incoming.collectAsState()
    val receiving = transfer?.let { !it.done && !it.failed } == true
    val activity = LocalActivity.current
    val baseLabel =
        if (base.absolutePath.contains("/Download/")) "Downloads / CouchFiles" else "App storage"

    val currentDir = if (relPath.isEmpty()) base else File(base, relPath)

    LaunchedEffect(tick, relPath, localBump) {
        entries = currentDir.listFiles()
            ?.filter { !it.name.endsWith(".part") }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.map { Entry(it, it.isDirectory) } ?: emptyList()
    }

    BackHandler {
        when {
            relPath.isNotEmpty() -> relPath = relPath.substringBeforeLast('/', "")
            receiving -> exitPrompt = true
            System.currentTimeMillis() - lastBack < 2000 -> activity?.finish()
            else -> {
                lastBack = System.currentTimeMillis()
                Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier.fillMaxHeight().padding(horizontal = 36.dp, vertical = 32.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow(
                    (baseLabel + if (relPath.isEmpty()) "" else " / " + relPath.replace("/", " / "))
                        .uppercase(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (relPath.isEmpty()) "All files" else relPath.substringAfterLast('/'),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Light),
                )
            }
            BrandButton(onClick = { creatingFolder = true }) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New folder")
            }
        }
        Spacer(Modifier.height(16.dp))

        if (entries.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Folder, contentDescription = null, Modifier.size(48.dp), tint = HarborEdge)
                Spacer(Modifier.height(12.dp))
                Text("Nothing here yet", color = MistDim)
                Text("Send files from your phone and they land in this folder.", color = MistDim,
                    style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(entries, key = { it.file.name + it.isDir }) { entry ->
                    // tv-material fires onClick on D-pad key-up even after onLongClick already
                    // triggered — swallow that trailing click or long-press on a folder both
                    // opens the dialog and navigates into it
                    var swallowClick by remember(entry.file.name) { mutableStateOf(false) }
                    ListItem(
                        selected = false,
                        onClick = {
                            if (swallowClick) {
                                swallowClick = false
                            } else if (entry.isDir) {
                                relPath = rel(base, entry.file)
                            } else {
                                selected = entry
                            }
                        },
                        onLongClick = {
                            swallowClick = true
                            selected = entry
                        },
                        headlineContent = {
                            Text(entry.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                if (entry.isDir) "Folder • long-press for options"
                                else android.text.format.Formatter.formatFileSize(context, entry.file.length()) +
                                    " • " + dateFmt.format(Date(entry.file.lastModified())),
                            )
                        },
                        leadingContent = {
                            Icon(
                                iconFor(entry),
                                contentDescription = null,
                                tint = if (entry.isDir) AerialTeal else SignalAmber,
                            )
                        },
                    )
                }
            }
        }
    }

    selected?.let { entry ->
        EntryDialog(
            entry = entry,
            onDismiss = { selected = null },
            onOpen = {
                selected = null
                if (entry.isDir) relPath = rel(base, entry.file) else openFile(context, entry.file)
            },
            onRename = { selected = null; renaming = entry },
            onDelete = { selected = null; confirmDelete = entry },
        )
    }

    confirmDelete?.let { entry ->
        Dialog(onDismissRequest = { confirmDelete = null }) {
            Column(
                Modifier.width(340.dp).background(HarborRaised, RoundedCornerShape(20.dp)).padding(24.dp),
            ) {
                Eyebrow(if (entry.isDir) "DELETE FOLDER" else "DELETE FILE", color = SignalAmber)
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.file.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (entry.isDir) "The folder and everything inside it will be deleted. This can't be undone."
                    else "This can't be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MistDim,
                )
                Spacer(Modifier.height(20.dp))
                Row {
                    BrandButton(
                        onClick = {
                            if (entry.isDir) entry.file.deleteRecursively() else entry.file.delete()
                            MediaScannerConnection.scanFile(context, arrayOf(entry.file.absolutePath), null, null)
                            confirmDelete = null
                            localBump++
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Delete") }
                    Spacer(Modifier.width(8.dp))
                    BrandButton(onClick = { confirmDelete = null }, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                }
            }
        }
    }

    if (exitPrompt) {
        Dialog(onDismissRequest = { exitPrompt = false }) {
            Column(
                Modifier.width(340.dp).background(HarborRaised, RoundedCornerShape(20.dp)).padding(24.dp),
            ) {
                Eyebrow("TRANSFER IN PROGRESS", color = SignalAmber)
                Spacer(Modifier.height(4.dp))
                Text(
                    transfer?.name ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Exiting now cancels the incoming transfer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MistDim,
                )
                Spacer(Modifier.height(20.dp))
                Row {
                    BrandButton(onClick = { exitPrompt = false }, modifier = Modifier.weight(1f)) {
                        Text("Stay")
                    }
                    Spacer(Modifier.width(8.dp))
                    BrandButton(onClick = { activity?.finish() }, modifier = Modifier.weight(1f)) {
                        Text("Exit anyway")
                    }
                }
            }
        }
    }

    renaming?.let { entry ->
        NameDialog(
            title = "Rename",
            initial = entry.file.name,
            confirmLabel = "Rename",
            onDismiss = { renaming = null },
        ) { newName ->
            val clean = FileServer.sanitize(newName)
            val target = File(entry.file.parentFile!!, clean)
            if (target.exists()) {
                Toast.makeText(context, "A file with that name already exists", Toast.LENGTH_SHORT).show()
            } else if (!entry.file.renameTo(target)) {
                Toast.makeText(context, "Could not rename", Toast.LENGTH_SHORT).show()
            } else {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(entry.file.absolutePath, target.absolutePath),
                    null, null,
                )
            }
            renaming = null
            localBump++
        }
    }

    if (creatingFolder) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { creatingFolder = false },
        ) { name ->
            val clean = FileServer.sanitize(name)
            if (!File(currentDir, clean).mkdirs()) {
                Toast.makeText(context, "Could not create folder", Toast.LENGTH_SHORT).show()
            }
            creatingFolder = false
            localBump++
        }
    }
}

private fun rel(base: File, file: File): String =
    file.canonicalPath.removePrefix(base.canonicalPath).trimStart('/')

private fun iconFor(entry: Entry): ImageVector {
    if (entry.isDir) return Icons.Outlined.Folder
    return when (entry.file.extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> Icons.Outlined.Image
        "mp4", "mkv", "avi", "mov", "webm", "m4v", "ts" -> Icons.Outlined.Movie
        "mp3", "m4a", "flac", "wav", "ogg", "aac" -> Icons.Outlined.MusicNote
        "apk" -> Icons.Outlined.Android
        "pdf", "doc", "docx", "txt", "md" -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
}

@Composable
private fun EntryDialog(
    entry: Entry,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(340.dp).background(HarborRaised, RoundedCornerShape(20.dp)).padding(24.dp),
        ) {
            Eyebrow(if (entry.isDir) "FOLDER" else "FILE")
            Spacer(Modifier.height(4.dp))
            Text(
                entry.file.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            BrandButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Open") }
            Spacer(Modifier.height(8.dp))
            BrandButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) { Text("Rename") }
            Spacer(Modifier.height(8.dp))
            BrandButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text(if (entry.isDir) "Delete folder" else "Delete")
            }
            Spacer(Modifier.height(8.dp))
            BrandButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(340.dp).background(HarborRaised, RoundedCornerShape(20.dp)).padding(24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Mist),
                cursorBrush = SolidColor(SignalAmber),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DeepHarbor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row {
                BrandButton(
                    onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                    modifier = Modifier.weight(1f),
                ) { Text(confirmLabel) }
                Spacer(Modifier.width(8.dp))
                BrandButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
        }
    }
}

private fun openFile(context: Context, file: File) {
    // APK install needs the one-time "install unknown apps" grant for this app
    if (file.extension.equals("apk", ignoreCase = true) &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        Toast.makeText(context, "Allow installing, then click Open again", Toast.LENGTH_LONG).show()
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.packageName),
            ),
        )
        return
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val mime = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this TV can open this file", Toast.LENGTH_SHORT).show()
    }
}
