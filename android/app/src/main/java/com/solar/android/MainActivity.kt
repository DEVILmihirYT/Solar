package com.solar.android

import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private val SolarBg = Color(0xFF0B0F14)
private val SolarCard = Color(0xFF151B23)
private val SolarAccent = Color(0xFF8BFF6A)

data class Model(
    val id: String,
    val name: String,
    val desc: String
)

data class Msg(
    val role: String,
    val text: String
)

class SolarApi(context: Context) {
    private val prefs = context.getSharedPreferences("solar", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base", "http://10.0.2.2:3000")!!.trimEnd('/')
        set(value) {
            prefs.edit().putString("base", value.trimEnd('/')).apply()
        }

    private val cookies = object : CookieJar {
        private val map = mutableMapOf<String, List<Cookie>>()

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            map[url.host].orEmpty()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            map[url.host] = cookies
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookies)
        .build()

    suspend fun models(): List<Model> = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder()
                .url("$baseUrl/api/models")
                .get()
                .build()
        ).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception(extractError(body, "Unable to load models."))
            }

            val array = JSONObject(body).getJSONArray("models")
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                Model(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    desc = item.optString("description")
                )
            }
        }
    }

    suspend fun run(
        message: String,
        model: String,
        flags: Map<String, Boolean>
    ): String = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("message", message)
            .put("modelId", model)
            .put("stream", false)

        flags.forEach { (key, value) ->
            payload.put(key, value)
        }

        client.newCall(
            Request.Builder()
                .url("$baseUrl/api/agent/run")
                .post(
                    payload.toString()
                        .toRequestBody("application/json".toMediaType())
                )
                .build()
        ).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception(extractError(body, "Solar request failed."))
            }

            val json = JSONObject(body)
            json.optString(
                "answer",
                json.optString(
                    "response",
                    json.optString("output", "No response returned.")
                )
            )
        }
    }

    fun login(context: Context) {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(
                context,
                Uri.parse("$baseUrl/api/auth/github")
            )
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
        setContent {
            SolarTheme {
                SolarApp(this)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolarApp(context: Context) {
    val api = remember { SolarApi(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf("Chat") }
    var models by remember { mutableStateOf(emptyList<Model>()) }
    var selected by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(emptyList<Msg>()) }
    var error by remember { mutableStateOf<String?>(null) }

    var allowWrites by remember { mutableStateOf(false) }
    var allowTermux by remember { mutableStateOf(false) }
    var allowInternet by remember { mutableStateOf(true) }
    var allowBrowser by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { api.models() }
            .onSuccess { loaded ->
                models = loaded
                selected = loaded.firstOrNull()?.id.orEmpty()
                error = null
            }
            .onFailure {
                error = it.message ?: "Unable to connect to Solar backend."
            }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail(containerColor = SolarBg) {
            Text(
                text = "S",
                color = SolarAccent,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(18.dp)
            )

            listOf(
                "Chat" to Icons.Default.Chat,
                "Projects" to Icons.Default.Folder,
                "Settings" to Icons.Default.Settings
            ).forEach { (name, icon) ->
                NavigationRailItem(
                    selected = screen == name,
                    onClick = { screen = name },
                    icon = { Icon(icon, contentDescription = name) },
                    label = { Text(name) }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            when (screen) {
                "Chat" -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Solar",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Button(onClick = { api.login(context) }) {
                            Text("GitHub")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (models.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }

                        Box {
                            OutlinedButton(onClick = { expanded = true }) {
                                Text(
                                    text = models
                                        .firstOrNull { it.id == selected }
                                        ?.name
                                        ?: "Choose model"
                                )
                            }

                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                models.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(model.name)
                                                if (model.desc.isNotBlank()) {
                                                    Text(
                                                        model.desc,
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            selected = model.id
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages) { message ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    text = message.text,
                                    modifier = Modifier.padding(14.dp),
                                    color = if (message.role == "user") {
                                        SolarAccent
                                    } else {
                                        Color.White
                                    }
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text("Ask Solar to code, explain, debug...")
                            },
                            maxLines = 5
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        FilledIconButton(
                            enabled = !loading &&
                                input.isNotBlank() &&
                                selected.isNotBlank(),
                            onClick = {
                                val question = input.trim()
                                input = ""
                                error = null
                                messages = messages + Msg("user", question)
                                loading = true

                                scope.launch {
                                    try {
                                        val answer = api.run(
                                            message = question,
                                            model = selected,
                                            flags = mapOf(
                                                "allowWrites" to allowWrites,
                                                "allowTermux" to allowTermux,
                                                "allowInternet" to allowInternet,
                                                "allowBrowser" to allowBrowser,
                                                "allowFallback" to true
                                            )
                                        )
                                        messages = messages + Msg("assistant", answer)
                                    } catch (exception: Exception) {
                                        error = exception.message ?: "Solar request failed."
                                    } finally {
                                        loading = false
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "Send"
                            )
                        }
                    }
                }

                "Projects" -> {
                    Text(
                        text = "Projects",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Card {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text("GitHub workspace")
                            Text(
                                "Connect GitHub to browse repositories and let Solar work inside an approved repo."
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { api.login(context) }) {
                                Text("Connect GitHub")
                            }
                        }
                    }
                }

                else -> {
                    Text(
                        text = "Agent permissions",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    PermissionRow(
                        title = "Allow repository writes",
                        checked = allowWrites,
                        onCheckedChange = { allowWrites = it }
                    )
                    PermissionRow(
                        title = "Allow Termux commands",
                        checked = allowTermux,
                        onCheckedChange = { allowTermux = it }
                    )
                    PermissionRow(
                        title = "Allow internet",
                        checked = allowInternet,
                        onCheckedChange = { allowInternet = it }
                    )
                    PermissionRow(
                        title = "Allow browser",
                        checked = allowBrowser,
                        onCheckedChange = { allowBrowser = it }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    OutlinedButton(
                        onClick = {
                            api.baseUrl = "http://10.0.2.2:3000"
                            error = null
                        }
                    ) {
                        Text("Reset backend URL")
                    }

                    Text(
                        text = \${api.baseUrl},
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
