package com.solar.android

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private val Bg = Color(0xFF0A0F14)
private val CardBg = Color(0xFF151B22)
private val Accent = Color(0xFF8BFF6A)

data class Model(val id: String, val name: String, val desc: String)
data class Project(val id: String, val name: String, val description: String, val owner: String?, val repo: String?, val ref: String)
data class Session(val id: String, val title: String, val projectId: String?, val modelId: String?)
data class Message(val id: String, val role: String, val text: String)
data class Repo(val fullName: String, val owner: String, val name: String, val description: String?)

class SolarApi(context: Context) {
    private val prefs = context.getSharedPreferences("solar", Context.MODE_PRIVATE)
    private val client = OkHttpClient()

    var baseUrl: String
        get() = prefs.getString("base", "http://10.0.2.2:8080")!!.trimEnd('/')
        set(v) = prefs.edit().putString("base", v.trim().trimEnd('/')).apply()

    val authToken: String? get() = prefs.getString("auth_token", null)
    val pendingAuth: String? get() = prefs.getString("pending_auth", null)

    fun saveAuth(token: String, login: String, name: String?) {
        prefs.edit().putString("auth_token", token).putString("github_login", login).putString("github_name", name).apply()
    }
    fun clearAuth() {
        prefs.edit().remove("auth_token").remove("github_login").remove("github_name").remove("pending_auth").apply()
    }
    fun setPendingAuth(id: String?) {
        prefs.edit().apply { if (id == null) remove("pending_auth") else putString("pending_auth", id) }.apply()
    }
    fun savedLogin(): String? = prefs.getString("github_login", null)
    fun selectedModel(): String = prefs.getString("selected_model", "") ?: ""
    fun setSelectedModel(id: String) = prefs.edit().putString("selected_model", id).apply()

    private fun request(path: String, method: String = "GET", body: String? = null): Request {
        val b = Request.Builder().url(baseUrl + path)
        authToken?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer " + it) }
        if (body != null) {
            b.header("Content-Type", "application/json")
            b.method(method, body.toRequestBody("application/json".toMediaType()))
        } else if (method != "GET") {
            b.method(method, null)
        }
        return b.build()
    }

