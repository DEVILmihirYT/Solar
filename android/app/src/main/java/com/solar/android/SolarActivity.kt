package com.solar.android

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Bg = Color(0xFF090D13)
private val Panel = Color(0xFF121923)
private val Panel2 = Color(0xFF18212D)
private val Accent = Color(0xFF9CFF6B)
private val Muted = Color(0xFF94A3B8)

private data class Model(val id:String,val name:String,val provider:String,val desc:String,val caps:List<String>)
private data class Msg(val role:String,val text:String)
private data class Session(val id:String,val name:String,val messages:List<Msg>)
private data class Project(val id:String,val name:String,val repo:String,val branch:String)
private data class Repo(val fullName:String,val branch:String,val privateRepo:Boolean)
private data class Account(val login:String,val name:String?)

private class Store(context:Context) {
    private val p=context.getSharedPreferences("solar_store",Context.MODE_PRIVATE)
    var model: String
        get() = p.getString("model", "openrouter/free") ?: "openrouter/free"
        set(value) {
            p.edit().putString("model", value).apply()
        }
    fun sessions():List<Session>{
        val raw=p.getString("sessions",null) ?: return listOf(Session("s-${UUID.randomUUID()}","New chat",emptyList()))
        return runCatching{
            val a=JSONArray(raw)
            List(a.length()){i->
                val o=a.getJSONObject(i);val m=o.optJSONArray("messages")
                Session(o.getString("id"),o.optString("name","Chat"),List(m?.length()?:0){j->{val x=m!!.getJSONObject(j);Msg(x.optString("role"),x.optString("text"))}})
            }
        }.getOrDefault(listOf(Session("s-${UUID.randomUUID()}","New chat",emptyList())))
    }
    fun saveSessions(list:List<Session>){val a=JSONArray();list.forEach{s->val o=JSONObject().put("id",s.id).put("name",s.name);val m=JSONArray();s.messages.forEach{x->m.put(JSONObject().put("role",x.role).put("text",x.text))};o.put("messages",m);a.put(o)};p.edit().putString("sessions",a.toString()).apply()}
    fun projects():List<Project>{
        val raw=p.getString("projects","[]") ?: "[]"
        return runCatching{
            val a=JSONArray(raw);List(a.length()){i->{val o=a.getJSONObject(i);Project(o.getString("id"),o.optString("name","Project"),o.optString("repo"),o.optString("branch","main"))}}
        }.getOrDefault(emptyList())
    }
    fun saveProjects(list:List<Project>){val a=JSONArray();list.forEach{x->a.put(JSONObject().put("id",x.id).put("name",x.name).put("repo",x.repo).put("branch",x.branch))};p.edit().putString("projects",a.toString()).apply()}
}

private class Api(context:Context){
    private val p=context.getSharedPreferences("solar",Context.MODE_PRIVATE)
    var baseUrl: String
        get() = p.getString("base", "http://10.0.2.2:8080")!!.trimEnd('/')
        set(value) {
            p.edit().putString("base", value.trimEnd('/')).apply()
        }

