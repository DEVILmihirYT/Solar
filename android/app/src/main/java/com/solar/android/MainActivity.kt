package com.solar.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private val SolarBg = Color(0xFF0B0F14)
private val SolarCard = Color(0xFF151B23)
private val SolarAccent = Color(0xFF8BFF6A)

data class Model(
    val id: String,
    val name: String,
    val desc: String,
    val provider: String
)

data class Project(
    val id: String,
    val name: String,
    val repoOwner: String?,
    val repoName: String?,
    val branch: String
)

data class ChatSession(
    val id: String,
    val title: String,
    val modelId: String?,
    val projectId: String?,
    val projectName: String?
)

data class Msg(
    val role: String,
    val text: String
)

data class GithubRepo(
    val owner: String,
    val name: String,
    val fullName: String,
    val defaultBranch: String
)

class SolarApi(context: Context) {
    private val prefs = context.getSharedPreferences("solar", Context.MODE_PRIVATE)
    private val client = OkHttpClient()

    var baseUrl: String
        get() = prefs.getString("base", "http://10.0.2.2:8080")!!.trimEnd('/')
        set(value) {
            prefs.edit().putString("base", value.trim().trimEnd('/')).apply()
        }

    private var authToken: String
        get() = prefs.getString("authToken", "")!!
        set(value) {
            prefs.edit().putString("authToken", value).apply()
        }

    private fun request(
        path: String,
        method: String = "GET",
        body: String? = null,
        auth: Boolean = true
    ): Request {
        val builder = Request.Builder().url(baseUrl + path)
        if (auth && authToken.isNotBlank()) {
            builder.header("Authorization", "Bearer " + authToken)
        }
        if (body != null) {
            builder.header("Content-Type", "application/json")
                .method(method, body.toRequestBody("application/json".toMediaType()))
        } else {
            builder.method(method, null)
        }
        return builder.build()
    }

