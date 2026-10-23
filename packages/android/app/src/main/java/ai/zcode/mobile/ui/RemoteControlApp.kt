package ai.zcode.mobile.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.zcode.mobile.R
import ai.zcode.mobile.remote.RemoteControlUrlPolicy
import ai.zcode.mobile.remote.TrustDecision
import ai.zcode.mobile.remote.ZCodeEndpoint
import ai.zcode.mobile.web.AttachmentTargets
import ai.zcode.mobile.web.ZCodeFileChooserCallback
import ai.zcode.mobile.web.ZCodeWebViewConfig
import ai.zcode.mobile.web.ZCodeWebViewHost
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun RemoteControlApp(
    endpoint: ZCodeEndpoint?,
    loadUrl: String?,
    endpoints: List<ZCodeEndpoint>,
    pendingTrust: TrustDecision.NeedsTrust?,
    pendingShareText: String?,
    onOpenEndpoint: (ZCodeEndpoint) -> Boolean,
    onOpenRaw: (String?) -> Boolean,
    onCloseRemote: () -> Unit,
    onSaveEndpoint: (ZCodeEndpoint) -> ZCodeEndpoint?,
    onDeleteEndpoint: (String) -> Unit,
    onConfirmTrust: (Uri, String, String?, Boolean) -> Boolean,
    onDismissTrust: () -> Unit,
    onShareTextConsumed: () -> Unit,
    onClearSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var scannerOpen by rememberSaveable { mutableStateOf(false) }
    var launcherError by rememberSaveable { mutableStateOf<String?>(null) }
    var editorTarget by remember { mutableStateOf<ZCodeEndpoint?>(null) }
    val cameraPermissionDenied = stringResource(R.string.remote_scan_permission_denied)
    val invalidScanMessage = stringResource(R.string.remote_scan_invalid)
    val invalidUrlMessage = stringResource(R.string.remote_url_invalid)
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launcherError = null
            scannerOpen = true
        } else {
            launcherError = cameraPermissionDenied
        }
    }
    val openScanner = {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launcherError = null
            scannerOpen = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        if (scannerOpen) {
            RemoteControlQrScanner(
                onClose = { scannerOpen = false },
                onScanned = { raw ->
                    val opened = onOpenRaw(raw)
                    if (opened) {
                        scannerOpen = false
                        launcherError = null
                    } else {
                        launcherError = invalidScanMessage
                    }
                    opened
                },
            )
        } else if (endpoint == null || loadUrl.isNullOrBlank()) {
            EndpointLauncher(
                endpoints = endpoints,
                errorMessage = launcherError,
                onOpenEndpoint = { target ->
                    launcherError = if (onOpenEndpoint(target)) null else invalidUrlMessage
                },
                onOpenRaw = { raw ->
                    launcherError = if (onOpenRaw(raw)) null else invalidUrlMessage
                },
                onScanQrCode = openScanner,
                onAddEndpoint = { editorTarget = ZCodeEndpoint(id = "", name = "", url = "") },
                onEditEndpoint = { editorTarget = it },
                onDeleteEndpoint = onDeleteEndpoint,
            )
        } else {
            RemoteControlWebViewScreen(
                endpoint = endpoint,
                loadUrl = loadUrl,
                pendingShareText = pendingShareText,
                onShareTextConsumed = onShareTextConsumed,
                onReopenRaw = onOpenRaw,
                onCloseRemote = onCloseRemote,
                onClearSession = onClearSession,
            )
        }
    }

    editorTarget?.let { target ->
        EndpointEditorDialog(
            initial = target,
            onDismiss = { editorTarget = null },
            onSubmit = { draft ->
                val saved = onSaveEndpoint(draft)
                editorTarget = null
                saved?.let { onOpenEndpoint(it) }
            },
        )
    }

    pendingTrust?.let { request ->
        TrustEndpointDialog(
            request = request,
            onDismiss = onDismissTrust,
            onConfirm = { name, token, allowHttp ->
                onConfirmTrust(request.uri, name, token, allowHttp)
            },
        )
    }
}