    private fun read(response: okhttp3.Response, fallback: String): String {
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw Exception(runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null } ?: fallback)
        return text
    }

    suspend fun models(): List<Model> = withContext(Dispatchers.IO) {
        client.newCall(request("/api/models")).execute().use { r ->
            val a = JSONObject(read(r, "Unable to load models.")).getJSONArray("models")
            List(a.length()) { i ->
                val x = a.getJSONObject(i)
                Model(x.optString("id"), x.optString("displayName", x.optString("id")), x.optString("description").ifBlank { x.optString("provider") })
            }
        }
    }

    suspend fun projects(): List<Project> = withContext(Dispatchers.IO) {
        client.newCall(request("/api/projects")).execute().use { r ->
            val a = JSONObject(read(r, "Unable to load projects.")).getJSONArray("projects")
            List(a.length()) { i ->
                val x = a.getJSONObject(i)
                Project(x.optString("id"), x.optString("name"), x.optString("description"),
                    x.optString("github_owner").ifBlank { null }, x.optString("github_repo").ifBlank { null },
                    x.optString("github_ref", "main"))
            }
        }
    }

    suspend fun createProject(name: String, description: String, owner: String?, repo: String?, ref: String): Project =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("name", name).put("description", description)
                .put("githubOwner", owner).put("githubRepo", repo).put("githubRef", ref.ifBlank { "main" }).toString()
            client.newCall(request("/api/projects", "POST", body)).execute().use { r ->
                val x = JSONObject(read(r, "Unable to create project."))
                Project(x.optString("id"), x.optString("name"), x.optString("description"),
                    x.optString("github_owner").ifBlank { null }, x.optString("github_repo").ifBlank { null },
                    x.optString("github_ref", "main"))
            }
        }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        client.newCall(request("/api/projects/" + Uri.encode(id), "DELETE")).execute().use { r -> read(r, "Unable to delete project.") }
    }

    suspend fun sessions(): List<Session> = withContext(Dispatchers.IO) {
        client.newCall(request("/api/chat/sessions")).execute().use { r ->
            val a = JSONObject(read(r, "Unable to load chats.")).getJSONArray("sessions")
            List(a.length()) { i ->
                val x = a.getJSONObject(i)
                Session(x.optString("id"), x.optString("title", "New chat"),
                    x.optString("project_id").ifBlank { null }, x.optString("model_id").ifBlank { null })
            }
        }
    }

    suspend fun createSession(projectId: String?, modelId: String?): Session = withContext(Dispatchers.IO) {
        val body = JSONObject().put("title", "New chat").put("projectId", projectId).put("modelId", modelId).toString()
        client.newCall(request("/api/chat/sessions", "POST", body)).execute().use { r ->
            val x = JSONObject(read(r, "Unable to create chat."))
            Session(x.optString("id"), x.optString("title", "New chat"),
                x.optString("project_id").ifBlank { null }, x.optString("model_id").ifBlank { null })
        }
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        client.newCall(request("/api/chat/sessions/" + Uri.encode(id), "DELETE")).execute().use { r -> read(r, "Unable to delete chat.") }
    }

    suspend fun messages(id: String): List<Message> = withContext(Dispatchers.IO) {
        client.newCall(request("/api/chat/sessions/" + Uri.encode(id) + "/messages")).execute().use { r ->
            val a = JSONObject(read(r, "Unable to load messages.")).getJSONArray("messages")
            List(a.length()) { i ->
                val x = a.getJSONObject(i)
                Message(x.optString("id"), x.optString("role"), x.optString("content"))
            }
        }
    }

    suspend fun repos(): List<Repo> = withContext(Dispatchers.IO) {
        client.newCall(request("/api/github/repositories")).execute().use { r ->
            val a = JSONArray(read(r, "Unable to load GitHub repositories."))
            List(a.length()) { i ->
                val x = a.getJSONObject(i); val o = x.optJSONObject("owner")
                Repo(x.optString("full_name"), o?.optString("login").orEmpty(), x.optString("name"), x.optString("description").ifBlank { null })
            }
        }
    }

    suspend fun startGithub(): Pair<String, String> = withContext(Dispatchers.IO) {
        client.newCall(request("/api/auth/github/mobile/start", "POST")).execute().use { r ->
            val x = JSONObject(read(r, "Unable to start GitHub login."))
            Pair(x.optString("requestId"), x.optString("authorizationUrl"))
        }
    }

    suspend fun githubStatus(id: String): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(request("/api/auth/github/mobile/status?requestId=" + Uri.encode(id))).execute().use { r ->
            JSONObject(read(r, "Unable to check GitHub login."))
        }
    }

    suspend fun me(): Boolean = withContext(Dispatchers.IO) {
        if (authToken.isNullOrBlank()) return@withContext false
        client.newCall(request("/api/auth/me")).execute().use { it.isSuccessful }
    }

    suspend fun runAgent(message: String, model: String, sessionId: String, projectId: String?, writes: Boolean, termux: Boolean, internet: Boolean, browser: Boolean): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("message", message).put("modelId", model).put("sessionId", sessionId).put("projectId", projectId)
                .put("stream", false).put("allowWrites", writes).put("allowTermux", termux)
                .put("allowInternet", internet).put("allowBrowser", browser).put("allowFallback", true).toString()
            client.newCall(request("/api/agent/run", "POST", body)).execute().use { r ->
                val x = JSONObject(read(r, "Solar request failed."))
                x.optString("message", x.optString("answer", "No response returned."))
            }
        }

    fun open(context: Context, url: String) {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    }
    suspend fun logout() {
        withContext(Dispatchers.IO) { runCatching { client.newCall(request("/api/auth/logout", "POST")).execute().close() } }
        clearAuth()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SolarTheme { SolarApp(this) } }
    }
}