    private suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception(extractError(body, "Request failed (" + response.code + ")."))
            }
            body
        }
    }

    suspend fun models(): List<Model> = runCatching {
        val body = call(request("/api/models", auth = false))
        val array = JSONObject(body).getJSONArray("models")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val capabilities = item.optJSONArray("capabilities")
            val caps = if (capabilities == null) "" else {
                (0 until capabilities.length()).joinToString(", ") { capabilities.optString(it) }
            }
            Model(
                id = item.optString("id"),
                name = item.optString("displayName", item.optString("name", item.optString("id"))),
                desc = item.optString("description").ifBlank { item.optString("provider") + " • " + caps },
                provider = item.optString("provider")
            )
        }
    }.getOrElse { fallbackModels() }

    suspend fun me(): Boolean = runCatching {
        call(request("/api/auth/me"))
        true
    }.getOrDefault(false)

    suspend fun projects(): List<Project> {
        val json = JSONObject(call(request("/api/projects")))
        val array = json.getJSONArray("projects")
        return List(array.length()) { i ->
            val p = array.getJSONObject(i)
            Project(
                id = p.optString("id"),
                name = p.optString("name"),
                repoOwner = p.optString("repo_owner").takeIf { it.isNotBlank() },
                repoName = p.optString("repo_name").takeIf { it.isNotBlank() },
                branch = p.optString("branch", "main")
            )
        }
    }

    suspend fun createProject(
        name: String,
        repoOwner: String?,
        repoName: String?,
        branch: String
    ): Project {
        val payload = JSONObject()
            .put("name", name)
            .put("repoOwner", repoOwner)
            .put("repoName", repoName)
            .put("branch", branch)
        val p = JSONObject(call(request("/api/projects", "POST", payload.toString())))
            .getJSONObject("project")
        return Project(
            id = p.optString("id"),
            name = p.optString("name"),
            repoOwner = p.optString("repo_owner").takeIf { it.isNotBlank() },
            repoName = p.optString("repo_name").takeIf { it.isNotBlank() },
            branch = p.optString("branch", "main")
        )
    }

    suspend fun deleteProject(id: String) {
        call(request("/api/projects/" + Uri.encode(id), "DELETE"))
    }

    suspend fun repositories(): List<GithubRepo> {
        val array = org.json.JSONArray(call(request("/api/github/repositories")))
        return List(array.length()) { i ->
            val repo = array.getJSONObject(i)
            val full = repo.optString("full_name")
            val parts = full.split("/", limit = 2)
            GithubRepo(
                owner = parts.getOrNull(0).orEmpty(),
                name = parts.getOrNull(1).orElse(repo.optString("name")),
                fullName = full,
                defaultBranch = repo.optString("default_branch", "main")
            )
        }
    }

    suspend fun sessions(): List<ChatSession> {
        val json = JSONObject(call(request("/api/sessions")))
        val array = json.getJSONArray("sessions")
        return List(array.length()) { i ->
            val s = array.getJSONObject(i)
            ChatSession(
                id = s.optString("id"),
                title = s.optString("title", "New chat"),
                modelId = s.optString("model_id").takeIf { it.isNotBlank() },
                projectId = s.optString("project_id").takeIf { it.isNotBlank() },
                projectName = s.optString("project_name").takeIf { it.isNotBlank() }
            )
        }
    }

    suspend fun createSession(title: String, projectId: String?, modelId: String?): ChatSession {
        val payload = JSONObject()
            .put("title", title)
            .put("projectId", projectId)
            .put("modelId", modelId)
        val s = JSONObject(call(request("/api/sessions", "POST", payload.toString())))
            .getJSONObject("session")
        return ChatSession(
            id = s.optString("id"),
            title = s.optString("title", title),
            modelId = s.optString("model_id").takeIf { it.isNotBlank() },
            projectId = s.optString("project_id").takeIf { it.isNotBlank() },
            projectName = null
        )
    }

    suspend fun deleteSession(id: String) {
        call(request("/api/sessions/" + Uri.encode(id), "DELETE"))
    }

    suspend fun messages(sessionId: String): List<Msg> {
        val json = JSONObject(call(request("/api/sessions/" + Uri.encode(sessionId) + "/messages")))
        val array = json.getJSONArray("messages")
        return List(array.length()) { i ->
            val m = array.getJSONObject(i)
            Msg(m.optString("role"), m.optString("content"))
        }
    }

    suspend fun sendMessage(
        sessionId: String,
        message: String,
        modelId: String,
        allowWrites: Boolean,
        allowTermux: Boolean,
        allowInternet: Boolean,
        allowBrowser: Boolean
    ): String {
        val payload = JSONObject()
            .put("message", message)
            .put("modelId", modelId)
            .put("allowWrites", allowWrites)
            .put("allowTermux", allowTermux)
            .put("allowInternet", allowInternet)
            .put("allowBrowser", allowBrowser)
            .put("allowFallback", true)
            .put("stream", false)
        val json = JSONObject(call(request(
            "/api/sessions/" + Uri.encode(sessionId) + "/messages",
            "POST",
            payload.toString()
        )))
        return json.optString(
            "message",
            json.optString("answer", json.optString("response", json.optString("output")))
        )
    }

    suspend fun exchangeOAuthCode(code: String) {
        val payload = JSONObject().put("code", code)
        val json = JSONObject(call(request("/api/auth/mobile/exchange", "POST", payload.toString(), auth = false)))
        authToken = json.optString("accessToken")
        if (authToken.isBlank()) throw Exception("GitHub login succeeded but no app token was returned.")
    }

    fun login(context: Context) {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, Uri.parse(baseUrl + "/api/auth/github?mobile=1"))
    }

    suspend fun logout() {
        runCatching { call(request("/api/auth/logout", "POST")) }
        authToken = ""
    }

    private fun extractError(body: String, fallback: String): String {
        return runCatching { JSONObject(body).optString("error") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: body.takeIf { it.isNotBlank() }
            ?: fallback
    }

    private fun fallbackModels(): List<Model> = listOf(
        Model("openrouter/free", "OpenRouter Free", "Free routing • chat/coding/reasoning", "openrouter"),
        Model("qwen/qwen3.5-397b-a17b", "Qwen 3.5 397B A17B", "General + coding + reasoning", "openrouter"),
        Model("qwen/qwen3-30b-a3b", "Qwen 3 30B A3B", "Fast general model", "openrouter"),
        Model("deepseek/deepseek-v3.2", "DeepSeek V3.2", "Coding + reasoning", "openrouter"),
        Model("z-ai/glm-5", "GLM 5", "General + reasoning", "openrouter"),
        Model("cognitivecomputations/dolphin3.0-r1-mistral-24b", "Dolphin 3.0 R1 Mistral 24B", "Community fine-tune", "openrouter"),
        Model("nousresearch/hermes-4-70b", "Hermes 4 70B", "Agentic general model", "openrouter")
    )
}

