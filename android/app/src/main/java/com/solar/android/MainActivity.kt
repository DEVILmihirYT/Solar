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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
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
import org.json.JSONArray
import org.json.JSONObject

private val SolarBg = Color(0xFF0B0F14)
private val SolarCard = Color(0xFF151B23)
private val SolarAccent = Color(0xFF8BFF6A)

data class Model(val id:String,val name:String,val desc:String,val provider:String,val capabilities:List<String>)
data class Project(val id:String,val name:String,val description:String,val githubOwner:String?,val githubRepo:String?,val githubRef:String?)
data class Session(val id:String,val projectId:String?,val title:String,val modelId:String?)
data class Msg(val role:String,val text:String)
data class AuthUser(val login:String,val name:String?)

class SolarApi(context:Context){
 private val prefs=context.getSharedPreferences("solar",Context.MODE_PRIVATE)
 var baseUrl:String
  get()=prefs.getString("base","http://10.0.2.2:8080")!!.trimEnd('/')
  set(value){prefs.edit().putString("base",value.trimEnd('/')).apply()}
 private var sessionToken:String?
  get()=prefs.getString("session_token",null)
  set(value){prefs.edit().apply{if(value.isNullOrBlank())remove("session_token")else putString("session_token",value)}.apply()}
 var selectedModelId:String
  get()=prefs.getString("selected_model","")?:""
  set(value){prefs.edit().putString("selected_model",value).apply()}
 var currentSessionId:String?
  get()=prefs.getString("current_session",null)
  set(value){prefs.edit().apply{if(value.isNullOrBlank())remove("current_session")else putString("current_session",value)}.apply()}
 val authenticated:Boolean get()=!sessionToken.isNullOrBlank()

 private val cookies=object:CookieJar{
  private val map=mutableMapOf<String,List<Cookie>>()
  override fun loadForRequest(url:HttpUrl):List<Cookie> = map[url.host].orEmpty()
  override fun saveFromResponse(url:HttpUrl,cookies:List<Cookie>){map[url.host]=cookies}
 }
 private val client=OkHttpClient.Builder().cookieJar(cookies).build()