@Composable
private fun EndpointLauncher(
    endpoints: List<ZCodeEndpoint>,
    errorMessage: String?,
    onOpenEndpoint: (ZCodeEndpoint) -> Unit,
    onOpenRaw: (String) -> Unit,
    onScanQrCode: () -> Unit,
    onAddEndpoint: () -> Unit,
    onEditEndpoint: (ZCodeEndpoint) -> Unit,
    onDeleteEndpoint: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val officialName = stringResource(R.string.remote_endpoint_official_name)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.remote_launcher_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.remote_launcher_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onScanQrCode,
        ) {
            Text(stringResource(R.string.remote_scan_button))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            label = { Text(stringResource(R.string.remote_url_label)) },
            placeholder = { Text(stringResource(R.string.remote_url_placeholder)) },
            isError = errorMessage != null,
            supportingText = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = errorMessage ?: stringResource(R.string.remote_url_supporting),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onOpenRaw(input) }) {
                        Text(stringResource(R.string.remote_open_button))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.remote_endpoints_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(onClick = onAddEndpoint) {
                Text(stringResource(R.string.remote_endpoint_add))
            }
        }
        endpoints.forEach { endpoint ->
            Spacer(modifier = Modifier.height(8.dp))
            EndpointCard(
                endpoint = endpoint,
                title = endpoint.name.ifBlank { officialName },
                onOpen = { onOpenEndpoint(endpoint) },
                onEdit = { onEditEndpoint(endpoint) },
                onDelete = { onDeleteEndpoint(endpoint.id) },
            )
        }
    }
}

@Composable
private fun EndpointCard(
    endpoint: ZCodeEndpoint,
    title: String,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = endpoint.displayUrl(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EndpointBadges(endpoint)
                if (endpoint.isOfficial) {
                    Text(
                        text = stringResource(R.string.remote_endpoint_official_hint),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.remote_endpoint_edit))
            }
            if (!endpoint.isOfficial) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.remote_endpoint_delete))
                }
            }
        }
    }
}

@Composable
private fun EndpointBadges(endpoint: ZCodeEndpoint) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (endpoint.isOfficial) {
            BadgeText(stringResource(R.string.remote_endpoint_official_badge))
        } else {
            BadgeText(stringResource(R.string.remote_endpoint_local_badge))
        }
        if (endpoint.isCleartext) {
            BadgeText(stringResource(R.string.remote_endpoint_http_badge))
        }
        if (!endpoint.token.isNullOrBlank()) {
            BadgeText(stringResource(R.string.remote_endpoint_token_badge))
        }
    }
}

@Composable
private fun BadgeText(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EndpointEditorDialog(
    initial: ZCodeEndpoint,
    onDismiss: () -> Unit,
    onSubmit: (ZCodeEndpoint) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var url by rememberSaveable { mutableStateOf(initial.url) }
    var token by rememberSaveable { mutableStateOf(initial.token.orEmpty()) }
    var allowHttp by rememberSaveable { mutableStateOf(initial.trustHttp) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val urlInvalidMessage = stringResource(R.string.remote_endpoint_url_invalid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial.id.isBlank()) R.string.remote_endpoint_add_title
                    else R.string.remote_endpoint_edit_title,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.remote_endpoint_name_label)) },
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.remote_endpoint_url_label)) },
                    placeholder = { Text(stringResource(R.string.remote_endpoint_url_placeholder)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.remote_endpoint_token_label)) },
                    supportingText = { Text(stringResource(R.string.remote_endpoint_token_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowHttp, onCheckedChange = { allowHttp = it })
                    Text(stringResource(R.string.remote_endpoint_allow_http), style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalized = RemoteControlUrlPolicy.normalizeInput(url)
                    if (normalized == null) {
                        error = urlInvalidMessage
                        return@TextButton
                    }
                    onSubmit(
                        initial.copy(
                            name = name.ifBlank { Uri.parse(normalized).host.orEmpty() },
                            url = normalized,
                            token = token.trim().takeIf { it.isNotBlank() },
                            trustHttp = allowHttp,
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.remote_endpoint_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remote_endpoint_cancel))
            }
        },
    )
}

@Composable
private fun TrustEndpointDialog(
    request: TrustDecision.NeedsTrust,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, Boolean) -> Boolean,
) {
    val origin = ZCodeEndpoint.originKeyOf(request.uri)
    var name by rememberSaveable { mutableStateOf(request.uri.host.orEmpty()) }
    var allowHttp by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val needHttpConfirm = stringResource(R.string.remote_trust_need_http_confirm)
    val invalidUrlMessage = stringResource(R.string.remote_url_invalid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_trust_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(origin, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.remote_trust_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (request.cleartext) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.remote_trust_http_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                request.token?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.remote_trust_token_found),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.remote_endpoint_name_label)) },
                )
                if (request.cleartext) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allowHttp, onCheckedChange = { allowHttp = it })
                        Text(
                            stringResource(R.string.remote_endpoint_allow_http),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (request.cleartext && !allowHttp) {
                        error = needHttpConfirm
                        return@TextButton
                    }
                    if (!onConfirm(name, request.token, allowHttp)) {
                        error = invalidUrlMessage
                    }
                },
            ) {
                Text(stringResource(R.string.remote_trust_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remote_endpoint_cancel))
            }
        },
    )
}