    var session: String?
        get() = p.getString("session_id", null)
        private set(value) {
            val editor = p.edit()
            if (value.isNullOrBlank()) editor.remove("session_id")
            else editor.putString("session_id", value)
            editor.apply()
        }
    private val client=OkHttpClient()
    private fun call(path:String,method:String="GET",json:String?=null):Response{
        val b=Request.Builder().url(baseUrl+path);session?.let{b.header("X-Solar-Session",it)}
        if(method=="POST")b.post((json?:"{}").toRequestBody("application/json".toMediaType()))else b.get()
        return client.newCall(b.build()).execute()
    }
    private fun body(r:Response):String{val s=r.body?.string().orEmpty();r.close();return s}
    private fun err(text:String,fallback:String)=runCatching{JSONObject(text).optString("error")}.getOrNull()?.takeIf{it.isNotBlank()}?:text.ifBlank{fallback}
    suspend fun models():List<Model>=withContext(Dispatchers.IO){
        val r=call("/api/models");val t=body(r);if(!r.isSuccessful)throw Exception(err(t,"Unable to load models."))
        val a=JSONObject(t).getJSONArray("models")
        List(a.length()){i->{val o=a.getJSONObject(i);val c=o.optJSONArray("capabilities")
            Model(o.optString("id"),o.optString("displayName",o.optString("id")),o.optString("provider"),o.optString("description"),List(c?.length()?:0){j->c!!.optString(j)})}}
    }
    suspend fun me():Account?=withContext(Dispatchers.IO){
        val r=call("/api/auth/me");val t=body(r);if(r.code==401){session=null;return@withContext null}
        if(!r.isSuccessful)throw Exception(err(t,"Authentication failed."))
        val o=JSONObject(t);Account(o.optString("login"),o.optString("name").ifBlank{null})
    }
    suspend fun repos():List<Repo>=withContext(Dispatchers.IO){
        val r=call("/api/github/repositories");val t=body(r);if(!r.isSuccessful)throw Exception(err(t,"Unable to load repositories."))
        val a=JSONArray(t);List(a.length()){i->{val o=a.getJSONObject(i);Repo(o.optString("full_name"),o.optString("default_branch","main"),o.optBoolean("private",false))}}
    }
    suspend fun exchange(ticket:String)=withContext(Dispatchers.IO){
        val r=call("/api/auth/mobile/exchange","POST",JSONObject().put("ticket",ticket).toString());val t=body(r)
        if(!r.isSuccessful)throw Exception(err(t,"GitHub sign-in failed."));session=JSONObject(t).getString("sessionId")
    }
    suspend fun logout()=withContext(Dispatchers.IO){runCatching{body(call("/api/auth/logout","POST"))};session=null}
    suspend fun run(message:String,model:String,flags:Map<String,Boolean>)=withContext(Dispatchers.IO){
        val o=JSONObject().put("message",message).put("modelId",model).put("stream",false);flags.forEach{(k,v)->o.put(k,v)}
        val r=call("/api/agent/run","POST",o.toString());val t=body(r);if(!r.isSuccessful)throw Exception(err(t,"Solar request failed."))
        JSONObject(t).optString("message",JSONObject(t).optString("answer","No response returned."))
    }
    fun login(context:Context)=CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context,Uri.parse("$baseUrl/api/auth/github?mobile=1"))
}

class SolarActivity:ComponentActivity(){
    private val ticket=mutableStateOf<String?>(null)
    override fun onCreate(state:Bundle?){super.onCreate(state);handle(intent);setContent{Theme{SolarApp(this,ticket.value){ticket.value=null}}}}
    override fun onNewIntent(i:Intent){super.onNewIntent(i);handle(i)}
    private fun handle(i:Intent?){val d=i?.data;if(d?.scheme=="solar"&&d.host=="oauth")ticket.value=d.getQueryParameter("ticket")}
}