 private fun request(path:String,method:String="GET",body:String?=null):Request{
  val b=Request.Builder().url(baseUrl+path)
  sessionToken?.let{b.addHeader("Authorization","Bearer "+it)}
  if(body!=null)b.method(method,body.toRequestBody("application/json".toMediaType()))else b.method(method,null)
  return b.build()
 }
 private suspend fun execute(path:String,method:String="GET",body:String?=null):String=withContext(Dispatchers.IO){
  client.newCall(request(path,method,body)).execute().use{response->
   val text=response.body?.string().orEmpty()
   if(!response.isSuccessful)throw Exception(extractError(text,"Request failed."))
   text
  }
 }
 suspend fun models():List<Model>{
  val array=JSONObject(execute("/api/models")).getJSONArray("models")
  return List(array.length()){i->
   val item=array.getJSONObject(i)
   Model(item.optString("id"),item.optString("displayName",item.optString("id")),item.optString("description").ifBlank{item.optString("provider")},item.optString("provider"),item.optJSONArray("capabilities")?.toStringList().orEmpty())
  }
 }
 suspend fun me():AuthUser{
  val item=JSONObject(execute("/api/auth/me"))
  return AuthUser(item.optString("login"),item.optString("name").takeIf{it.isNotBlank()})
 }
 suspend fun exchangeMobileCode(code:String):AuthUser{
  val json=JSONObject(execute("/api/auth/mobile/exchange","POST",JSONObject().put("code",code).toString()))
  sessionToken=json.getString("sessionToken")
  val user=json.getJSONObject("user")
  return AuthUser(user.optString("login"),user.optString("name").takeIf{it.isNotBlank()})
 }
 fun login(context:Context){CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context,Uri.parse(baseUrl+"/api/auth/github?platform=android"))}
 suspend fun logout(){runCatching{execute("/api/auth/logout","POST","{}")};sessionToken=null;currentSessionId=null}
 suspend fun projects():List<Project>{
  val array=JSONObject(execute("/api/projects")).getJSONArray("projects")
  return List(array.length()){i->
   val x=array.getJSONObject(i)
   Project(x.getString("id"),x.getString("name"),x.optString("description"),x.optString("githubOwner").takeIf{it.isNotBlank()},x.optString("githubRepo").takeIf{it.isNotBlank()},x.optString("githubRef").takeIf{it.isNotBlank()})
  }
 }
 suspend fun createProject(name:String,description:String,owner:String?,repo:String?,ref:String?):Project{
  val payload=JSONObject().put("name",name).put("description",description).apply{
   if(owner.isNullOrBlank()||repo.isNullOrBlank()){put("githubOwner",JSONObject.NULL);put("githubRepo",JSONObject.NULL)}
   else{put("githubOwner",owner);put("githubRepo",repo);put("githubRef",ref?.takeIf{it.isNotBlank()}?:JSONObject.NULL)}
  }
  val x=JSONObject(execute("/api/projects","POST",payload.toString())).getJSONObject("project")
  return Project(x.getString("id"),x.getString("name"),x.optString("description"),x.optString("githubOwner").takeIf{it.isNotBlank()},x.optString("githubRepo").takeIf{it.isNotBlank()},x.optString("githubRef").takeIf{it.isNotBlank()})
 }
 suspend fun sessions(projectId:String?=null):List<Session>{
  val suffix=projectId?.let{"?projectId="+Uri.encode(it)}.orEmpty()
  val array=JSONObject(execute("/api/sessions"+suffix)).getJSONArray("sessions")
  return List(array.length()){i->
   val x=array.getJSONObject(i)
   Session(x.getString("id"),x.optString("projectId").takeIf{it.isNotBlank()&&it!="null"},x.optString("title","New chat"),x.optString("modelId").takeIf{it.isNotBlank()&&it!="null"})
  }
 }
 suspend fun createSession(title:String,projectId:String?,modelId:String?):Session{
  val x=JSONObject(execute("/api/sessions","POST",JSONObject().put("title",title).put("projectId",projectId?:JSONObject.NULL).put("modelId",modelId?:JSONObject.NULL).toString())).getJSONObject("session")
  val s=Session(x.getString("id"),x.optString("projectId").takeIf{it.isNotBlank()&&it!="null"},x.optString("title","New chat"),x.optString("modelId").takeIf{it.isNotBlank()&&it!="null"})
  currentSessionId=s.id
  return s
 }
 suspend fun sessionMessages(sessionId:String):List<Msg>{
  val array=JSONObject(execute("/api/sessions/"+Uri.encode(sessionId))).getJSONArray("messages")
  return List(array.length()){i->{val x=array.getJSONObject(i);Msg(x.getString("role"),x.getString("content"))}}
 }
 suspend fun run(message:String,model:String,projectId:String?,sessionId:String?,flags:Map<String,Boolean>):Pair<String,String>{
  val payload=JSONObject().put("message",message).put("modelId",model).put("projectId",projectId?:JSONObject.NULL).put("sessionId",sessionId?:JSONObject.NULL).put("stream",false)
  flags.forEach{(key,value)->payload.put(key,value)}
  val json=JSONObject(execute("/api/agent/run","POST",payload.toString()))
  return json.optString("sessionId",sessionId.orEmpty()) to json.optString("message","No response returned.")
 }
 private fun extractError(body:String,fallback:String):String=runCatching{JSONObject(body).optString("error")}.getOrNull()?.takeIf{it.isNotBlank()}?:body.takeIf{it.isNotBlank()}?:fallback
}
private fun JSONArray.toStringList():List<String>=List(length()){optString(it)}

@Composable fun SolarTheme(content:@Composable()->Unit){MaterialTheme(colorScheme=darkColorScheme(background=SolarBg,surface=SolarCard,primary=SolarAccent,onPrimary=Color.Black),content=content)}