@Composable
private fun RemoteControlWebViewScreen(
    endpoint: ZCodeEndpoint,
    loadUrl: String,
    pendingShareText: String?,
    onShareTextConsumed: () -> Unit,
    onReopenRaw: (String?) -> Boolean,
    onCloseRemote: () -> Unit,
    onClearSession: () -> Unit,
) {
    val context = LocalContext.current
    val officialName = stringResource(R.string.remote_endpoint_official_name)
    var host by remember { mutableStateOf<ZCodeWebViewHost?>(null) }
    var progress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var desktopMode by rememberSaveable { mutableStateOf(false) }
    var chooserRequest by remember { mutableStateOf<ZCodeFileChooserCallback?>(null) }
    var chooserDialogOpen by remember { mutableStateOf(false) }
    var cameraTarget by remember { mutableStateOf<AttachmentTargets.CameraTarget?>(null) }

    val finishChooser = { uris: List<Uri> ->
        chooserRequest?.onResult(uris)
        chooserRequest = null
        chooserDialogOpen = false
    }
    val documentsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        AttachmentTargets.persist(context, uris)
        finishChooser(uris)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val target = cameraTarget
        finishChooser(if (saved && target != null && target.exists) listOf(target.uri) else emptyList())
        cameraTarget = null
    }

    BackHandler {
        val view = host?.webView
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            onCloseRemote()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            host?.destroy()
            host = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        RemoteControlTopBar(
            endpoint = endpoint,
            title = endpoint.name.ifBlank { officialName },
            desktopMode = desktopMode,
            onToggleDesktopMode = { desktopMode = it },
            isLoading = isLoading,
            onReload = {
                pageError = null
                host?.webView?.reload()
            },
            onClose = onCloseRemote,
            onClearSession = {
                // 换账号或配对卡死时用：清掉 WebView 的 cookie 与 DOM storage，再从入口地址重进。
                onClearSession()
                pageError = null
                host?.webView?.loadUrl(loadUrl)
            },
        )
        if (isLoading) {
            LinearProgressIndicator(
                progress = { (progress.coerceIn(0, 100) / 100f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!pendingShareText.isNullOrBlank()) {
            ShareHint(
                onDismiss = onShareTextConsumed,
                onPasteHint = {
                    val manager = context.getSystemService(android.content.ClipboardManager::class.java)
                    pendingShareText?.let { text ->
                        manager?.setPrimaryClip(android.content.ClipData.newPlainText("ZCode", text))
                    }
                    onShareTextConsumed()
                },
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            key(desktopMode) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        ZCodeWebViewHost(
                            context = viewContext,
                            config = ZCodeWebViewConfig(endpoint = endpoint, desktopMode = desktopMode),
                            onLoadingChanged = { isLoading = it },
                            onProgressChanged = { progress = it },
                            onMainFrameError = { pageError = it },
                            onExternalNavigation = { uri -> openExternalUrl(viewContext, uri) },
                            onDownloadRequested = { url -> openExternalUrl(viewContext, Uri.parse(url)) },
                            onPairingRequest = { uri -> onReopenRaw(uri.toString()) },
                            onFileChooser = { _, callback ->
                                chooserRequest = callback
                                chooserDialogOpen = true
                                true
                            },
                        ).also { viewHost ->
                            host = viewHost
                            viewHost.load(loadUrl)
                        }.webView
                    },
                    update = { view ->
                        if (view.url != loadUrl && host?.webView === view) {
                            pageError = null
                            view.loadUrl(loadUrl)
                        }
                    },
                )
            }
            if (chooserDialogOpen) {
                AttachmentChooserOverlay(
                    onDismiss = { finishChooser(emptyList()) },
                    onPickDocuments = { documentsLauncher.launch(arrayOf("*/*")) },
                    onPickImages = { documentsLauncher.launch(arrayOf("image/*", "video/*")) },
                    onTakePhoto = {
                        val target = runCatching { AttachmentTargets.createCameraTarget(context) }.getOrNull()
                        if (target == null) {
                            finishChooser(emptyList())
                        } else {
                            cameraTarget = target
                            cameraLauncher.launch(target.uri)
                        }
                    },
                )
            }
            if (pageError != null) {
                RemoteControlErrorOverlay(
                    message = pageError.orEmpty(),
                    onRetry = {
                        pageError = null
                        host?.webView?.reload()
                    },
                )
            }
        }
    }
}

@Composable
private fun AttachmentChooserOverlay(
    onDismiss: () -> Unit,
    onPickDocuments: () -> Unit,
    onPickImages: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.remote_attach_title), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onPickImages,
            ) {
                Text(stringResource(R.string.remote_attach_images))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onTakePhoto,
            ) {
                Text(stringResource(R.string.remote_attach_camera))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onPickDocuments,
            ) {
                Text(stringResource(R.string.remote_attach_files))
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remote_endpoint_cancel))
            }
        }
    }
}