@Composable
fun SolarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = CardBg, primary = Accent, onPrimary = Color.Black), content = content)
}

@Composable
fun SolarApp(context: Context) {
    val api = remember { SolarApi(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf("Chat") }
    var user by remember { mutableStateOf(api.savedLogin()) }
    var models by remember { mutableStateOf(emptyList<Model>()) }
    var selectedModel by remember { mutableStateOf(api.selectedModel()) }
    var projects by remember { mutableStateOf(emptyList<Project>()) }
    var sessions by remember { mutableStateOf(emptyList<Session>()) }
    var repos by remember { mutableStateOf(emptyList<Repo>()) }
    var current by remember { mutableStateOf<Session?>(null) }
    var messages by remember { mutableStateOf(emptyList<Message>()) }

    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var modelMenu by remember { mutableStateOf(false) }
    var newProject by remember { mutableStateOf(false) }
    var newChat by remember { mutableStateOf(false) }
    var pendingAuth by remember { mutableStateOf(api.pendingAuth) }
    var authBusy by remember { mutableStateOf(false) }

    var allowWrites by remember { mutableStateOf(false) }
    var allowTermux by remember { mutableStateOf(false) }
    var allowInternet by remember { mutableStateOf(true) }
    var allowBrowser by remember { mutableStateOf(false) }
    var backend by remember { mutableStateOf(api.baseUrl) }

    suspend fun refresh() {
        runCatching { models = api.models() }.onFailure { error = it.message }
        if (selectedModel.isBlank() && models.isNotEmpty()) {
            selectedModel = models.first().id
            api.setSelectedModel(selectedModel)
        }
        if (api.authToken.isNullOrBlank()) return
        user = api.savedLogin()
        runCatching { projects = api.projects() }.onFailure { error = it.message }
        runCatching { sessions = api.sessions() }.onFailure { error = it.message }
        runCatching { repos = api.repos() }.onFailure { error = it.message }
        if (current == null) current = sessions.firstOrNull()
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    LaunchedEffect(current?.id) {
        val id = current?.id ?: return@LaunchedEffect
        runCatching { messages = api.messages(id) }.onFailure { error = it.message }
    }

    LaunchedEffect(pendingAuth) {
        val requestId = pendingAuth ?: return@LaunchedEffect
        authBusy = true
        try {
            while (isActive && pendingAuth == requestId) {
                val status = runCatching { api.githubStatus(requestId) }.getOrNull()
                when (status?.optString("status")) {
                    "complete" -> {
                        val token = status.optString("token")
                        val u = status.optJSONObject("user")
                        val login = u?.optString("login").orEmpty()
                        if (token.isNotBlank() && login.isNotBlank()) {
                            api.saveAuth(token, login, u?.optString("name")?.takeIf { it.isNotBlank() })
                            user = login
                            api.setPendingAuth(null)
                            pendingAuth = null
                            error = null
                            refresh()
                        }
                    }
                    "failed", "expired", "consumed" -> {
                        error = status.optString("error").ifBlank { if (status.optString("status") == "expired") "GitHub login expired." else null }
                        api.setPendingAuth(null)
                        pendingAuth = null
                    }
                }
                delay(1200)
            }
        } finally {
            authBusy = false
        }
    }

    fun startGithubLogin() {
        authBusy = true
        scope.launch {
            runCatching {
                val (id, url) = api.startGithub()
                api.setPendingAuth(id)
                pendingAuth = id
                api.open(context, url)
            }.onFailure {
                authBusy = false
                error = it.message ?: "Unable to start GitHub login."
            }
        }
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = CardBg) {
                listOf(
                    Triple("Chat", Icons.Default.Chat, "Chat"),
                    Triple("Projects", Icons.Default.Folder, "Projects"),
                    Triple("Sessions", Icons.Default.Code, "Sessions"),
                    Triple("Settings", Icons.Default.Settings, "Settings")
                ).forEach { (name, icon, label) ->
                    NavigationBarItem(tab == name, { tab = name }, icon = { Icon(icon, label) }, label = { Text(label) })
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Solar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(user?.let { "@$it" } ?: "AI Agent & Coding Assistant", style = MaterialTheme.typography.labelSmall)
                }
                if (authBusy) CircularProgressIndicator(Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                else if (user == null) Button(onClick = { startGithubLogin() }) { Icon(Icons.Default.Code, null); Spacer(Modifier.width(6.dp)); Text("Connect") }
                else IconButton(onClick = { scope.launch { api.logout(); user = null; current = null; sessions = emptyList(); projects = emptyList() } }) { Icon(Icons.Default.Logout, "Logout") }
            }

            Spacer(Modifier.height(10.dp))

            when (tab) {
                "Chat" -> ChatTab(
                    models, selectedModel, { selectedModel = it; api.setSelectedModel(it) }, modelMenu, { modelMenu = it },
                    current, projects, messages, input, { input = it }, loading, user != null, { newChat = true },
                    { loading = true; val q = input.trim(); input = ""; error = null; scope.launch {
                        runCatching { api.runAgent(q, selectedModel, current!!.id, current!!.projectId, allowWrites, allowTermux, allowInternet, allowBrowser) }
                            .onSuccess { answer -> messages = messages + Message("local", "assistant", answer); sessions = runCatching { api.sessions() }.getOrDefault(sessions) }
                            .onFailure { error = it.message }
                        loading = false
                    }}
                )
                "Projects" -> ProjectsTab(
                    user != null, projects, repos, { scope.launch { runCatching { repos = api.repos() }.onFailure { error = it.message } } },
                    { newProject = true }, { repo -> scope.launch {
                        runCatching { api.createProject(repo.name, repo.description.orEmpty(), repo.owner, repo.name, "main") }
                            .onSuccess { projects = listOf(it) + projects }.onFailure { error = it.message }
                    }}, { p -> scope.launch { runCatching { api.deleteProject(p.id) }.onSuccess { projects = projects.filterNot { x -> x.id == p.id } }.onFailure { error = it.message } } }
                )
                "Sessions" -> SessionsTab(user != null, sessions, projects, { s -> current = s; tab = "Chat" }, { s -> scope.launch { runCatching { api.deleteSession(s.id) }.onSuccess { sessions = sessions.filterNot { x -> x.id == s.id }; if (current?.id == s.id) current = null }.onFailure { error = it.message } } })
                else -> SettingsTab(
                    user != null, backend, { backend = it }, { api.baseUrl = backend; error = null },
                    allowWrites, { allowWrites = it }, allowTermux, { allowTermux = it }, allowInternet, { allowInternet = it }, allowBrowser, { allowBrowser = it },
                    { startGithubLogin() }, { scope.launch { api.logout(); user = null; sessions = emptyList(); projects = emptyList() } }
                )
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }

    if (newProject) NewProjectDialog(
        { newProject = false },
        { name, desc, owner, repo, ref -> scope.launch { runCatching { api.createProject(name, desc, owner.ifBlank { null }, repo.ifBlank { null }, ref) }.onSuccess { projects = listOf(it) + projects; newProject = false }.onFailure { error = it.message } } }
    )
    if (newChat) NewChatDialog(models, projects, selectedModel, { newChat = false }) { project, model ->
        scope.launch { runCatching { api.createSession(project, model) }.onSuccess { current = it; sessions = listOf(it) + sessions; selectedModel = model; api.setSelectedModel(model); messages = emptyList(); newChat = false; tab = "Chat" }.onFailure { error = it.message } }
    }
}

@Composable
private fun ChatTab(
    models: List<Model>, selectedModel: String, onModel: (String) -> Unit, expanded: Boolean, onExpanded: (Boolean) -> Unit,
    current: Session?, projects: List<Project>, messages: List<Message>, input: String, onInput: (String) -> Unit,
    loading: Boolean, auth: Boolean, onNewChat: () -> Unit, onSend: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(current?.title ?: "No chat selected", fontWeight = FontWeight.Bold); Text("Model: " + (models.firstOrNull { it.id == selectedModel }?.name ?: "Select model"), style = MaterialTheme.typography.labelSmall) }
        Button(onClick = onNewChat, enabled = auth) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("New chat") }
    }
    Spacer(Modifier.height(6.dp))
    DropdownMenu(expanded, { onExpanded(false) }, modifier = Modifier.fillMaxWidth(0.92f)) {
        models.forEach { m -> DropdownMenuItem(text = { Column { Text(m.name); Text(m.desc, style = MaterialTheme.typography.labelSmall) } }, onClick = { onModel(m.id); onExpanded(false) }) }
    }
    OutlinedButton(onClick = { onExpanded(true) }, enabled = models.isNotEmpty()) { Text(models.firstOrNull { it.id == selectedModel }?.name ?: "Select model") }
    current?.let {
        Text("Project: " + (projects.firstOrNull { p -> p.id == it.projectId }?.name ?: "General"), style = MaterialTheme.typography.labelSmall)
    }
    Spacer(Modifier.height(6.dp))
    if (current == null) {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text("Create a chat session to start."); if (!auth) { Spacer(Modifier.height(6.dp)); Text("Connect GitHub first to enable saved sessions.") } } }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(messages) { m -> Surface(color = if (m.role == "user") Accent.copy(alpha = 0.12f) else CardBg, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(if (m.role == "user") "You" else "Solar", fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(m.text) } } }
        if (loading) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(18.dp).height(18.dp)); Spacer(Modifier.width(8.dp)); Text("Solar is working…") } }
    }
    Row(verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(input, onInput, Modifier.weight(1f), placeholder = { Text("Ask Solar to code, debug, explain…") }, maxLines = 5)
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick = onSend, enabled = auth && current != null && input.isNotBlank() && selectedModel.isNotBlank() && !loading) { Icon(Icons.Default.Send, "Send") }
    }
}