class MainActivity:ComponentActivity(){
 private var authCode by mutableStateOf<String?>(null)
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);handleIntent(intent);setContent{SolarTheme{SolarApp(this,authCode){authCode=null}}}}
 override fun onNewIntent(intent:Intent?){super.onNewIntent(intent);handleIntent(intent)}
 private fun handleIntent(intent:Intent?){val data=intent?.data?:return;if(data.scheme=="solar"&&data.host=="auth"&&data.path=="/callback")authCode=data.getQueryParameter("code")}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SolarApp(context:Context,authCode:String?,onAuthCodeHandled:()->Unit){
 val api=remember{SolarApi(context.applicationContext)}
 val scope=rememberCoroutineScope()
 var screen by remember{mutableStateOf("Chat")}
 var models by remember{mutableStateOf(emptyList<Model>())}
 var projects by remember{mutableStateOf(emptyList<Project>())}
 var sessions by remember{mutableStateOf(emptyList<Session>())}
 var selectedModel by remember{mutableStateOf(api.selectedModelId)}
 var selectedProject by remember{mutableStateOf<String?>(null)}
 var selectedSession by remember{mutableStateOf(api.currentSessionId)}
 var messages by remember{mutableStateOf(emptyList<Msg>())}
 var input by remember{mutableStateOf("")}
 var loading by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf<String?>(null)}
 var user by remember{mutableStateOf<AuthUser?>(null)}
 var allowWrites by remember{mutableStateOf(false)}
 var allowTermux by remember{mutableStateOf(false)}
 var allowInternet by remember{mutableStateOf(true)}
 var allowBrowser by remember{mutableStateOf(false)}
 var showCreateProject by remember{mutableStateOf(false)}
 var showCreateSession by remember{mutableStateOf(false)}

 fun refresh(){
  scope.launch{
   if(!api.authenticated)return@launch
   runCatching{api.projects()}.onSuccess{projects=it}.onFailure{error=it.message}
   runCatching{api.sessions(selectedProject)}.onSuccess{loaded->sessions=loaded;if(selectedSession!=null&&loaded.none{it.id==selectedSession})selectedSession=null}.onFailure{error=it.message}
  }
 }

 LaunchedEffect(Unit){
  runCatching{api.models()}.onSuccess{loaded->
   models=loaded
   if(selectedModel.isBlank()||loaded.none{it.id==selectedModel}){selectedModel=loaded.firstOrNull()?.id.orEmpty();api.selectedModelId=selectedModel}
  }.onFailure{error=it.message}
  if(api.authenticated)runCatching{api.me()}.onSuccess{user=it}.onFailure{api.logout();user=null}.also{refresh()}
 }
 LaunchedEffect(authCode){
  val code=authCode?:return@LaunchedEffect
  loading=true;error=null
  runCatching{api.exchangeMobileCode(code)}.onSuccess{user=it;screen="Chat";refresh()}.onFailure{error=it.message}
  loading=false;onAuthCodeHandled()
 }
 LaunchedEffect(selectedSession){
  val id=selectedSession?:return@LaunchedEffect
  runCatching{api.sessionMessages(id)}.onSuccess{messages=it}.onFailure{error=it.message}
 }

 Row(Modifier.fillMaxSize()){
  NavigationRail(containerColor=SolarBg){
   Text("S",color=SolarAccent,fontWeight=FontWeight.Black,style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(18.dp))
   listOf("Chat" to Icons.Default.Chat,"Projects" to Icons.Default.Folder,"Sessions" to Icons.Default.Chat,"Models" to Icons.Default.List,"Settings" to Icons.Default.Settings).forEach{(name,icon)->
    NavigationRailItem(selected=screen==name,onClick={screen=name;if(name=="Projects"||name=="Sessions")refresh()},icon={Icon(icon,contentDescription=name)},label={Text(name)})
   }
  }
  Column(Modifier.fillMaxSize().padding(16.dp)){
   when(screen){
    "Chat"->ChatScreen(api,models,projects,sessions,selectedModel,selectedProject,selectedSession,messages,input,loading,allowWrites,allowTermux,allowInternet,allowBrowser,{selectedModel=it;api.selectedModelId=it},{selectedProject=it;selectedSession=null;api.currentSessionId=null;messages=emptyList();refresh()},{selectedSession=it;api.currentSessionId=it},{input=it},{
      val question=input.trim();if(question.isBlank()||selectedModel.isBlank()||loading)Unit else {
      input="";loading=true;error=null;messages=messages+Msg("user",question)
      scope.launch{
       runCatching{api.run(question,selectedModel,selectedProject,selectedSession,mapOf("allowWrites" to allowWrites,"allowTermux" to allowTermux,"allowInternet" to allowInternet,"allowBrowser" to allowBrowser,"allowFallback" to true))}
       .onSuccess{(sessionId,answer)->if(selectedSession==null&&sessionId.isNotBlank()){selectedSession=sessionId;api.currentSessionId=sessionId};messages=messages+Msg("assistant",answer);refresh()}
       .onFailure{error=it.message?:"Solar request failed."};loading=false
      }
    }},{api.login(context)},{showCreateSession=true})
    "Projects"->ProjectsScreen(api.authenticated,projects,{api.login(context)},{showCreateProject=true})
    "Sessions"->SessionsScreen(api.authenticated,sessions,{showCreateSession=true},{selectedSession=it;api.currentSessionId=it;screen="Chat"})
    "Models"->ModelsScreen(models,selectedModel){selectedModel=it;api.selectedModelId=it}
    else->SettingsScreen(api,user,allowWrites,allowTermux,allowInternet,allowBrowser,{api.login(context)},{allowWrites=it},{allowTermux=it},{allowInternet=it},{allowBrowser=it}){scope.launch{api.logout();user=null;projects=emptyList();sessions=emptyList();messages=emptyList()}}
   }
   error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=8.dp))}
  }
 }

 if(showCreateProject)CreateProjectDialog({showCreateProject=false}){name,description,owner,repo,ref->
  scope.launch{runCatching{api.createProject(name,description,owner,repo,ref)}.onSuccess{showCreateProject=false;projects=api.projects();selectedProject=it.id;refresh()}.onFailure{error=it.message}}
 }
 if(showCreateSession)CreateSessionDialog({showCreateSession=false}){title->
  scope.launch{runCatching{api.createSession(title,selectedProject,selectedModel)}.onSuccess{showCreateSession=false;selectedSession=it.id;messages=emptyList();sessions=api.sessions(selectedProject);screen="Chat"}.onFailure{error=it.message}}
 }
}