class MainActivity : ComponentActivity() {
    private lateinit var api: SolarApi
    private var authRevision by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = SolarApi(applicationContext)
        setContent {
            SolarTheme {
                SolarApp(this, api, authRevision)
            }
        }
        handleOAuthIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthIntent(intent)
    }

    private fun handleOAuthIntent(intent: Intent?) {
        val code = intent?.data?.getQueryParameter("code") ?: return
        intent.data = null
        lifecycleScope.launch {
            runCatching { api.exchangeOAuthCode(code) }
                .onSuccess { authRevision++ }
        }
    }
}

@Composable
fun SolarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            background = SolarBg,
            surface = SolarCard,
            primary = SolarAccent,
            onPrimary = Color.Black
        ),
        content = content
    )
}

@Composable
fun SolarApp(context: Context, api: SolarApi, authRevision: Int) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf("Chat") }
    var models by remember { mutableStateOf(emptyList<Model>()) }
    var selectedModel by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf(emptyList<Project>()) }
    var sessions by remember { mutableStateOf(emptyList<ChatSession>()) }
    var currentSession by remember { mutableStateOf<ChatSession?>(null) }
    var messages by remember { mutableStateOf(emptyList<Msg>()) }
    var input by remember { mutableStateOf("") }
    var authenticated by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var backendUrl by remember { mutableStateOf(api.baseUrl) }
    var allowWrites by remember { mutableStateOf(false) }
    var allowTermux by remember { mutableStateOf(false) }
    var allowInternet by remember { mutableStateOf(true) }
    var allowBrowser by remember { mutableStateOf(false) }
    var showNewSession by remember { mutableStateOf(false) }
    var showNewProject by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showProjectMenu by remember { mutableStateOf(false) }
    var repositories by remember { mutableStateOf(emptyList<GithubRepo>()) }
    var refreshingRepos by remember { mutableStateOf(false) }

    suspend fun reloadCore() {
        models = api.models()
        if (selectedModel.isBlank() || models.none { it.id == selectedModel }) {
            selectedModel = models.firstOrNull()?.id.orEmpty()
        }
        authenticated = api.me()
        if (authenticated) {
            runCatching { projects = api.projects() }.onFailure { projects = emptyList() }
            runCatching { sessions = api.sessions() }.onFailure { sessions = emptyList() }
            val currentId = currentSession?.id
            currentSession = sessions.firstOrNull { it.id == currentId } ?: currentSession?.takeIf { s -> sessions.any { it.id == s.id } }
            if (currentSession == null) messages = emptyList()
            if (currentSession != null) {
                selectedModel = currentSession?.modelId ?: selectedModel
                runCatching { messages = api.messages(currentSession!!.id) }
                    .onFailure { error = it.message }
            }
        } else {
            projects = emptyList()
            sessions = emptyList()
            currentSession = null
            messages = emptyList()
        }
    }

    LaunchedEffect(authRevision, api.baseUrl) {
        error = null
        runCatching { reloadCore() }
            .onFailure { error = it.message ?: "Unable to reach Solar backend." }
    }

    fun selectSession(session: ChatSession) {
        currentSession = session
        screen = "Chat"
        scope.launch {
            runCatching { messages = api.messages(session.id) }
                .onFailure { error = it.message }
        }
    }

    Scaffold(
        containerColor = SolarBg,
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = SolarCard
            ) {
                listOf(
                    Triple("Chat", Icons.Default.Chat, "Chat"),
                    Triple("Projects", Icons.Default.Folder, "Projects"),
                    Triple("Sessions", Icons.Default.History, "Sessions"),
                    Triple("Settings", Icons.Default.Settings, "Settings")
                ).forEach { (name, icon, label) ->
                    NavigationBarItem(
                        selected = screen == name,
                        onClick = { screen = name },
                        icon = { Icon(icon, label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            when (screen) {
                "Chat" -> ChatScreen(
                    api = api,
                    models = models,
                    selectedModel = selectedModel,
                    onSelectedModel = { selectedModel = it },
                    modelMenuExpanded = showModelMenu,
                    onModelMenuExpanded = { showModelMenu = it },
                    projects = projects,
                    currentSession = currentSession,
                    messages = messages,
                    input = input,
                    onInput = { input = it },
                    loading = loading,
                    authenticated = authenticated,
                    onLogin = { api.login(context) },
                    onNewSession = { showNewSession = true },
                    onSend = {
                        val session = currentSession
                        val question = input.trim()
                        if (session == null || question.isBlank() || selectedModel.isBlank()) return@ChatScreen
                        input = ""
                        messages = messages + Msg("user", question)
                        loading = true
                        error = null
                        scope.launch {
                            runCatching {
                                api.sendMessage(
                                    session.id,
                                    question,
                                    selectedModel,
                                    allowWrites,
                                    allowTermux,
                                    allowInternet,
                                    allowBrowser
                                )
                            }.onSuccess { answer ->
                                messages = messages + Msg("assistant", answer)
                                sessions = runCatching { api.sessions() }.getOrDefault(sessions)
                            }.onFailure {
                                error = it.message ?: "Solar request failed."
                            }
                            loading = false
                        }
                    }
                )

                "Projects" -> ProjectsScreen(
                    projects = projects,
                    authenticated = authenticated,
                    repositories = repositories,
                    refreshingRepos = refreshingRepos,
                    onLogin = { api.login(context) },
                    onRefreshRepos = {
                        refreshingRepos = true
                        scope.launch {
                            runCatching { repositories = api.repositories() }
                                .onFailure { error = it.message }
                            refreshingRepos = false
                        }
                    },
                    onCreateManual = { showNewProject = true },
                    onCreateFromRepo = { repo ->
                        scope.launch {
                            runCatching {
                                api.createProject(repo.name, repo.owner, repo.name, repo.defaultBranch)
                            }.onSuccess { project ->
                                projects = listOf(project) + projects
                            }.onFailure { error = it.message }
                        }
                    },
                    onDelete = { project ->
                        scope.launch {
                            runCatching { api.deleteProject(project.id) }
                                .onSuccess { projects = projects.filterNot { it.id == project.id } }
                                .onFailure { error = it.message }
                        }
                    }
                )

                "Sessions" -> SessionsScreen(
                    sessions = sessions,
                    authenticated = authenticated,
                    onLogin = { api.login(context) },
                    onSelect = ::selectSession,
                    onDelete = { session ->
                        scope.launch {
                            runCatching { api.deleteSession(session.id) }
                                .onSuccess {
                                    sessions = sessions.filterNot { it.id == session.id }
                                    if (currentSession?.id == session.id) {
                                        currentSession = null
                                        messages = emptyList()
                                    }
                                }
                                .onFailure { error = it.message }
                        }
                    }
                )

                else -> SettingsScreen(
                    authenticated = authenticated,
                    backendUrl = backendUrl,
                    onBackendUrl = { backendUrl = it },
                    onSaveBackend = {
                        api.baseUrl = backendUrl
                        error = null
                    },
                    onLogin = { api.login(context) },
                    onLogout = {
                        scope.launch {
                            api.logout()
                            authRevision++
                        }
                    },
                    allowWrites = allowWrites,
                    onAllowWrites = { allowWrites = it },
                    allowTermux = allowTermux,
                    onAllowTermux = { allowTermux = it },
                    allowInternet = allowInternet,
                    onAllowInternet = { allowInternet = it },
                    allowBrowser = allowBrowser,
                    onAllowBrowser = { allowBrowser = it }
                )
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showNewSession) {
        NewSessionDialog(
            models = models,
            projects = projects,
            defaultModel = selectedModel,
            onDismiss = { showNewSession = false },
            onCreate = { title, projectId, modelId ->
                scope.launch {
                    runCatching { api.createSession(title, projectId, modelId) }
                        .onSuccess {
                            sessions = listOf(it) + sessions
                            currentSession = it
                            selectedModel = modelId
                            messages = emptyList()
                            screen = "Chat"
                            showNewSession = false
                        }.onFailure { error = it.message }
                }
            }
        )
    }

    if (showNewProject) {
        NewProjectDialog(
            onDismiss = { showNewProject = false },
            onCreate = { name, owner, repo, branch ->
                scope.launch {
                    runCatching { api.createProject(name, owner.ifBlank { null }, repo.ifBlank { null }, branch.ifBlank { "main" }) }
                        .onSuccess {
                            projects = listOf(it) + projects
                            showNewProject = false
                        }.onFailure { error = it.message }
                }
            }
        )
    }
}

@Composable
private fun ChatScreen(
    api: SolarApi,
    models: List<Model>,
    selectedModel: String,
    onSelectedModel: (String) -> Unit,
    modelMenuExpanded: Boolean,
    onModelMenuExpanded: (Boolean) -> Unit,
    projects: List<Project>,
    currentSession: ChatSession?,
    messages: List<Msg>,
    input: String,
    onInput: (String) -> Unit,
    loading: Boolean,
    authenticated: Boolean,
    onLogin: () -> Unit,
    onNewSession: () -> Unit,
    onSend: () -> Unit
) {
    Text("Solar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        currentSession?.title ?: "No chat selected",
        style = MaterialTheme.typography.titleMedium
    )

    Spacer(Modifier.height(10.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            OutlinedButton(onClick = { onModelMenuExpanded(true) }) {
                Icon(Icons.Default.Sync, null)
                Spacer(Modifier.width(6.dp))
                Text(
                    models.firstOrNull { it.id == selectedModel }?.name ?: "Select model"
                )
            }
            DropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { onModelMenuExpanded(false) }
            ) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(model.name)
                                Text(model.desc, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        onClick = {
                            onSelectedModel(model.id)
                            onModelMenuExpanded(false)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        Button(onClick = onNewSession, enabled = authenticated) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("New chat")
        }
    }

    if (currentSession != null) {
        Spacer(Modifier.height(6.dp))
        val project = projects.firstOrNull { it.id == currentSession.projectId }
        Text(
            "Project: " + (project?.name ?: currentSession.projectName ?: "General"),
            style = MaterialTheme.typography.labelLarge
        )
    } else {
        Spacer(Modifier.height(6.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Chat, null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Start a persistent chat")
                    Text(
                        if (authenticated) "Create a session to keep history synced with Solar."
                        else "Connect GitHub to enable persistent sessions and project workspaces.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!authenticated) {
                    OutlinedButton(onClick = onLogin) { Text("Connect") }
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages) { message ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (message.role == "user") SolarAccent.copy(alpha = 0.13f) else SolarCard
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (message.role == "user") "You" else "Solar",
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(message.text)
                }
            }
        }
        if (loading) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Solar is working...")
                }
            }
        }
    }

    Row(verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask Solar to code, debug, explain...") },
            maxLines = 6
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            enabled = authenticated && currentSession != null && input.isNotBlank() && selectedModel.isNotBlank() && !loading,
            onClick = onSend
        ) {
            Icon(Icons.Default.Send, "Send")
        }
    }
}

@Composable
private fun ProjectsScreen(
    projects: List<Project>,
    authenticated: Boolean,
    repositories: List<GithubRepo>,
    refreshingRepos: Boolean,
    onLogin: () -> Unit,
    onRefreshRepos: () -> Unit,
    onCreateManual: () -> Unit,
    onCreateFromRepo: (GithubRepo) -> Unit,
    onDelete: (Project) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Projects", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Persistent workspaces for Solar")
        }
        Button(onClick = onCreateManual, enabled = authenticated) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("New")
        }
    }

    Spacer(Modifier.height(10.dp))

    if (!authenticated) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("GitHub is not connected", fontWeight = FontWeight.Bold)
                Text("Connect once and your projects and sessions will sync to the backend.")
                Spacer(Modifier.height(10.dp))
                Button(onClick = onLogin) { Text("Connect GitHub") }
            }
        }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("GitHub repositories", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconButton(onClick = onRefreshRepos, enabled = !refreshingRepos) {
            Icon(if (refreshingRepos) Icons.Default.Sync else Icons.Default.Refresh, "Refresh")
        }
    }

    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(repositories.take(30)) { repo ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Cloud, null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(repo.fullName, fontWeight = FontWeight.SemiBold)
                        Text("Branch: " + repo.defaultBranch, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { onCreateFromRepo(repo) }) { Text("Add") }
                }
            }
        }

        item {
            Text("Your projects", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }

        items(projects) { project ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(project.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            project.repoOwner?.let { owner ->
                                (owner + "/" + (project.repoName ?: "")) + " • " + project.branch
                            } ?: "No GitHub repository linked",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = { onDelete(project) }) {
                        Icon(Icons.Default.Delete, "Delete project")
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionsScreen(
    sessions: List<ChatSession>,
    authenticated: Boolean,
    onLogin: () -> Unit,
    onSelect: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit
) {
    Text("Chat sessions", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("Persistent conversations")

    Spacer(Modifier.height(10.dp))

    if (!authenticated) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Connect GitHub to sync sessions.")
                Spacer(Modifier.height(10.dp))
                Button(onClick = onLogin) { Text("Connect GitHub") }
            }
        }
        return
    }

    if (sessions.isEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("No sessions yet")
                Text("Create a new chat from the Chat tab.")
            }
        }
    }

    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(sessions) { session ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(session.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            (session.projectName ?: "General") + " • " + (session.modelId ?: "Auto"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(onClick = { onSelect(session) }) { Text("Open") }
                    IconButton(onClick = { onDelete(session) }) {
                        Icon(Icons.Default.Delete, "Delete session")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    authenticated: Boolean,
    backendUrl: String,
    onBackendUrl: (String) -> Unit,
    onSaveBackend: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    allowWrites: Boolean,
    onAllowWrites: (Boolean) -> Unit,
    allowTermux: Boolean,
    onAllowTermux: (Boolean) -> Unit,
    allowInternet: Boolean,
    onAllowInternet: (Boolean) -> Unit,
    allowBrowser: Boolean,
    onAllowBrowser: (Boolean) -> Unit
) {
    Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Connection", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = backendUrl,
                onValueChange = onBackendUrl,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Solar backend URL") }
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Wifi, null)
                Spacer(Modifier.width(8.dp))
                Text("Default Android emulator URL: http://10.0.2.2:8080")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSaveBackend) { Text("Save backend URL") }
        }
    }

    Spacer(Modifier.height(10.dp))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("GitHub account", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (authenticated) {
                Text("Connected")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onLogout) { Text("Disconnect") }
            } else {
                Text("Not connected")
                Spacer(Modifier.height(8.dp))
                Button(onClick = onLogin) { Text("Connect GitHub") }
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Agent permissions", fontWeight = FontWeight.Bold)
            PermissionRow("Allow GitHub writes", allowWrites, onAllowWrites)
            PermissionRow("Allow Termux", allowTermux, onAllowTermux)
            PermissionRow("Allow internet", allowInternet, onAllowInternet)
            PermissionRow("Allow browser", allowBrowser, onAllowBrowser)
        }
    }
}

