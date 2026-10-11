package dev.nasexplorer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(vm: AppViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NAS Explorer") },
                actions = {
                    TextButton(onClick = { vm.overlay = Overlay.Transfers }) { Text("Transfers") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.overlay = Overlay.EditProfile(null) }) {
                Icon(Icons.Default.Add, "Add NAS")
            }
        },
    ) { pad ->
        if (vm.profiles.isEmpty()) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No NAS yet", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Tap + to add the address of your NAS and the name of a shared folder (SMB share).")
            }
        } else {
            LazyColumn(Modifier.padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(vm.profiles, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { vm.openProfile(p) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🗄️", style = MaterialTheme.typography.headlineMedium)
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    p.address(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.overlay = Overlay.EditProfile(p) }) {
                                Icon(Icons.Default.Edit, "Edit")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(vm: AppViewModel, existing: Profile?) {
    var protocol by remember { mutableStateOf(existing?.protocol ?: Protocol.SMB) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: Protocol.SMB.defaultPort).toString()) }
    var share by remember { mutableStateOf(existing?.share ?: "") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var pass by remember { mutableStateOf(existing?.password ?: "") }
    var domain by remember { mutableStateOf(existing?.domain ?: "") }
    var https by remember { mutableStateOf(existing?.https ?: true) }
    var selfSigned by remember { mutableStateOf(existing?.trustSelfSigned ?: false) }
    var privateKey by remember { mutableStateOf(existing?.privateKey ?: "") }
    var status by remember { mutableStateOf<String?>(null) }

    fun defaultPort(p: Protocol, secure: Boolean) = if (p == Protocol.WEBDAV && !secure) 80 else p.defaultPort

    fun build(): Profile {
        val s = share.trim()
        return Profile(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { host.trim() },
            host = host.trim(),
            port = port.toIntOrNull() ?: defaultPort(protocol, https),
            share = if (protocol == Protocol.SMB) s.trim('/', '\\') else s.trimEnd('/'),
            domain = if (protocol == Protocol.SMB) domain.trim() else "",
            user = user.trim(),
            password = pass,
            protocol = protocol,
            https = https,
            trustSelfSigned = protocol == Protocol.WEBDAV && https && selfSigned,
            privateKey = if (protocol == Protocol.SFTP) privateKey else "",
        )
    }

    val valid = host.isNotBlank() &&
        (protocol != Protocol.SMB || share.isNotBlank()) &&
        (protocol != Protocol.SFTP || user.isNotBlank())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add NAS" else "Edit NAS") },
                navigationIcon = {
                    IconButton(onClick = { vm.overlay = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Protocol.entries.forEach { p ->
                    FilterChip(
                        selected = protocol == p,
                        onClick = {
                            if (port == defaultPort(protocol, https).toString() || port.isBlank()) {
                                port = defaultPort(p, https).toString()
                            }
                            protocol = p
                        },
                        label = { Text(p.label) },
                    )
                }
            }
            Text(
                when (protocol) {
                    Protocol.SMB -> "Windows-style network share – Synology, QNAP, Unraid, TrueNAS, routers."
                    Protocol.SFTP -> "Secure file transfer over SSH – Linux boxes, Raspberry Pi, NAS with SSH enabled."
                    Protocol.WEBDAV -> "Web-based sharing – Nextcloud, Synology WebDAV Server, QNAP, nginx/Apache."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(name, { name = it }, label = { Text("Display name (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(host, { host = it }, label = { Text("NAS address (e.g. 192.168.1.20)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(
                share, { share = it },
                label = {
                    Text(
                        when (protocol) {
                            Protocol.SMB -> "Share name (e.g. media)"
                            Protocol.SFTP -> "Start folder (e.g. /volume1/data, blank = home)"
                            Protocol.WEBDAV -> "Path on server (e.g. /webdav)"
                        }
                    )
                },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(user, { user = it },
                label = { Text(if (protocol == Protocol.SFTP) "Username" else "Username (blank = guest)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it },
                label = { Text(if (protocol == Protocol.SFTP && privateKey.isNotBlank()) "Key passphrase (if any)" else "Password") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (protocol == Protocol.SFTP) {
                OutlinedTextField(privateKey, { privateKey = it },
                    label = { Text("Private key (optional – paste PEM text)") },
                    minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
            }
            if (protocol == Protocol.WEBDAV) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = https, onCheckedChange = {
                        if (port == defaultPort(protocol, https).toString()) port = defaultPort(protocol, it).toString()
                        https = it
                    })
                    Text("  Use HTTPS (recommended – otherwise your password is sent unprotected)")
                }
                if (https) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = selfSigned, onCheckedChange = { selfSigned = it })
                        Text("  Accept this server's self-signed certificate")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (protocol == Protocol.SMB) {
                    OutlinedTextField(domain, { domain = it }, label = { Text("Domain (optional)") },
                        singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(port, { port = it }, label = { Text("Port") },
                    singleLine = true, modifier = Modifier.weight(if (protocol == Protocol.SMB) 0.6f else 1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = valid, onClick = {
                    status = "Testing…"
                    vm.testProfile(build()) { status = it }
                }) { Text("Test connection") }
                Button(enabled = valid, onClick = { vm.saveProfile(build()) }) { Text("Save") }
            }
            if (existing != null) {
                TextButton(onClick = { vm.deleteProfile(existing) }) {
                    Text("Delete this NAS", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