@Composable private fun ChatScreen(api:SolarApi,models:List<Model>,projects:List<Project>,sessions:List<Session>,selectedModel:String,selectedProject:String?,selectedSession:String?,messages:List<Msg>,input:String,loading:Boolean,allowWrites:Boolean,allowTermux:Boolean,allowInternet:Boolean,allowBrowser:Boolean,onModel:(String)->Unit,onProject:(String?)->Unit,onSession:(String?)->Unit,onInput:(String)->Unit,onSend:()->Unit,onGitHub:()->Unit,onNewSession:()->Unit){
 var modelMenu by remember{mutableStateOf(false)}
 var projectMenu by remember{mutableStateOf(false)}
 var sessionMenu by remember{mutableStateOf(false)}
 Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){
  Column{Text("Solar",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(if(api.authenticated)"GitHub connected" else "Connect GitHub to use projects and saved sessions",style=MaterialTheme.typography.labelSmall)}
  Spacer(Modifier.weight(1f));Button(onClick=onGitHub){Text(if(api.authenticated)"GitHub" else "Connect GitHub")}
 }
 Spacer(Modifier.height(10.dp))
 Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){
  Box{
   OutlinedButton(onClick={modelMenu=true},enabled=models.isNotEmpty()){Text(models.firstOrNull{it.id==selectedModel}?.name?:"Select model")}
   DropdownMenu(modelMenu,{modelMenu=false}){models.forEach{model->DropdownMenuItem(text={Column{Text(model.name);Text(model.provider+" • "+model.capabilities.joinToString(", "),style=MaterialTheme.typography.labelSmall)}},onClick={onModel(model.id);modelMenu=false})}}
  }
  Box{
   OutlinedButton(onClick={projectMenu=true}){Text(projects.firstOrNull{it.id==selectedProject}?.name?:"No project")}
   DropdownMenu(projectMenu,{projectMenu=false}){DropdownMenuItem(text={Text("No project")},onClick={onProject(null);projectMenu=false});projects.forEach{p->DropdownMenuItem(text={Text(p.name)},onClick={onProject(p.id);projectMenu=false})}}
  }
  Box{
   OutlinedButton(onClick={sessionMenu=true}){Text(sessions.firstOrNull{it.id==selectedSession}?.title?:"New session")}
   DropdownMenu(sessionMenu,{sessionMenu=false}){sessions.forEach{s->DropdownMenuItem(text={Text(s.title)},onClick={onSession(s.id);sessionMenu=false})};DropdownMenuItem(text={Text("Create new session")},onClick={sessionMenu=false;onNewSession()})}
  }
 }
 LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  items(messages){message->Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(14.dp)){Text(if(message.role=="user")"You" else "Solar",fontWeight=FontWeight.Bold);Text(message.text,Modifier.padding(top=4.dp))}}}
  if(loading)item{Card(Modifier.fillMaxWidth()){Text("Solar is working…",Modifier.padding(14.dp))}}
 }
 Row(verticalAlignment=Alignment.Bottom,modifier=Modifier.fillMaxWidth()){
  OutlinedTextField(input,onInput,Modifier.weight(1f),placeholder={Text("Ask Solar to code, explain, debug…")},maxLines=6)
  Spacer(Modifier.width(8.dp));FilledIconButton(enabled=!loading&&input.isNotBlank()&&selectedModel.isNotBlank(),onClick=onSend){Icon(Icons.Default.Send,contentDescription="Send")}
 }
}

