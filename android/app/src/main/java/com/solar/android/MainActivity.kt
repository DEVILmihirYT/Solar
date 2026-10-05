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
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private val SolarBg = Color(0xFF0B0F14)
private val SolarCard = Color(0xFF151B23)
private val SolarAccent = Color(0xFF8BFF6A)

data class Model(
    val id: String,
    val name: String,
    val desc: String,
    val provider: String,
    val capabilities: List<String>
)

data class Msg(
    val id: String,
    val role: String,
    val text: String,
    val modelId: String?
)

data class Project(
    val id: String,
    val name: String,
    val description: String,
    val githubOwner: String?,
    val githubRepo: String?,
    val githubRef: String
)

data class ChatSession(
    val id: String,
    val title: String,
    val projectId: String?,
    val modelId: String?
)

data class Repo(
    val fullName: String,
    val owner: String,
    val name: String,
    val description: String?
)

data class User(
    val id: String,
    val login: String,
    val name: String?
)

data class LoginStart(
    val requestId: String,
    val authorizationUrl: String
)

class SolarApi(context: Context) {
    private val prefs = context.getSharedPreferences("solar", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base", "http://10.0.2.2:8080")!!.trimEnd('/')
        set(value) {
            prefs.edit().putString("base", value.trim().trimEnd('/')).apply()
        }

    val authToken: String?
        get() = prefs.getString("auth_token", null)

    val pendingAuthId: String?
        get() = prefs.getString("pending_auth", null)

    private val cookies = object : CookieJar {
        private val map = mutableMapOf<String, List<Cookie>>()
        override fun loadForRequest(url: HttpUrl): List<Cookie> = map[url.host].orEmpty()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            map[url.host] = cookies
        }
    }

    private val client = OkHttpClient.Builder().cookieJar(cookies).build()

    fun setPendingAuth(id: String?) {
        prefs.edit().apply {
            if (id == null) remove("pending_auth") else putString("pending_auth", id)
        }.apply()
    }

    fun saveAuth(token: String, user: User) {
        prefs.edit()
            .putString("auth_token", token)
            .putString("github_login", user.login)
            .putString("github_name", user.name)
            .apply()
    }

    fun clearAuth() {
        prefs.edit()
            .remove("auth_token")
            .remove("github_login")
            .remove("github_name")
            .remove("pending_auth")
            .apply()
    }

    fun savedUser(): User? {
        val token = authToken ?: return null
        val login = prefs.getString("github_login", null) ?: return null
        return User(id = "", login = login, name = prefs.getString("github_name", null))
    }

    fun selectedModelId(): String = prefs.getString("selected_model", "") ?: ""

    fun setSelectedModelId(id: String) {
        prefs.edit().putString("selected_model", id).apply()
    }

    private fun builder(path: String, method: String = "GET", body: String? = null): Request {
        val b = Request.Builder().url(baseUrl + path)
        if (!authToken.isNullOrBlank()) b.header("Authorization", "Bearer " + authToken)
        if (body != null) {
            b.header("Content-Type", "application/json")
            b.method(method, body.toRequestBody("application/json".toMediaType()))
        } else if (method != "GET") {
            b.method(method, null)
        }
        return b.build()
    }