@Composable
private fun PermissionRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f))
        Switch(checked, onCheckedChange)
    }
}

@Composable
private fun NewSessionDialog(
    models: List<Model>,
    projects: List<Project>,
    defaultModel: String,
    onDismiss: () -> Unit,
    onCreate: (String, String?, String) -> Unit
) {
    var title by remember { mutableStateOf("New chat") }
    var model by remember(defaultModel) { mutableStateOf(defaultModel) }
    var projectId by remember { mutableStateOf<String?>(null) }
    var modelExpanded by remember { mutableStateOf(false) }
    var projectExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create chat session") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Session name") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { modelExpanded = true }) {
                        Text(models.firstOrNull { it.id == model }?.name ?: "Select model")
                    }
                    DropdownMenu(modelExpanded, { modelExpanded = false }) {
                        models.forEach {
                            DropdownMenuItem(
                                text = { Text(it.name) },
                                onClick = {
                                    model = it.id
                                    modelExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box {
                    OutlinedButton(onClick = { projectExpanded = true }) {
                        Text(projects.firstOrNull { it.id == projectId }?.name ?: "General / no project")
                    }
                    DropdownMenu(projectExpanded, { projectExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("General / no project") },
                            onClick = {
                                projectId = null
                                projectExpanded = false
                            }
                        )
                        projects.forEach {
                            DropdownMenuItem(
                                text = { Text(it.name) },
                                onClick = {
                                    projectId = it.id
                                    projectExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && model.isNotBlank(),
                onClick = { onCreate(title.trim(), projectId, model) }
            ) { Text("Create") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create project") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(owner, { owner = it }, label = { Text("GitHub owner (optional)") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(repo, { repo = it }, label = { Text("GitHub repository (optional)") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(branch, { branch = it }, label = { Text("Branch") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), owner.trim(), repo.trim(), branch.trim()) }) {
                Text("Create")
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