@Composable
private fun ProjectsTab(auth: Boolean, projects: List<Project>, repos: List<Repo>, refreshRepos: () -> Unit, newProject: () -> Unit, addRepo: (Repo) -> Unit, deleteProject: (Project) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Projects", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Persistent workspaces") }
        Button(onClick = newProject, enabled = auth) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("New") }
    }
    Spacer(Modifier.height(8.dp))
    if (!auth) { Text("Connect GitHub to create and sync projects."); return }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("GitHub repositories", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); IconButton(onClick = refreshRepos) { Icon(Icons.Default.Refresh, "Refresh") } }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(repos.take(40)) { r -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Code, null); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(r.fullName, fontWeight = FontWeight.SemiBold); Text(r.description.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 2) }; OutlinedButton(onClick = { addRepo(r) }) { Text("Add") } } } }
        item { Spacer(Modifier.height(4.dp)); Text("Your projects", fontWeight = FontWeight.Bold) }
        items(projects) { p -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold); Text(p.owner?.let { it + "/" + (p.repo ?: "") } ?: "No repository", style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = { deleteProject(p) }) { Text("×") } } } }
    }
}

@Composable
private fun SessionsTab(auth: Boolean, sessions: List<Session>, projects: List<Project>, open: (Session) -> Unit, delete: (Session) -> Unit) {
    Text("Chat sessions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Persistent conversations")
    Spacer(Modifier.height(8.dp))
    if (!auth) { Text("Connect GitHub to sync sessions."); return }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(sessions) { s -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(s.title, fontWeight = FontWeight.SemiBold); Text(projects.firstOrNull { p -> p.id == s.projectId }?.name ?: "General", style = MaterialTheme.typography.bodySmall); Text("Model: " + (s.modelId ?: "Auto"), style = MaterialTheme.typography.labelSmall) }; OutlinedButton(onClick = { open(s) }) { Text("Open") }; IconButton(onClick = { delete(s) }) { Text("×") } } } }
    }
}