    private fun body(response: okhttp3.Response, fallback: String): String {
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw Exception(extractError(text, fallback))
        }
        return text
    }

    suspend fun models(): List<Model> = withContext(Dispatchers.IO) {
        client.newCall(builder("/api/models")).execute().use { response ->
            val json = JSONObject(body(response, "Unable to load models."))
            val array = json.getJSONArray("models")
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                Model(
                    id = item.optString("id"),
                    name = item.optString("displayName", item.optString("id")),
                    desc = item.optString("description").ifBlank { item.optString("provider") },
                    provider = item.optString("provider"),
                    capabilities = jsonStringList(item.optJSONArray("capabilities"))
                )
            }
        }
    }

    suspend fun projects(): List<Project> = withContext(Dispatchers.IO) {
        client.newCall(builder("/api/projects")).execute().use { response ->
            val array = JSONObject(body(response, "Unable to load projects.")).getJSONArray("projects")
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                Project(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    description = item.optString("description"),
                    githubOwner = item.optString("github_owner").ifBlank { null },
                    githubRepo = item.optString("github_repo").ifBlank { null },
                    githubRef = item.optString("github_ref", "main")
                )
            }
        }
    }

    suspend fun createProject(name: String, description: String, owner: String?, repo: String?, ref: String): Project =
        withContext(Dispatchers.IO) {
            val json = JSONObject()
                .put("name", name)
                .put("description", description)
                .put("githubOwner", owner)
                .put("githubRepo", repo)
                .put("githubRef", ref.ifBlank { "main" })
            client.newCall(builder("/api/projects", "POST", json.toString())).execute().use { response ->
                val item = JSONObject(body(response, "Unable to create project."))
                Project(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    description = item.optString("description"),
                    githubOwner = item.optString("github_owner").ifBlank { null },
                    githubRepo = item.optString("github_repo").ifBlank { null },
                    githubRef = item.optString("github_ref", "main")
                )
            }
        }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        client.newCall(builder("/api/projects/" + id, "DELETE")).execute().use { response ->
            body(response, "Unable to delete project.")
        }
    }

    suspend fun sessions(projectId: String? = null): List<ChatSession> = withContext(Dispatchers.IO) {
        val suffix = projectId?.let { "?projectId=" + Uri.encode(it) } ?: ""
        client.newCall(builder("/api/chat/sessions" + suffix)).execute().use { response ->
            val array = JSONObject(body(response, "Unable to load chats.")).getJSONArray("sessions")
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                ChatSession(
                    id = item.optString("id"),
                    title = item.optString("title", "New chat"),
                    projectId = item.optString("project_id").ifBlank { null },
                    modelId = item.optString("model_id").ifBlank { null }
                )
            }
        }
    }

    suspend fun createSession(projectId: String?, modelId: String?): ChatSession = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("title", "New chat")
            .put("projectId", projectId)
            .put("modelId", modelId)
        client.newCall(builder("/api/chat/sessions", "POST", json.toString())).execute().use { response ->
            val item = JSONObject(body(response, "Unable to create chat."))
            ChatSession(
                id = item.optString("id"),
                title = item.optString("title", "New chat"),
                projectId = item.optString("project_id").ifBlank { null },
                modelId = item.optString("model_id").ifBlank { null }
            )
        }
    }

    suspend fun updateSession(id: String, title: String? = null, modelId: String? = null, projectId: String? = null): ChatSession = withContext(Dispatchers.IO) {
        val json = JSONObject()
        title?.let { json.put("title", it) }
        modelId?.let { json.put("modelId", it) }
        projectId?.let { json.put("projectId", it) }
        client.newCall(builder("/api/chat/sessions/" + id, "PATCH", json.toString())).execute().use { response ->
            val item = JSONObject(body(response, "Unable to update chat."))
            ChatSession(
                id = item.optString("id"),
                title = item.optString("title", "New chat"),
                projectId = item.optString("project_id").ifBlank { null },
                modelId = item.optString("model_id").ifBlank { null }
            )
        }
    }

    suspend fun health() = withContext(Dispatchers.IO) {
        client.newCall(builder("/api/health")).execute().use { response ->
            body(response, "Backend is not reachable.")
        }
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        client.newCall(builder("/api/chat/sessions/" + id, "DELETE")).execute().use { response ->
            body(response, "Unable to delete chat.")
        }
    }

    suspend fun messages(sessionId: String): List<Msg> = withContext(Dispatchers.IO) {
        client.newCall(builder("/api/chat/sessions/" + sessionId + "/messages")).execute().use { response ->
            val array = JSONObject(body(response, "Unable to load chat messages.")).getJSONArray("messages")
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                Msg(
                    id = item.optString("id"),
                    role = item.optString("role"),
                    text = item.optString("content"),
                    modelId = item.optString("model_id").ifBlank { null }
                )
            }
        }
    }

    suspend fun repositories(): List<Repo> = withContext(Dispatchers.IO) {
        client.newCall(builder("/api/github/repositories")).execute().use { response ->
            val array = JSONArray(body(response, "Unable to load GitHub repositories."))
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                Repo(
                    fullName = item.optString("full_name"),
                    owner = item.optJSONObject("owner")?.optString("login").orEmpty(),
                    name = item.optString("name"),
                    description = item.optString("description").ifBlank { null }
                )
            }
        }
    }

    suspend fun startGithubMobile(): LoginStart = withContext(Dispatchers.IO) {
        client.newCall(builder("/api/auth/github/mobile/start", "POST")).execute().use { response ->
            val json = JSONObject(body(response, "Unable to start GitHub login."))
            LoginStart(json.optString("requestId"), json.optString("authorizationUrl"))
        }
    }

    suspend fun githubMobileStatus(requestId: String): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(builder("/api/auth/github/mobile/status?requestId=" + Uri.encode(requestId))).execute().use { response ->
            JSONObject(body(response, "Unable to check GitHub login."))
        }
    }

    suspend fun me(): User? = withContext(Dispatchers.IO) {
        if (authToken.isNullOrBlank()) return@withContext null
        client.newCall(builder("/api/auth/me")).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val item = JSONObject(response.body?.string().orEmpty())
            User(item.optString("id"), item.optString("login"), item.optString("name").ifBlank { null })
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(builder("/api/auth/logout", "POST")).execute().use { response ->
                response.close()
            }
        }
        clearAuth()
    }

    suspend fun run(
        message: String,
        model: String,
        sessionId: String,
        projectId: String?,
        flags: Map<String, Boolean>
    ): String = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("message", message)
            .put("modelId", model)
            .put("sessionId", sessionId)
            .put("projectId", projectId)
            .put("stream", false)
        flags.forEach { (key, value) -> payload.put(key, value) }
        client.newCall(builder("/api/agent/run", "POST", payload.toString())).execute().use { response ->
            val json = JSONObject(body(response, "Solar request failed."))
            json.optString("message", json.optString("answer", "No response returned."))
        }
    }

    fun openExternal(context: Context, url: String) {
        val uri = Uri.parse(url)
        runCatching {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
        }.recoverCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.getOrThrow()
    }

    private fun jsonStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return List(array.length()) { i -> array.optString(i) }
    }

    private fun extractError(body: String, fallback: String): String {
        return runCatching { JSONObject(body).optString("error") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: body.takeIf { it.isNotBlank() }
            ?: fallback
    }
}