@Composable private fun ProjectsScreen(authenticated:Boolean,projects:List<Project>,onGitHub:()->Unit,onCreate:()->Unit){
 Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){Text("Projects",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));Button(onClick=onCreate,enabled=authenticated){Text("New project")}}
 Spacer(Modifier.height(12.dp))
 if(!authenticated)Card{Column(Modifier.padding(18.dp)){Text("GitHub sign-in required");Text("Connect GitHub before creating or saving Solar projects.",Modifier.padding(top=6.dp));Button(onClick=onGitHub,modifier=Modifier.padding(top=12.dp)){Text("Connect GitHub")}}}
 else if(projects.isEmpty())Card{Text("No projects yet. Create your first Solar project.",Modifier.padding(18.dp))}
 else LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){items(projects){project->Card{Column(Modifier.padding(18.dp)){Text(project.name,fontWeight=FontWeight.Bold);if(project.description.isNotBlank())Text(project.description,Modifier.padding(top=4.dp));val repo=listOfNotNull(project.githubOwner,project.githubRepo).joinToString("/");if(repo.isNotBlank())Text("GitHub: "+repo+(project.githubRef?.let{" @ "+it}?: ""),style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=8.dp))}}}}
}

@Composable private fun SessionsScreen(authenticated:Boolean,sessions:List<Session>,onNew:()->Unit,onOpen:(String)->Unit){
 Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){Text("Chat Sessions",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));Button(onClick=onNew,enabled=authenticated){Text("New session")}}
 Spacer(Modifier.height(12.dp))
 if(!authenticated)Text("Connect GitHub to persist and reopen sessions.")
 else if(sessions.isEmpty())Text("No saved sessions yet.")
 else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){items(sessions){s->Card(onClick={onOpen(s.id)}){Column(Modifier.padding(16.dp)){Text(s.title,fontWeight=FontWeight.Bold);Text(s.modelId?:"Auto model",style=MaterialTheme.typography.labelSmall)}}}}
}

