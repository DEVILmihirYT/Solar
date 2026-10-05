import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { requireAuth } from "./auth.js";
import { pool } from "./db.js";
import { bridgeUser, connectBridge, validateTermuxCommand } from "./termux.js";
import { listPublicModels, runAgent, type AgentEvent } from "./agent.js";

function projectCompat(row:any){
 return {...row,github_owner:row.githubOwner,github_repo:row.githubRepo,github_ref:row.githubRef};
}
function sessionCompat(row:any){
 return {...row,project_id:row.projectId,model_id:row.modelId};
}
function messageCompat(row:any){
 return {...row,model_id:row.modelId};
}

export async function registerRoutes(app:FastifyInstance){
 app.get("/api/health",async()=>({ok:true,service:"solar-backend",timestamp:new Date().toISOString()}));
 app.get("/api/models",async()=>({models:listPublicModels()}));
 app.get("/api/github/repositories",{preHandler:requireAuth},async(req,reply)=>{
  const u=req.authUser!,r=await fetch("https://api.github.com/user/repos?per_page=100&sort=updated",{headers:{Accept:"application/vnd.github+json",Authorization:"Bearer "+u.githubToken,"X-GitHub-Api-Version":"2022-11-28"}});
  if(!r.ok)return reply.code(r.status).send({error:"GitHub request failed"});return r.json();
 });

 app.get("/api/projects",{preHandler:requireAuth},async(req)=>{
  const q=await pool.query<any>("SELECT id,name,description,github_owner,github_repo,github_ref,created_at,updated_at FROM projects WHERE user_id=$1 ORDER BY updated_at DESC",[req.authUser!.id]);
  return {projects:q.rows.map(projectCompat)};
 });
 app.post("/api/projects",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({name:z.string().trim().min(1).max(120),description:z.string().max(2000).default(""),githubOwner:z.string().trim().max(100).optional().nullable(),githubRepo:z.string().trim().max(200).optional().nullable(),githubRef:z.string().trim().max(200).default("main")}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid project"});
  const id=crypto.randomUUID();
  const q=await pool.query<any>("INSERT INTO projects(id,user_id,name,description,github_owner,github_repo,github_ref) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING id,name,description,github_owner,github_repo,github_ref,created_at,updated_at",[id,req.authUser!.id,p.data.name,p.data.description,p.data.githubOwner||null,p.data.githubRepo||null,p.data.githubRef]);
  return q.rows[0];
 });
 app.patch("/api/projects/:id",{preHandler:requireAuth},async(req,reply)=>{
  const id=(req.params as {id:string}).id;
  const p=z.object({name:z.string().trim().min(1).max(120).optional(),description:z.string().max(2000).optional(),githubOwner:z.string().trim().max(100).optional().nullable(),githubRepo:z.string().trim().max(200).optional().nullable(),githubRef:z.string().trim().max(200).optional()}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid project"});
  const q=await pool.query<any>("UPDATE projects SET name=COALESCE($3,name),description=COALESCE($4,description),github_owner=$5,github_repo=$6,github_ref=COALESCE($7,github_ref),updated_at=now() WHERE id=$1 AND user_id=$2 RETURNING id,name,description,github_owner,github_repo,github_ref,created_at,updated_at",[id,req.authUser!.id,p.data.name??null,p.data.description??null,p.data.githubOwner??null,p.data.githubRepo??null,p.data.githubRef??null]);
  if(!q.rows[0])return reply.code(404).send({error:"Project not found"});
  return q.rows[0];
 });
 app.delete("/api/projects/:id",{preHandler:requireAuth},async(req,reply)=>{
  const id=(req.params as {id:string}).id;
  const q=await pool.query("DELETE FROM projects WHERE id=$1 AND user_id=$2",[id,req.authUser!.id]);
  if(q.rowCount!==1)return reply.code(404).send({error:"Project not found"});
  return {ok:true};
 });

 app.get("/api/chat/sessions",{preHandler:requireAuth},async(req)=>{
  const projectId=String((req.query as {projectId?:string}).projectId??"");
  const q=projectId
   ? await pool.query<any>("SELECT id,project_id,title,model_id,created_at,updated_at FROM chat_sessions WHERE user_id=$1 AND project_id=$2 ORDER BY updated_at DESC",[req.authUser!.id,projectId])
   : await pool.query<any>("SELECT id,project_id,title,model_id,created_at,updated_at FROM chat_sessions WHERE user_id=$1 ORDER BY updated_at DESC",[req.authUser!.id]);
  return {sessions:q.rows};
 });
 app.post("/api/chat/sessions",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({title:z.string().trim().max(160).default("New chat"),projectId:z.string().uuid().optional().nullable(),modelId:z.string().max(200).optional().nullable()}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid chat session"});
  if(p.data.projectId){
   const pq=await pool.query("SELECT 1 FROM projects WHERE id=$1 AND user_id=$2",[p.data.projectId,req.authUser!.id]);
   if(pq.rowCount!==1)return reply.code(404).send({error:"Project not found"});
  }
  const id=crypto.randomUUID();
  const q=await pool.query<any>("INSERT INTO chat_sessions(id,user_id,project_id,title,model_id) VALUES($1,$2,$3,$4,$5) RETURNING id,project_id,title,model_id,created_at,updated_at",[id,req.authUser!.id,p.data.projectId||null,p.data.title||"New chat",p.data.modelId||null]);
  return q.rows[0];
 });
 app.patch("/api/chat/sessions/:id",{preHandler:requireAuth},async(req,reply)=>{
  const id=(req.params as {id:string}).id;
  const p=z.object({title:z.string().trim().min(1).max(160).optional(),modelId:z.string().max(200).optional().nullable(),projectId:z.string().uuid().optional().nullable()}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid chat session"});
  if(p.data.projectId){
   const pq=await pool.query("SELECT 1 FROM projects WHERE id=$1 AND user_id=$2",[p.data.projectId,req.authUser!.id]);
   if(pq.rowCount!==1)return reply.code(404).send({error:"Project not found"});
  }
  const q=await pool.query<any>("UPDATE chat_sessions SET title=COALESCE($3,title),model_id=COALESCE($4,model_id),project_id=COALESCE($5,project_id),updated_at=now() WHERE id=$1 AND user_id=$2 RETURNING id,project_id,title,model_id,created_at,updated_at",[id,req.authUser!.id,p.data.title??null,p.data.modelId??null,p.data.projectId??null]);
  if(!q.rows[0])return reply.code(404).send({error:"Chat session not found"});
  return q.rows[0];
 });
 app.delete("/api/chat/sessions/:id",{preHandler:requireAuth},async(req,reply)=>{
  const id=(req.params as {id:string}).id;
  const q=await pool.query("DELETE FROM chat_sessions WHERE id=$1 AND user_id=$2",[id,req.authUser!.id]);
  if(q.rowCount!==1)return reply.code(404).send({error:"Chat session not found"});
  return {ok:true};
 });
 app.get("/api/chat/sessions/:id/messages",{preHandler:requireAuth},async(req,reply)=>{
  const id=(req.params as {id:string}).id;
  const own=await pool.query("SELECT 1 FROM chat_sessions WHERE id=$1 AND user_id=$2",[id,req.authUser!.id]);
  if(own.rowCount!==1)return reply.code(404).send({error:"Chat session not found"});
  const q=await pool.query<any>("SELECT id,role,content,model_id,created_at FROM chat_messages WHERE session_id=$1 ORDER BY created_at",[id]);
  return {messages:q.rows};
 });
 app.post("/api/agent/run",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({message:z.string().min(1).max(20000),modelId:z.string().max(200).optional(),sessionId:z.string().uuid().optional(),projectId:z.string().uuid().optional(),allowWrites:z.boolean().default(false),allowTermux:z.boolean().default(false),allowInternet:z.boolean().default(true),allowBrowser:z.boolean().default(false),allowFallback:z.boolean().default(true),stream:z.boolean().default(false)}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid request"});
  const u=req.authUser!,id=crypto.randomUUID();

  let history:Array<{role:"user"|"assistant";content:string}>=[];
  let projectContext="";
  let session:any=null;
  if(p.data.sessionId){
   const sq=await pool.query<any>("SELECT id,project_id,title,model_id FROM chat_sessions WHERE id=$1 AND user_id=$2",[p.data.sessionId,u.id]);
   session=sq.rows[0];
   if(!session)return reply.code(404).send({error:"Chat session not found"});
   if(p.data.projectId&&p.data.projectId!==session.project_id)return reply.code(400).send({error:"Project does not match chat session"});
   const h=await pool.query<any>("SELECT role,content FROM chat_messages WHERE session_id=$1 AND role IN ('user','assistant') ORDER BY created_at DESC LIMIT 40",[session.id]);
   history=h.rows.reverse();
   const projectId=p.data.projectId??session.project_id;
   if(projectId){
    const pq=await pool.query<any>("SELECT name,description,github_owner,github_repo,github_ref FROM projects WHERE id=$1 AND user_id=$2",[projectId,u.id]);
    const project=pq.rows[0];
    if(project)projectContext=["name: "+project.name,project.description?"description: "+project.description:"",project.github_owner&&project.github_repo?"GitHub: "+project.github_owner+"/"+project.github_repo+" @ "+project.github_ref:""].filter(Boolean).join("\n");
   }
  }

  const runModel=p.data.modelId??session?.model_id??undefined;
  await pool.query("INSERT INTO agent_runs(id,user_id,status,model) VALUES($1,$2,'running',$3)",[id,u.id,runModel??process.env.OPENROUTER_MODEL??"configured"]);
  if(session){
   await pool.query("INSERT INTO chat_messages(id,session_id,role,content,model_id) VALUES($1,$2,'user',$3,$4)",[crypto.randomUUID(),session.id,p.data.message,runModel??null]);
   if(session.title==="New chat"){
    const title=p.data.message.trim().replace(/\s+/g," ").slice(0,60)||"New chat";
    await pool.query("UPDATE chat_sessions SET title=$2,updated_at=now() WHERE id=$1",[session.id,title]);
   }else await pool.query("UPDATE chat_sessions SET updated_at=now() WHERE id=$1",[session.id]);
  }

  try{
   if(p.data.stream){reply.raw.setHeader("Content-Type","text/event-stream");reply.raw.setHeader("Cache-Control","no-cache");reply.raw.setHeader("Connection","keep-alive");reply.hijack()}
   const result=await runAgent(u,p.data.message,{...(runModel?{modelId:runModel}:{}),history,projectContext,allowWrites:p.data.allowWrites,allowTermux:p.data.allowTermux,allowInternet:p.data.allowInternet,allowBrowser:p.data.allowBrowser,allowFallback:p.data.allowFallback,onEvent:(e:AgentEvent)=>{if(p.data.stream)reply.raw.write("event: "+e.type+"\ndata: "+JSON.stringify(e.data??e.name??null)+"\n\n")}});
   await pool.query("UPDATE agent_runs SET status='completed',model=$2,tool_calls=$3,finished_at=now() WHERE id=$1",[id,result.modelId,result.toolCalls]);
   if(session)await pool.query("INSERT INTO chat_messages(id,session_id,role,content,model_id) VALUES($1,$2,'assistant',$3,$4)",[crypto.randomUUID(),session.id,result.message,result.modelId]);
   return {runId:id,sessionId:session?.id??null,...result};
  }catch(e){
   await pool.query("UPDATE agent_runs SET status='failed',finished_at=now() WHERE id=$1",[id]);
   return reply.code(500).send({error:e instanceof Error?e.message:"Agent failed",runId:id});
  }
 });

 app.post("/api/termux/bridge",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({name:z.string().max(100).default("Android Termux")}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid bridge request"});
  return connectBridge(req.authUser!,p.data.name);
 });
 app.post("/api/termux/bridge/heartbeat",async(req,reply)=>{
  const token=String(req.headers["x-termux-token"]??"");const bridge=await bridgeUser(token);
  if(!bridge)return reply.code(401).send({error:"Invalid Termux bridge token"});
  return {ok:true,bridgeId:bridge.bridgeId};
 });
 app.get("/api/termux/bridge/commands/next",async(req,reply)=>{
  const token=String(req.headers["x-termux-token"]??""),bridge=await bridgeUser(token);
  if(!bridge)return reply.code(401).send({error:"Invalid Termux bridge token"});
  const c=await pool.connect();
  try{
   await c.query("BEGIN");
   const q=await c.query<any>("SELECT id,project_id,command,reason,destructive FROM termux_commands WHERE user_id=$1 AND status='queued' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",[bridge.userId]);
   const row=q.rows[0];if(!row){await c.query("COMMIT");return {command:null}};
   await c.query("UPDATE termux_commands SET status='running',bridge_id=$2,started_at=now() WHERE id=$1",[row.id,bridge.bridgeId]);await c.query("COMMIT");
   return {command:row};
  }catch(e){await c.query("ROLLBACK");throw e}finally{c.release()}
 });
 app.post("/api/termux/bridge/commands/:id/result",async(req,reply)=>{
  const token=String(req.headers["x-termux-token"]??""),bridge=await bridgeUser(token);if(!bridge)return reply.code(401).send({error:"Invalid Termux bridge token"});
  const p=z.object({exitCode:z.number().int().min(-1).max(255),stdout:z.string().max(200000).default(""),stderr:z.string().max(200000).default("")}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid command result"});
  const q=await pool.query("UPDATE termux_commands SET status='completed',exit_code=$1,stdout=$2,stderr=$3,finished_at=now() WHERE id=$4 AND bridge_id=$5 AND status='running'",[p.data.exitCode,p.data.stdout,p.data.stderr,(req.params as {id:string}).id,bridge.bridgeId]);
  if(q.rowCount!==1)return reply.code(404).send({error:"Command not found or already completed"});
  return {ok:true};
 });
 app.get("/api/termux/commands/:id",{preHandler:requireAuth},async(req,reply)=>{const id=(req.params as {id:string}).id;const q=await pool.query<any>("SELECT id,project_id,command,reason,status,exit_code,stdout,stderr,created_at,started_at,finished_at FROM termux_commands WHERE id=$1 AND user_id=$2",[id,req.authUser!.id]);const row=q.rows[0];if(!row)return reply.code(404).send({error:"Command not found"});return row;});
 app.post("/api/termux/commands",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({projectId:z.string().min(1).max(200),command:z.string().min(1).max(4000),reason:z.string().min(1).max(1000),destructive:z.boolean().default(false),confirmed:z.boolean().default(false)}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid command request"});
  const check=validateTermuxCommand(p.data.command);
  if(!check.ok)return reply.code(403).send({error:check.reason});
  if(p.data.destructive&&!p.data.confirmed)return reply.code(409).send({error:"Confirmation required"});
  const bridge=await pool.query<any>("SELECT id FROM termux_bridges WHERE user_id=$1 AND last_seen_at>now()-interval '60 seconds' ORDER BY last_seen_at DESC LIMIT 1",[req.authUser!.id]);
  const row=bridge.rows[0];if(!row)return reply.code(503).send({error:"Termux bridge is not connected"});
  const id=crypto.randomUUID();
  await pool.query("INSERT INTO termux_commands(id,user_id,bridge_id,project_id,command,reason,status,destructive) VALUES($1,$2,$3,$4,$5,$6,'queued',$7)",[id,req.authUser!.id,row.id,p.data.projectId,p.data.command,p.data.reason,p.data.destructive]);
  return {commandId:id,status:"queued"};
 });
}
