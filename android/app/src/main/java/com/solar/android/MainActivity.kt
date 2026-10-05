package com.solar.android

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

private val SolarBg=Color(0xFF0B0F14); private val SolarCard=Color(0xFF151B23); private val SolarAccent=Color(0xFF8BFF6A)
data class Model(val id:String,val name:String,val desc:String)
data class Msg(val role:String,val text:String)

class SolarApi(ctx:Context){
 private val prefs=ctx.getSharedPreferences("solar",0)
 var baseUrl:String get()=prefs.getString("base","http://10.0.2.2:3000")!!.trimEnd('/') set(v){prefs.edit().putString("base",v.trimEnd('/')).apply()}
 private val cookies=object:CookieJar{private val map=mutableMapOf<String,List<Cookie>>();override fun loadForRequest(url:HttpUrl)=map[url.host].orEmpty();override fun saveFromResponse(url:HttpUrl,cookies:List<Cookie>){map[url.host]=cookies}}
 private val client=OkHttpClient.Builder().cookieJar(cookies).build()
 suspend fun models(): List<Model> =withContext(Dispatchers.IO){val r=client.newCall(Request.Builder().url("$baseUrl/api/models").build()).execute();val a=JSONObject(r.body!!.string()).getJSONArray("models");List(a.length()){val o=a.getJSONObject(it);Model(o.optString("id"),o.optString("name"),o.optString("description"))}}
 suspend fun run(message:String,model:String,flags:Map<String,Boolean>): String = withContext(Dispatchers.IO){val o=JSONObject().put("message",message).put("modelId",model).put("stream",false);flags.forEach{o.put(it.key,it.value)};val r=client.newCall(Request.Builder().url("$baseUrl/api/agent/run").post(o.toString().toRequestBody("application/json".toMediaType())).build()).execute();val body=r.body!!.string();if(!r.isSuccessful)throw Exception(JSONObject(body).optString("error",body));val j=JSONObject(body);j.optString("answer",j.optString("response",j.optString("output","No response returned.")))}
 fun login(context:Context){CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context,android.net.Uri.parse("$baseUrl/api/auth/github"))}
}
@Composable fun SolarTheme(content:@Composable()->Unit){MaterialTheme(colorScheme=darkColorScheme(background=SolarBg,surface=SolarCard,primary=SolarAccent,onPrimary=Color.Black),content=content)}
class MainActivity:ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContent{SolarTheme{SolarApp(this)}}}}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SolarApp(ctx:Context){
 val api=remember{SolarApi(ctx)};val scope=rememberCoroutineScope();var screen by remember{mutableStateOf("Chat")};var models by remember{mutableStateOf(listOf<Model>())};var selected by remember{mutableStateOf("")};var input by remember{mutableStateOf("")};var loading by remember{mutableStateOf(false)});var messages by remember{mutableStateOf(listOf<Msg>())};var error by remember{mutableStateOf<String?>(null)}
 var allowWrites by remember{mutableStateOf(false)};var allowTermux by remember{mutableStateOf(false)};var allowInternet by remember{mutableStateOf(true)};var allowBrowser by remember{mutableStateOf(false)}
 LaunchedEffect(Unit){try{models=api.models();selected=models.firstOrNull()?.id.orEmpty()}catch(e:Exception){error=e.message}}
 Row(Modifier.fillMaxSize()){NavigationRail(containerColor=SolarBg){Text("S",color=SolarAccent,fontWeight=FontWeight.Black,style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(18.dp));listOf("Chat" to Icons.Default.Chat,"Projects" to Icons.Default.Folder,"Settings" to Icons.Default.Settings).forEach{(name,icon)->NavigationRailItem(selected=screen==name,onClick={screen=name},icon={Icon(icon,null)},label={Text(name)})}}
 Column(Modifier.fillMaxSize().padding(16.dp)){
 if(screen=="Chat"){Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){Text("Solar",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));Button(onClick={api.login(ctx)}){Text("GitHub")}};Spacer(Modifier.height(10.dp));if(models.isNotEmpty()){var expanded by remember{mutableStateOf(false)};Box{OutlinedButton(onClick={expanded=true}){Text(models.find{it.id==selected}?.name?:"Choose model")};DropdownMenu(expanded,onDismissRequest={expanded=false}){models.forEach{m->DropdownMenuItem(text={Column{Text(m.name);Text(m.desc,style=MaterialTheme.typography.labelSmall)}},onClick={selected=m.id;expanded=false})}}}};LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){items(messages){m->Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp)){Text(m.text,modifier=Modifier.padding(14.dp),color=if(m.role=="user")SolarAccent else Color.White)}}};Row(verticalAlignment=Alignment.Bottom){OutlinedTextField(input,{input=it},Modifier.weight(1f),placeholder={Text("Ask Solar to code, explain, debug...")},maxLines=5);Spacer(Modifier.width(8.dp));FilledIconButton(enabled=!loading&&input.isNotBlank(),onClick={val q=input;input="";messages=messages+Msg("user",q);loading=true;scope.launch{try{val a=api.run(q,selected,mapOf("allowWrites" to allowWrites,"allowTermux" to allowTermux,"allowInternet" to allowInternet,"allowBrowser" to allowBrowser,"allowFallback" to true));messages=messages+Msg("assistant",a)}catch(e:Exception){error=e.message}finally{loading=false}}}){Icon(Icons.Default.Send,null)}}}
 else if(screen=="Projects"){Text("Projects",style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.height(12.dp));Card{Column(Modifier.padding(18.dp)){Text("GitHub workspace");Text("Connect GitHub to browse repositories and let Solar work inside an approved repo.");Spacer(Modifier.height(12.dp));Button(onClick={api.login(ctx)}){Text("Connect GitHub")}}}}
 else {Text("Agent permissions",style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.height(10.dp));listOf("Allow repository writes" to allowWrites,"Allow Termux commands" to allowTermux,"Allow internet" to allowInternet,"Allow browser" to allowBrowser).forEach{p->Card{Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Text(p.first,Modifier.weight(1f));Switch(checked=p.second,onCheckedChange={v->when(p.first){"Allow repository writes"->allowWrites=v;"Allow Termux commands"->allowTermux=v;"Allow internet"->allowInternet=v;"Allow browser"->allowBrowser=v}})}}};Spacer(Modifier.height(20.dp));OutlinedButton(onClick={api.baseUrl="http://10.0.2.2:3000"}){Text("Reset backend URL")};Text("Backend: "+api.baseUrl,style=MaterialTheme.typography.labelSmall)}
 error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=8.dp))}
 }}