@Composable
private fun SettingsTab(
    auth: Boolean, backend: String, onBackend: (String) -> Unit, save: () -> Unit,
    writes: Boolean, onWrites: (Boolean) -> Unit, termux: Boolean, onTermux: (Boolean) -> Unit,
    internet: Boolean, onInternet: (Boolean) -> Unit, browser: Boolean, onBrowser: (Boolean) -> Unit,
    connect: () -> Unit, logout: () -> Unit
) {
    Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("GitHub", fontWeight = FontWeight.Bold); Text(if (auth) "Connected" else "Not connected"); Spacer(Modifier.height(6.dp))
            if (auth) OutlinedButton(onClick = logout) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(4.dp)); Text("Disconnect") }
            else Button(onClick = connect) { Icon(Icons.Default.Code, null); Spacer(Modifier.width(4.dp)); Text("Connect GitHub") }
        }
    }
    Spacer(Modifier.height(8.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Backend", fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp))
            OutlinedTextField(backend, onBackend, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Solar backend URL") })
            Spacer(Modifier.height(6.dp)); Button(onClick = save) { Text("Save") }
        }
    }
    Spacer(Modifier.height(8.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Agent permissions", fontWeight = FontWeight.Bold)
            Permission("GitHub writes", writes, onWrites); Permission("Termux", termux, onTermux); Permission("Internet", internet, onInternet); Permission("Browser", browser, onBrowser)
        }
    }
}
@Composable private fun Permission(label: String, value: Boolean, set: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value, set) } }