@Composable
fun SolarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = SolarBg,
            surface = SolarCard,
            primary = SolarAccent,
            onPrimary = Color.Black
        ),
        content = content
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SolarTheme { SolarApp(this) } }
    }
}

@Composable
fun SolarApp(context: Context) {
    val api = remember { SolarApi(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf("Chats") }
    var user by remember { mutableStateOf(api.savedUser()) }
    var models by remember { mutableStateOf(emptyList<Model>()) }
    var selectedModel by remember { mutableStateOf(api.selectedModelId()) }
    var projects by remember { mutableStateOf(emptyList<Project>()) }
    var sessions by remember { mutableStateOf(emptyList<ChatSession>()) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf(emptyList<Msg>()) }
    var repos by remember { mutableStateOf(emptyList<Repo>()) }

    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var authBusy by remember { mutableStateOf(false) }
    var pendingAuthId by remember { mutableStateOf(api.pendingAuthId) }
    var error by remember { mutableStateOf<String?>(null) }

    var allowWrites by remember { mutableStateOf(false) }
    var allowTermux by remember { mutableStateOf(false) }
    var allowInternet by remember { mutableStateOf(true) }
    var allowBrowser by remember { mutableStateOf(false) }

    var showProjectDialog by remember { mutableStateOf(false) }
    var showNewChatDialog by remember { mutableStateOf(false) }
    var showDeleteSessionDialog by remember { mutableStateOf<ChatSession?>(null) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showProjectMenu by remember { mutableStateOf(false) }
    var showSessionMenu by remember { mutableStateOf(false) }
    var newChatProjectId by remember { mutableStateOf<String?>(null) }
    var newChatModelId by remember { mutableStateOf(selectedModel) }
    var backendDraft by remember { mutableStateOf(api.baseUrl) }

    suspend fun refreshWorkspace() {
        if (api.authToken.isNullOrBlank()) {
            sessions = emptyList()
            projects = emptyList()
            repos = emptyList()
            return
        }
        runCatching { api.me() }.onSuccess { me ->
            user = me
        }
        runCatching { api.models() }.onSuccess { loaded ->
            models = loaded
            val current = loaded.firstOrNull { it.id == selectedModel } ?: loaded.firstOrNull()
            if (current != null) {
                selectedModel = current.id
                api.setSelectedModelId(current.id)
            }
        }
        runCatching { api.projects() }.onSuccess { projects = it }
        runCatching { api.sessions() }.onSuccess { loaded ->
            sessions = loaded
            if (selectedSessionId == null && loaded.isNotEmpty()) selectedSessionId = loaded.first().id
            val active = loaded.firstOrNull { it.id == selectedSessionId }
            active?.modelId?.takeIf { it.isNotBlank() }?.let {
                selectedModel = it
                api.setSelectedModelId(it)
            }
        }
        runCatching { api.repositories() }.onSuccess { repos = it }
    }

    LaunchedEffect(Unit) {
        runCatching { api.models() }
            .onSuccess { loaded ->
                models = loaded
                if (selectedModel.isBlank()) {
                    selectedModel = loaded.firstOrNull()?.id.orEmpty()
                    if (selectedModel.isNotBlank()) api.setSelectedModelId(selectedModel)
                }
            }
            .onFailure { error = it.message }
        refreshWorkspace()
    }

    LaunchedEffect(pendingAuthId) {
        val requestId = pendingAuthId ?: return@LaunchedEffect
        authBusy = true
        try {
            while (isActive && pendingAuthId == requestId) {
                val result = runCatching { api.githubMobileStatus(requestId) }.getOrNull()
                when (result?.optString("status")) {
                    "complete" -> {
                        val token = result.optString("token")
                        val item = result.optJSONObject("user")
                        val loggedInUser = User(
                            id = item?.optString("id").orEmpty(),
                            login = item?.optString("login").orEmpty(),
                            name = item?.optString("name")?.takeIf { it.isNotBlank() }
                        )
                        if (token.isNotBlank() && loggedInUser.login.isNotBlank()) {
                            api.saveAuth(token, loggedInUser)
                            user = loggedInUser
                            api.setPendingAuth(null)
                            pendingAuthId = null
                            error = null
                            refreshWorkspace()
                        } else {
                            error = "GitHub login completed without a valid session."
                            api.setPendingAuth(null)
                            pendingAuthId = null
                        }
                    }
                    "failed", "expired" -> {
                        error = result?.optString("error").takeIf { !it.isNullOrBlank() } ?: "GitHub login expired."
                        api.setPendingAuth(null)
                        pendingAuthId = null
                    }
                    "consumed" -> {
                        api.setPendingAuth(null)
                        pendingAuthId = null
                    }
                }
                delay(1500)
            }
        } finally {
            authBusy = false
        }
    }

    LaunchedEffect(selectedSessionId) {
        val id = selectedSessionId ?: return@LaunchedEffect
        if (!api.authToken.isNullOrBlank()) {
            runCatching { api.messages(id) }
                .onSuccess { messages = it }
                .onFailure { error = it.message }
        }
    }

    fun sendMessage() {
        val text = input.trim()
        val currentSession = sessions.firstOrNull { it.id == selectedSessionId }
        if (text.isBlank() || loading) return
        scope.launch {
            var session = currentSession
            if (session == null) {
                if (api.authToken.isNullOrBlank()) {
                    error = "Connect GitHub before sending a synced chat."
                    screen = "Settings"
                    return@launch
                }
                session = runCatching {
                    api.createSession(null, selectedModel.ifBlank { null })
                }.getOrElse {
                    error = it.message
                    return@launch
                }
                sessions = listOf(session!!) + sessions
                selectedSessionId = session!!.id
                messages = emptyList()
            }
            val projectId = session!!.projectId
            val displayModel = session!!.modelId?.takeIf { it.isNotBlank() }
            ?: selectedModel.ifBlank { models.firstOrNull()?.id.orEmpty() }
            if (displayModel.isBlank()) {
                error = "No AI model is available."
                screen = "Models"
                return@launch
            }
            input = ""
            messages = messages + Msg("local-user-" + System.nanoTime(), "user", text, displayModel)
            loading = true
            error = null
            try {
                val answer = api.run(
                    message = text,
                    model = displayModel,
                    sessionId = session!!.id,
                    projectId = projectId,
                    flags = mapOf(
                        "allowWrites" to allowWrites,
                        "allowTermux" to allowTermux,
                        "allowInternet" to allowInternet,
                        "allowBrowser" to allowBrowser,
                        "allowFallback" to true
                    )
                )
                messages = messages + Msg("local-assistant-" + System.nanoTime(), "assistant", answer, displayModel)
                runCatching { api.sessions() }.onSuccess { sessions = it }
            } catch (e: Exception) {
                error = e.message ?: "Solar request failed."
            } finally {
                loading = false
            }
        }
    }

    fun startGithubLogin() {
        scope.launch {
            if (authBusy) return@launch
            authBusy = true
            runCatching { api.startGithubMobile() }
                .onSuccess {
                    api.setPendingAuth(it.requestId)
                    pendingAuthId = it.requestId
                    api.openExternal(context, it.authorizationUrl)
                    error = null
                }
                .onFailure { error = it.message ?: "Unable to start GitHub login." }
            authBusy = pendingAuthId != null
        }
    }

    Scaffold(
        containerColor = SolarBg,
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = SolarCard
            ) {
                val nav = listOf(
                    "Chats" to Icons.Default.Chat,
                    "Projects" to Icons.Default.Folder,
                    "Models" to Icons.Default.Code,
                    "Settings" to Icons.Default.Settings
                )
                nav.forEach { (name, icon) ->
                    NavigationBarItem(
                        selected = screen == name,
                        onClick = { screen = name },
                        icon = { Icon(icon, contentDescription = name) },
                        label = { Text(name) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Solar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (user != null) "@" + user!!.login else "AI Agent & Coding Assistant",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (authBusy) {
                    CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
                } else if (user == null) {
                    Button(onClick = { startGithubLogin() }) {
                        Icon(Icons.Default.Code, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Connect")
                    }
                } else {
                    IconButton(onClick = {
                        scope.launch {
                            api.logout()
                            user = null
                            sessions = emptyList()
                            projects = emptyList()
                            repos = emptyList()
                            selectedSessionId = null
                            messages = emptyList()
                        }
                    }) {
                        Icon(Icons.Default.Logout, "Logout")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            when (screen) {
                "Chats" -> {
                    val currentSession = sessions.firstOrNull { it.id == selectedSessionId }
                    val currentProject = projects.firstOrNull { it.id == currentSession?.projectId }
                    val activeModel = models.firstOrNull { it.id == (currentSession?.modelId ?: selectedModel) }

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Chats", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    if (currentSession == null) "Start a new Solar session" else currentSession.title,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Button(onClick = {
                                if (api.authToken.isNullOrBlank()) {
                                    screen = "Settings"
                                    error = "Connect GitHub before creating synced chats."
                                } else {
                                    newChatProjectId = currentProject?.id
                                    newChatModelId = activeModel?.id ?: selectedModel
                                    showNewChatDialog = true
                                }
                            }) {
                                Icon(Icons.Default.Add, null)
                                Spacer(Modifier.width(4.dp))
                                Text("New chat")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { showModelMenu = true }
                                ) {
                                    Text(activeModel?.name ?: "Choose model", maxLines = 1)
                                }
                                DropdownMenu(
                                    expanded = showModelMenu,
                                    onDismissRequest = { showModelMenu = false }
                                ) {
                                    models.forEach { model ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(model.name, fontWeight = if (model.id == activeModel?.id) FontWeight.Bold else FontWeight.Normal)
                                                    Text(
                                                        model.provider,
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            },
                                            onClick = {
                                                selectedModel = model.id
                                                api.setSelectedModelId(model.id)
                                                showModelMenu = false
                                                val sid = selectedSessionId
                                                if (sid != null) {
                                                    scope.launch {
                                                        runCatching { api.updateSession(sid, modelId = model.id) }
                                                            .onSuccess { updated ->
                                                                sessions = sessions.map { if (it.id == updated.id) updated else it }
                                                                error = null
                                                            }
                                                            .onFailure { error = it.message }
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            Box(Modifier.weight(1f)) {
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { showProjectMenu = true }
                                ) {
                                    Text(currentProject?.name ?: "No project", maxLines = 1)
                                }
                                DropdownMenu(
                                    expanded = showProjectMenu,
                                    onDismissRequest = { showProjectMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("No project") },
                                        onClick = {
                                            showProjectMenu = false
                                            selectedSessionId?.let { sid ->
                                                scope.launch {
                                                    runCatching { api.updateSession(sid, projectId = "") }
                                                }
                                            }
                                        }
                                    )
                                    projects.forEach { project ->
                                        DropdownMenuItem(
                                            text = { Text(project.name) },
                                            onClick = {
                                                showProjectMenu = false
                                                selectedSessionId?.let { sid ->
                                                    scope.launch {
                                                        runCatching {
                                                            api.updateSession(sid, projectId = project.id)
                                                        }.onSuccess { updated ->
                                                            sessions = sessions.map { if (it.id == updated.id) updated else it }
                                                            error = null
                                                        }.onFailure { error = it.message }
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            if (sessions.isNotEmpty()) {
                                Box(Modifier.weight(1f)) {
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { showSessionMenu = true }
                                    ) {
                                        Text(currentSession?.title ?: "Choose chat", maxLines = 1)
                                    }
                                    DropdownMenu(
                                        expanded = showSessionMenu,
                                        onDismissRequest = { showSessionMenu = false }
                                    ) {
                                        sessions.forEach { session ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(session.title, fontWeight = if (session.id == selectedSessionId) FontWeight.Bold else FontWeight.Normal)
                                                        Text(
                                                            session.modelId ?: "Auto",
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    selectedSessionId = session.id
                                                    selectedModel = session.modelId ?: selectedModel
                                                    api.setSelectedModelId(selectedModel)
                                                    showSessionMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (currentSession != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(currentSession.title, fontWeight = FontWeight.Bold)
                                    Text(
                                        buildString {
                                            append(activeModel?.name ?: "Auto")
                                            currentProject?.name?.let { append(" • "); append(it) }
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                TextButton(onClick = { showDeleteSessionDialog = currentSession }) {
                                    Text("Delete")
                                }
                            }
                        } else {
                            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(18.dp)) {
                                    Text("No chat session yet", fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    Text("Create a session, pick a model, and start working with Solar.")
                                    Spacer(Modifier.height(10.dp))
                                    OutlinedButton(onClick = {
                                        if (api.authToken.isNullOrBlank()) {
                                            screen = "Settings"
                                            error = "Connect GitHub before creating synced chats."
                                        } else {
                                            newChatProjectId = null
                                            newChatModelId = selectedModel
                                            showNewChatDialog = true
                                        }
                                    }) { Text("Create first chat") }
                                }
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(messages) { message ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            if (message.role == "user") "You" else "Solar",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(message.text)
                                    }
                                }
                            }
                        }

                        if (loading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }

                        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Ask Solar to code, explain, debug...") },
                                maxLines = 5
                            )
                            Spacer(Modifier.width(8.dp))
                            FilledIconButton(
                                enabled = !loading && input.isNotBlank() && currentSession != null,
                                onClick = { sendMessage() }
                            ) {
                                Icon(Icons.Default.Send, "Send")
                            }
                        }
                    }
                }

                "Projects" -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Projects", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Persistent workspaces for Solar.")
                        }
                        IconButton(onClick = {
                            scope.launch { runCatching { api.projects() }.onSuccess { projects = it } }
                        }) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                        Button(onClick = { showProjectDialog = true }) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(4.dp))
                            Text("New")
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(projects) { project ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(project.name, fontWeight = FontWeight.Bold)
                                    if (project.description.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(project.description)
                                    }
                                    project.githubOwner?.let { owner ->
                                        Text(owner + "/" + (project.githubRepo ?: ""), style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row {
                                        OutlinedButton(onClick = {
                                            scope.launch {
                                                runCatching { api.createSession(project.id, selectedModel.ifBlank { null }) }
                                                    .onSuccess {
                                                        sessions = listOf(it) + sessions
                                                        selectedSessionId = it.id
                                                        messages = emptyList()
                                                        screen = "Chats"
                                                    }
                                                    .onFailure { error = it.message }
                                            }
                                        }) { Text("Open chat") }
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(onClick = {
                                            scope.launch {
                                                runCatching { api.deleteProject(project.id) }
                                                    .onSuccess { projects = projects.filterNot { it.id == project.id } }
                                                    .onFailure { error = it.message }
                                            }
                                        }) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }

                    if (repos.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("GitHub repositories", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(repos.take(25)) { repo ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(repo.fullName, fontWeight = FontWeight.Bold)
                                            repo.description?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                        }
                                        TextButton(onClick = {
                                            scope.launch {
                                                runCatching { api.createProject(repo.name, repo.description.orEmpty(), repo.owner, repo.name, "main") }
                                                    .onSuccess { projects = listOf(it) + projects }
                                                    .onFailure { error = it.message }
                                            }
                                        }) { Text("Add") }
                                    }
                                }
                            }
                        }
                    }
                }

                "Models" -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Models", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Select the model Solar should use.")
                        }
                        IconButton(onClick = {
                            scope.launch { runCatching { api.models() }.onSuccess { models = it } }
                        }) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(models) { model ->
                            Card(
                                onClick = {
                                    selectedModel = model.id
                                    api.setSelectedModelId(model.id)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedModel == model.id,
                                        onClick = {
                                            selectedModel = model.id
                                            api.setSelectedModelId(model.id)
                                        }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(model.name, fontWeight = FontWeight.Bold)
                                        Text(model.provider, style = MaterialTheme.typography.labelSmall)
                                        if (model.desc.isNotBlank()) Text(model.desc, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("GitHub account", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(if (user == null) "Not connected" else "Connected as @" + user!!.login)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { startGithubLogin() },
                                enabled = !authBusy
                            ) {
                                Icon(Icons.Default.Code, null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (user == null) "Connect GitHub" else "Reconnect GitHub")
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Agent permissions", fontWeight = FontWeight.Bold)
                            PermissionRow("Allow repository writes", allowWrites) { allowWrites = it }
                            PermissionRow("Allow Termux commands", allowTermux) { allowTermux = it }
                            PermissionRow("Allow internet", allowInternet) { allowInternet = it }
                            PermissionRow("Allow browser", allowBrowser) { allowBrowser = it }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Backend", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = backendDraft,
                                onValueChange = { backendDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                api.baseUrl = backendDraft
                                error = null
                                scope.launch { refreshWorkspace() }
                            }) { Text("Save backend URL") }
                            Text(
                                "Emulator default: http://10.0.2.2:8080",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showNewChatDialog) {
        NewChatDialog(
            projects = projects,
            models = models,
            initialProjectId = newChatProjectId,
            initialModelId = newChatModelId,
            onDismiss = { showNewChatDialog = false },
            onCreate = { projectId, modelId ->
                scope.launch {
                    runCatching { api.createSession(projectId, modelId.ifBlank { null }) }
                        .onSuccess {
                            sessions = listOf(it) + sessions
                            selectedSessionId = it.id
                            selectedModel = it.modelId ?: selectedModel
                            api.setSelectedModelId(selectedModel)
                            messages = emptyList()
                            showNewChatDialog = false
                            screen = "Chats"
                            error = null
                        }
                        .onFailure { error = it.message }
                }
            }
        )
    }

    if (showProjectDialog) {
        ProjectDialog(
            onDismiss = { showProjectDialog = false },
            onCreate = { name, description, owner, repo, ref ->
                scope.launch {
                    runCatching { api.createProject(name, description, owner, repo, ref) }
                        .onSuccess {
                            projects = listOf(it) + projects
                            showProjectDialog = false
                            error = null
                        }
                        .onFailure { error = it.message }
                }
            }
        )
    }

    showDeleteSessionDialog?.let { session ->
        AlertDialog(
            onDismissRequest = { showDeleteSessionDialog = null },
            title = { Text("Delete chat?") },
            text = { Text(session.title) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { api.deleteSession(session.id) }
                            .onSuccess {
                                sessions = sessions.filterNot { it.id == session.id }
                                if (selectedSessionId == session.id) {
                                    selectedSessionId = sessions.firstOrNull()?.id
                                    messages = emptyList()
                                }
                                showDeleteSessionDialog = null
                            }
                            .onFailure { error = it.message }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteSessionDialog = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NewChatDialog(
    projects: List<Project>,
    models: List<Model>,
    initialProjectId: String?,
    initialModelId: String,
    onDismiss: () -> Unit,
    onCreate: (String?, String) -> Unit
) {
    var projectId by remember { mutableStateOf(initialProjectId) }
    var modelId by remember { mutableStateOf(initialModelId) }
    var showProjectPicker by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose the workspace and model for this session.")
                Box {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showProjectPicker = true }
                    ) {
                        Text(projects.firstOrNull { it.id == projectId }?.name ?: "No project")
                    }
                    DropdownMenu(
                        expanded = showProjectPicker,
                        onDismissRequest = { showProjectPicker = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("No project") },
                            onClick = {
                                projectId = null
                                showProjectPicker = false
                            }
                        )
                        projects.forEach { project ->
                            DropdownMenuItem(
                                text = { Text(project.name) },
                                onClick = {
                                    projectId = project.id
                                    showProjectPicker = false
                                }
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showModelPicker = true },
                        enabled = models.isNotEmpty()
                    ) {
                        Text(models.firstOrNull { it.id == modelId }?.name ?: "Auto")
                    }
                    DropdownMenu(
                        expanded = showModelPicker,
                        onDismissRequest = { showModelPicker = false }
                    ) {
                        models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.name) },
                                onClick = {
                                    modelId = model.id
                                    showModelPicker = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = modelId.isNotBlank(),
                onClick = { onCreate(projectId, modelId) }
            ) { Text("Create chat") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PermissionRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String?, String?, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("main") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, minLines = 2)
                OutlinedTextField(owner, { owner = it }, label = { Text("GitHub owner (optional)") }, singleLine = true)
                OutlinedTextField(repo, { repo = it }, label = { Text("GitHub repository (optional)") }, singleLine = true)
                OutlinedTextField(ref, { ref = it }, label = { Text("Branch / ref") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onCreate(
                        name.trim(),
                        description.trim(),
                        owner.trim().ifBlank { null },
                        repo.trim().ifBlank { null },
                        ref.trim().ifBlank { "main" }
                    )
                }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