@Composable private fun Theme(content:@Composable()->Unit)=MaterialTheme(colorScheme=darkColorScheme(background=Bg,surface=Panel,surfaceContainer=Panel,primary=Accent,onPrimary=Color.Black,onSurface=Color(0xFFF5F7FA),onSurfaceVariant=Muted),content=content)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SolarApp(context:Context,ticket:String?,done:()->Unit){
    val api=remember{Api(context.applicationContext)}
    val store=remember{Store(context.applicationContext)}
    val scope=rememberCoroutineScope()
    val drawer=rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    var tab by remember{mutableStateOf("Chat")}
    var models by remember{mutableStateOf(emptyList<Model>())}
    var selectedModel by remember{mutableStateOf(store.model)}
    var sessions by remember{mutableStateOf(store.sessions())}
    var activeSessionId by remember{mutableStateOf(sessions.first().id)}
    var projects by remember{mutableStateOf(store.projects())}
    var activeProjectId by remember{mutableStateOf<String?>(null)}
    var account by remember{mutableStateOf<Account?>(null)}
    var repos by remember{mutableStateOf(emptyList<Repo>())}
    var text by remember{mutableStateOf("")}
    var error by remember{mutableStateOf<String?>(null)}
    var loading by remember{mutableStateOf(false)}
    var showModels by remember{mutableStateOf(false)}
    var showProjects by remember{mutableStateOf(false)}
    var showCreate by remember{mutableStateOf(false)}
    var showRepos by remember{mutableStateOf(false)}
    var newName by remember{mutableStateOf("")}
    var newRepo by remember{mutableStateOf("")}
    var newBranch by remember{mutableStateOf("main")}
    var writes by remember{mutableStateOf(false)}
    var termux by remember{mutableStateOf(false)}
    var internet by remember{mutableStateOf(true)}
    var browser by remember{mutableStateOf(false)}

    val active=sessions.firstOrNull{it.id==activeSessionId}?:sessions.first()
    val project=projects.firstOrNull{it.id==activeProjectId}
    val model=models.firstOrNull{it.id==selectedModel}

    fun saveSession(next:List<Session>){sessions=next;store.saveSessions(next)}
    fun saveProjects(next:List<Project>){projects=next;store.saveProjects(next)}
    fun newChat(){val s=Session("s-${UUID.randomUUID()}","New chat",emptyList());saveSession(listOf(s)+sessions);activeSessionId=s.id;scope.launch{drawer.close()}}
    fun deleteChat(id:String){val next=sessions.filterNot{it.id==id}.ifEmpty{listOf(Session("s-${UUID.randomUUID()}","New chat",emptyList()))};saveSession(next);if(activeSessionId==id)activeSessionId=next.first().id}
    fun append(m:Msg){val s=active;val name=if(s.name=="New chat"&&m.role=="user")m.text.take(32)else s.name;saveSession(sessions.map{if(it.id==s.id)it.copy(name=name,messages=it.messages+m)else it})}

    LaunchedEffect(Unit){
        runCatching{models=api.models()}.onFailure{error=it.message}
        account=api.me()
        if(account!=null)runCatching{repos=api.repos()}.onFailure{error=it.message}
    }
    LaunchedEffect(ticket){
        val t=ticket?:return@LaunchedEffect
        runCatching{api.exchange(t);account=api.me();repos=api.repos()}.onFailure{error=it.message}.onSuccess{error=null}
        done()
    }

    ModalNavigationDrawer(
        drawerState=drawer,
        gesturesEnabled=tab=="Chat",
        drawerContent={
            ModalDrawerSheet(drawerContainerColor = Panel){
                Column(Modifier.fillMaxSize().padding(16.dp)){
                    Text("SOLAR",fontWeight=FontWeight.Black,color=Accent,style=MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick={newChat()},Modifier.fillMaxWidth()){Icon(Icons.Default.Add,null);Spacer(Modifier.width(6.dp));Text("New chat")}
                    Spacer(Modifier.height(12.dp))
                    Text("SESSIONS",color=Muted,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
                    LazyColumn(Modifier.weight(1f)){
                        items(sessions,key={it.id}){s->
                            NavigationDrawerItem(
                                label={Text(s.name,maxLines=1,overflow=TextOverflow.Ellipsis)},
                                selected=s.id==active.id,
                                onClick={activeSessionId=s.id;scope.launch{drawer.close()}},
                                icon={Icon(Icons.Default.ChatBubble,null)},
                                badge={IconButton(onClick={deleteChat(s.id)}){Icon(Icons.Default.Delete,null)}}
                            )
                        }
                    }
                    if(account==null)OutlinedButton(onClick={api.login(context)},Modifier.fillMaxWidth()){Icon(Icons.Default.Code,null);Spacer(Modifier.width(6.dp));Text("Connect GitHub")}
                    else{Text(account!!.login,fontWeight=FontWeight.Bold);TextButton(onClick={scope.launch{api.logout();account=null;repos=emptyList()}}){Text("Sign out")}}
                }
            }
        }
    ){
        Scaffold(
            containerColor=Bg,
            topBar={
                SmallTopAppBar(
                    title={Column{Text(tab);if(tab=="Chat")Text(project?.name?:"No project",color=Muted,style=MaterialTheme.typography.labelSmall)}},
                    navigationIcon={IconButton(onClick={scope.launch{drawer.open()}}){Icon(Icons.Default.Menu,"Sessions")}},
                    actions={
                        if(tab=="Chat")OutlinedButton(onClick={showModels=true}){
                            Icon(Icons.Default.Code,null);Spacer(Modifier.width(5.dp))
                            Text(model?.name?:"Select model",maxLines=1,overflow=TextOverflow.Ellipsis)
                            Icon(Icons.Default.ArrowDropDown,null)
                        }
                    }
                )
            },
            bottomBar={
                NavigationBar(containerColor=Panel){
                    NavigationBarItem(tab=="Chat",{tab="Chat"},icon={Icon(Icons.Default.ChatBubble,null)},label={Text("Chat")})
                    NavigationBarItem(tab=="Projects",{tab="Projects"},icon={Icon(Icons.Default.Folder,null)},label={Text("Projects")})
                    NavigationBarItem(tab=="Settings",{tab="Settings"},icon={Icon(Icons.Default.Settings,null)},label={Text("Settings")})
                }
            }
        ){pad->
            when(tab){
                "Chat"->ChatView(pad,active,model,project,text,loading,error,{text=it},{showProjects=true},{
                    val q=text.trim()
                    if (!q.isBlank() && !loading && model != null) {
                        text="";error=null;append(Msg("user",q));loading=true
                        scope.launch{
                        try{append(Msg("assistant",api.run(q,selectedModel,mapOf("allowWrites" to writes,"allowTermux" to termux,"allowInternet" to internet,"allowBrowser" to browser,"allowFallback" to true)))}
                        catch(e:Exception){error=e.message?:"Request failed"}
                        finally{loading=false}
                    }
                })
                "Projects"->ProjectsView(pad,projects,activeProjectId,account!=null,repos,{showCreate=true},{activeProjectId=it;tab="Chat"},{api.login(context)},{scope.launch{runCatching{repos=api.repos()}.onFailure{error=it.message}}})
                else->SettingsView(pad,account,api.baseUrl,writes,termux,internet,browser,{writes=it},{termux=it},{internet=it},{browser=it},{api.login(context)},{scope.launch{api.logout();account=null;repos=emptyList()}},{api.baseUrl="http://10.0.2.2:8080"})
            }
        }
    }

    if(showModels)AlertDialog(
        onDismissRequest={showModels=false},
        title={Text("Choose AI model")},
        text={LazyColumn(verticalArrangement=Arrangement.spacedBy(7.dp)){
            items(models){m->
                Card(onClick={selectedModel=m.id;store.model=m.id;showModels=false},colors = CardDefaults.cardColors(containerColor = if (m.id == selectedModel) Panel2 else Panel),modifier=Modifier.fillMaxWidth()){
                    Column(Modifier.padding(13.dp)){
                        Text(m.name,fontWeight=FontWeight.Bold)
                        Text("${m.provider} • ${m.caps.joinToString(", ")}",color=Muted,style=MaterialTheme.typography.labelSmall)
                        if(m.desc.isNotBlank())Text(m.desc,color=Muted,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }},
        confirmButton={TextButton(onClick={showModels=false}){Text("Close")}}
    )

    if(showProjects)AlertDialog(
        onDismissRequest={showProjects=false},
        title={Text("Select project")},
        text={LazyColumn{items(projects){p->NavigationDrawerItem(label={Text(p.name)},selected=p.id==activeProjectId,onClick={activeProjectId=p.id;showProjects=false},icon={Icon(Icons.Default.Folder,null)})}}},
        confirmButton={TextButton(onClick={showProjects=false}){Text("Done")}}
    )

    if(showCreate)AlertDialog(
        onDismissRequest={showCreate=false},
        title={Text("Create project")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(newName,{newName=it},label={Text("Project name")},singleLine=true)
            OutlinedButton(onClick={showRepos=true},Modifier.fillMaxWidth()){Text(newRepo.ifBlank{"Link GitHub repo (optional)"},maxLines=1,overflow=TextOverflow.Ellipsis)}
            OutlinedTextField(newBranch,{newBranch=it},label={Text("Branch")},singleLine=true)
        }},
        confirmButton={Button(onClick={
            val p=Project("p-${UUID.randomUUID()}",newName.trim().ifBlank{"Untitled project"},newRepo,newBranch.ifBlank{"main"})
            saveProjects(listOf(p)+projects);activeProjectId=p.id;newName="";newRepo="";newBranch="main";showCreate=false
        }){Text("Create")}},
        dismissButton={TextButton(onClick={showCreate=false}){Text("Cancel")}}
    )

    if(showRepos)AlertDialog(
        onDismissRequest={showRepos=false},
        title={Text("GitHub repositories")},
        text={LazyColumn{items(repos){r->
            NavigationDrawerItem(
                label = {
                    Column {
                        Text(r.fullName)
                        Text(
                            (if (r.privateRepo) "Private" else "Public") + " • " + r.branch,
                            color = Muted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                selected=newRepo==r.fullName,
                onClick={newRepo=r.fullName;newBranch=r.branch;showRepos=false},
                icon={Icon(Icons.Default.Code,null)}
            )
        }}},
        confirmButton={TextButton(onClick={showRepos=false}){Text("Close")}}
    )
}

@Composable private fun ChatView(
    pad:PaddingValues,s:Session,model:Model?,project:Project?,text:String,loading:Boolean,error:String?,
    setText:(String)->Unit,chooseProject:()->Unit,send:()->Unit
){
    Column(Modifier.fillMaxSize().padding(pad).padding(horizontal=14.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){
            OutlinedButton(onClick=chooseProject){Icon(Icons.Default.Folder,null);Spacer(Modifier.width(5.dp));Text(project?.name?:"Choose project",maxLines=1,overflow=TextOverflow.Ellipsis)}
            Spacer(Modifier.weight(1f))
            Text(model?.provider?:"",color=Muted,style=MaterialTheme.typography.labelSmall)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            items(s.messages){m->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.role=="user")Arrangement.End else Arrangement.Start){
                    Surface(color=if(m.role=="user")Accent else Panel,shape=RoundedCornerShape(17.dp)){
                        Text(m.text,color=if(m.role=="user")Color.Black else Color.White,modifier=Modifier.padding(13.dp))
                    }
                }
            }
            if(loading)item{Surface(color=Panel,shape=RoundedCornerShape(17.dp)){Text("Solar is working…",color=Muted,modifier=Modifier.padding(13.dp))}}
        }
        if(!error.isNullOrBlank())Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(bottom=6.dp))
        Row(verticalAlignment=Alignment.Bottom,modifier=Modifier.fillMaxWidth().padding(bottom=10.dp)){
            OutlinedTextField(text,setText,Modifier.weight(1f),placeholder={Text("Ask Solar to code, explain, debug…")},maxLines=6)
            Spacer(Modifier.width(7.dp))
            FilledIconButton(onClick=send,enabled=text.isNotBlank()&&!loading&&model!=null){Icon(Icons.Default.Send,"Send")}
        }
    }
}

@Composable private fun ProjectsView(
    pad:PaddingValues,projects:List<Project>,active:String?,connected:Boolean,repos:List<Repo>,
    create:()->Unit,select:(String)->Unit,connect:()->Unit,refresh:()->Unit
){
    Column(Modifier.fillMaxSize().padding(pad).padding(horizontal=14.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){
            Column(Modifier.weight(1f)){Text("Projects",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Create workspaces and link GitHub repositories.",color=Muted)}
            FilledIconButton(onClick=create){Icon(Icons.Default.Add,"Create project")}
        }
        Spacer(Modifier.height(12.dp))
        if(!connected){
            Card(colors=CardDefaults.cardColors(Panel),modifier=Modifier.fillMaxWidth()){
                Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically){
                    Icon(Icons.Default.Code,null);Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)){Text("GitHub not connected",fontWeight=FontWeight.Bold);Text("Connect to browse repositories.",color=Muted,style=MaterialTheme.typography.bodySmall)}
                    OutlinedButton(onClick=connect){Text("Connect")}
                }
            }
        }else{TextButton(onClick=refresh){Text("Refresh ${repos.size} repos")}}
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp)){
            items(projects){p->
                Card(onClick={select(p.id)},colors=CardDefaults.cardColors(if(p.id==active)Panel2 else Panel),modifier=Modifier.fillMaxWidth()){
                    Column(Modifier.padding(15.dp)){
                        Row{Icon(Icons.Default.Folder,null,tint=Accent);Spacer(Modifier.width(8.dp));Text(p.name,fontWeight=FontWeight.Bold)}
                        Spacer(Modifier.height(5.dp));Text(p.repo.ifBlank{"No GitHub repo linked"},color=Muted);Text("Branch: ${p.branch}",color=Muted,style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if(projects.isEmpty())item{Card(colors=CardDefaults.cardColors(Panel),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text("No projects yet",fontWeight=FontWeight.Bold);Text("Create a project and optionally attach a GitHub repository.",color=Muted)}}}
        }
    }
}

@Composable private fun SettingsView(
    pad:PaddingValues,account:Account?,base:String,writes:Boolean,termux:Boolean,internet:Boolean,browser:Boolean,
    setW:(Boolean)->Unit,setT:(Boolean)->Unit,setI:(Boolean)->Unit,setB:(Boolean)->Unit,login:()->Unit,logout:()->Unit,reset:()->Unit
){
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(9.dp),contentPadding=PaddingValues(bottom=16.dp)){
        item{Text("Settings",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Agent permissions and connection.",color=Muted)}
        item{
            Card(colors=CardDefaults.cardColors(Panel),modifier=Modifier.fillMaxWidth()){
                Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically){
                    Icon(if(account==null)Icons.Default.Person else Icons.Default.Code,null);Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)){Text(account?.login?:"GitHub not connected",fontWeight=FontWeight.Bold);Text(base,color=Muted,style=MaterialTheme.typography.labelSmall)}
                    if(account==null)OutlinedButton(onClick=login){Text("Connect")}else TextButton(onClick=logout){Text("Sign out")}
                }
            }
        }
        item{Permission("GitHub writes",writes,setW,Icons.Default.Storage)}
        item{Permission("Termux commands",termux,setT,Icons.Default.Terminal)}
        item{Permission("Internet",internet,setI,Icons.Default.Code)}
        item{Permission("Browser tools",browser,setB,Icons.Default.Code)}
        item{
            Card(colors=CardDefaults.cardColors(Panel),modifier=Modifier.fillMaxWidth()){
                Column(Modifier.padding(15.dp)){Text("Backend");Text(base,color=Muted,style=MaterialTheme.typography.bodySmall);Spacer(Modifier.height(7.dp));OutlinedButton(onClick=reset){Text("Reset emulator backend")}}
            }
        }
    }
}

@Composable private fun Permission(title:String,checked:Boolean,set:(Boolean)->Unit,icon:androidx.compose.ui.graphics.vector.ImageVector){
    Card(colors=CardDefaults.cardColors(Panel),modifier=Modifier.fillMaxWidth()){
        Row(Modifier.fillMaxWidth().padding(15.dp),verticalAlignment=Alignment.CenterVertically){
            Icon(icon,null,tint=Accent);Spacer(Modifier.width(10.dp));Text(title,Modifier.weight(1f));Switch(checked,onCheckedChange=set)
        }
    }
}