@Composable
private fun NewProjectDialog(dismiss: () -> Unit, create: (String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }; var owner by remember { mutableStateOf("") }; var repo by remember { mutableStateOf("") }; var ref by remember { mutableStateOf("main") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Create project") }, text = { Column {
        OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true)
        OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, singleLine = true)
        OutlinedTextField(owner, { owner = it }, label = { Text("GitHub owner") }, singleLine = true)
        OutlinedTextField(repo, { repo = it }, label = { Text("Repository") }, singleLine = true)
        OutlinedTextField(ref, { ref = it }, label = { Text("Branch") }, singleLine = true)
    }}, confirmButton = { Button(enabled = name.isNotBlank(), onClick = { create(name.trim(), desc.trim(), owner.trim(), repo.trim(), ref.trim()) }) { Text("Create") } }, dismissButton = { OutlinedButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun NewChatDialog(models: List<Model>, projects: List<Project>, defaultModel: String, dismiss: () -> Unit, create: (String?, String) -> Unit) {
    var model by remember(defaultModel) { mutableStateOf(defaultModel) }; var project by remember { mutableStateOf<String?>(null) }; var modelOpen by remember { mutableStateOf(false) }; var projectOpen by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("New chat session") }, text = { Column {
        Text("Choose a model", fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp))
        BoxButton(models.firstOrNull { it.id == model }?.name ?: "Select model") { modelOpen = true }
        DropdownMenu(modelOpen, { modelOpen = false }) { models.forEach { m -> DropdownMenuItem(text = { Text(m.name) }, onClick = { model = m.id; modelOpen = false }) } }
        Spacer(Modifier.height(6.dp)); Text("Choose a project", fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp))
        BoxButton(projects.firstOrNull { it.id == project }?.name ?: "General / no project") { projectOpen = true }
        DropdownMenu(projectOpen, { projectOpen = false }) {
            DropdownMenuItem(text = { Text("General / no project") }, onClick = { project = null; projectOpen = false })
            projects.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { project = p.id; projectOpen = false }) }
        }
    }}, confirmButton = { Button(enabled = model.isNotBlank(), onClick = { create(project, model) }) { Text("Create") } }, dismissButton = { OutlinedButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable private fun BoxButton(text: String, onClick: () -> Unit) { OutlinedButton(onClick = onClick) { Text(text) } }