@Composable private fun ModelsScreen(models:List<Model>,selected:String,onSelect:(String)->Unit){
 Text("Models",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Choose the exact backend model Solar should use.",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=4.dp));Spacer(Modifier.height(12.dp))
 LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){items(models){model->Card{Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(model.name,fontWeight=FontWeight.Bold);Text(model.id,style=MaterialTheme.typography.labelSmall);if(model.desc.isNotBlank())Text(model.desc,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=4.dp))};Button(onClick={onSelect(model.id)}){Text(if(selected==model.id)"Selected" else "Use")}}}}}
}

@Composable private fun SettingsScreen(api:SolarApi,user:AuthUser?,allowWrites:Boolean,allowTermux:Boolean,allowInternet:Boolean,allowBrowser:Boolean,onGitHub:()->Unit,onWrites:(Boolean)->Unit,onTermux:(Boolean)->Unit,onInternet:(Boolean)->Unit,onBrowser:(Boolean)->Unit,onLogout:()->Unit){
 Text("Settings",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Spacer(Modifier.height(10.dp))
 Card{Column(Modifier.padding(16.dp)){Text(if(user!=null)"Signed in as "+user.login else "Not connected to GitHub");Button(onClick=if(user==null)onGitHub else onLogout,modifier=Modifier.padding(top=10.dp)){Text(if(user==null)"Connect GitHub" else "Disconnect GitHub")}}}
 Spacer(Modifier.height(12.dp));
 PermissionRow("Allow repository writes",allowWrites,onWrites)
 PermissionRow("Allow Termux commands",allowTermux,onTermux)
 PermissionRow("Allow internet",allowInternet,onInternet)
 PermissionRow("Allow browser",allowBrowser,onBrowser)
 Spacer(Modifier.height(12.dp));Text("Backend URL",fontWeight=FontWeight.Bold);OutlinedTextField(api.baseUrl,{api.baseUrl=it},singleLine=true,modifier=Modifier.fillMaxWidth());Text("Use the deployed Solar backend URL here for a physical device.",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=4.dp))
}

@Composable private fun CreateProjectDialog(onDismiss:()->Unit,onCreate:(String,String,String?,String?,String?)->Unit){
 var name by remember{mutableStateOf("")};var description by remember{mutableStateOf("")};var owner by remember{mutableStateOf("")};var repo by remember{mutableStateOf("")};var ref by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=onDismiss,title={Text("Create project")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(name,{name=it},label={Text("Project name")},singleLine=true);OutlinedTextField(description,{description=it},label={Text("Description")},maxLines=3);OutlinedTextField(owner,{owner=it},label={Text("GitHub owner (optional)")},singleLine=true);OutlinedTextField(repo,{repo=it},label={Text("GitHub repo (optional)")},singleLine=true);OutlinedTextField(ref,{ref=it},label={Text("Branch/ref (optional)")},singleLine=true)}},confirmButton={Button(onClick={if(name.isNotBlank())onCreate(name.trim(),description.trim(),owner.trim().takeIf{it.isNotBlank()},repo.trim().takeIf{it.isNotBlank()},ref.trim().takeIf{it.isNotBlank()})},enabled=name.isNotBlank()){Text("Create")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable private fun CreateSessionDialog(onDismiss:()->Unit,onCreate:(String)->Unit){
 var title by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=onDismiss,title={Text("New chat session")},text={OutlinedTextField(title,{title=it},label={Text("Session title")},singleLine=true,placeholder={Text("e.g. Fix login bug")})},confirmButton={Button(onClick={onCreate(title.trim().ifBlank{"New chat"})}){Text("Create")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable private fun PermissionRow(title:String,checked:Boolean,onCheckedChange:(Boolean)->Unit){Card{Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f));Switch(checked,onCheckedChange)}}}