@Composable
private fun ShareHint(onDismiss: () -> Unit, onPasteHint: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.remote_share_copied),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onPasteHint) {
                Text(stringResource(R.string.remote_share_copy_again))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remote_share_dismiss))
            }
        }
    }
}

@Composable
private fun RemoteControlTopBar(
    endpoint: ZCodeEndpoint,
    title: String,
    desktopMode: Boolean,
    onToggleDesktopMode: (Boolean) -> Unit,
    isLoading: Boolean,
    onReload: () -> Unit,
    onClose: () -> Unit,
    onClearSession: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = endpoint.displayUrl(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = if (endpoint.isCleartext) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onToggleDesktopMode(!desktopMode) }) {
                Text(
                    stringResource(
                        if (desktopMode) R.string.remote_mode_desktop else R.string.remote_mode_mobile,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            TextButton(onClick = onReload) {
                Text(stringResource(R.string.remote_reload_button), style = MaterialTheme.typography.labelMedium)
            }
            if (endpoint.isOfficial) {
                // 官方 relay 的会话是 WebView 自己的 Z.ai 登录态，换账号时没有别的退出入口。
                TextButton(onClick = onClearSession) {
                    Text(stringResource(R.string.remote_clear_session), style = MaterialTheme.typography.labelMedium)
                }
            }
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.remote_switch_endpoint), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun RemoteControlErrorOverlay(
    message: String,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = stringResource(R.string.remote_load_failed_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.remote_load_failed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.remote_retry_button))
            }
        }
    }
}

private fun openExternalUrl(context: Context, uri: Uri) {
    if (uri.scheme != "http" && uri.scheme != "https") {
        return
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}

@Composable
private fun RemoteControlQrScanner(
    onClose: () -> Unit,
    onScanned: (String) -> Boolean,
) {
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(onBack = onClose)

    Box(modifier = Modifier.fillMaxSize()) {
        RemoteQrCameraPreview(
            onScanned = onScanned,
            onCameraError = { scanError = it },
        )
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.remote_scan_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.remote_scan_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.remote_close_button))
                }
            }
        }
        scanError?.let { message ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 2.dp,
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RemoteQrCameraPreview(
    onScanned: (String) -> Boolean,
    onCameraError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val consumed = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val invalidQrMessage = stringResource(R.string.remote_scan_invalid)

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            cameraProvider?.unbindAll()
            scanner.close()
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                bindRemoteQrCamera(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    previewView = this,
                    scanner = scanner,
                    cameraExecutor = cameraExecutor,
                    consumed = consumed,
                    disposed = disposed,
                    onProviderReady = { provider -> cameraProvider = provider },
                    onScanned = { raw ->
                        if (!onScanned(raw)) {
                            onCameraError(invalidQrMessage)
                            consumed.set(false)
                        }
                    },
                    onCameraError = onCameraError,
                )
            }
        },
    )
}

private fun bindRemoteQrCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    scanner: BarcodeScanner,
    cameraExecutor: ExecutorService,
    consumed: AtomicBoolean,
    disposed: AtomicBoolean,
    onProviderReady: (ProcessCameraProvider) -> Unit,
    onScanned: (String) -> Unit,
    onCameraError: (String) -> Unit,
) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener(
        {
            val provider = runCatching { providerFuture.get() }
                .getOrElse { error ->
                    previewView.post {
                        onCameraError(error.message ?: context.getString(R.string.remote_scan_camera_failed))
                    }
                    return@addListener
                }
            if (disposed.get()) {
                provider.unbindAll()
                return@addListener
            }

            onProviderReady(provider)
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        analyzeRemoteQrImage(
                            scanner = scanner,
                            imageProxy = imageProxy,
                            consumed = consumed,
                            previewView = previewView,
                            onScanned = onScanned,
                        )
                    }
                }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }.onFailure { error ->
                previewView.post {
                    onCameraError(error.message ?: context.getString(R.string.remote_scan_camera_failed))
                }
            }
        },
        ContextCompat.getMainExecutor(context),
    )
}

@OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun analyzeRemoteQrImage(
    scanner: BarcodeScanner,
    imageProxy: ImageProxy,
    consumed: AtomicBoolean,
    previewView: PreviewView,
    onScanned: (String) -> Unit,
) {
    if (consumed.get()) {
        imageProxy.close()
        return
    }

    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            val rawValue = barcodes.firstNotNullOfOrNull { barcode ->
                barcode.rawValue?.takeIf { it.isNotBlank() }
            }
            if (rawValue != null && consumed.compareAndSet(false, true)) {
                previewView.post {
                    onScanned(rawValue)
                }
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}
