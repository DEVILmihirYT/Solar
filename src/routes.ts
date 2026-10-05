import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { requireAuth } from "./auth.js";
import { pool } from "./db.js";
import { bridgeUser, connectBridge, validateTermuxCommand } from "./termux.js";
import { listPublicModels, runAgent, type AgentEvent } from "./agent.js";

const projectId=(req:any)=>String(req.params?.id??"");
const sessionId=(req:any)=>String(req.params?.id??"");

export async function registerRoutes(app:FastifyInstance){
 app.get("/api/health",async()=>({ok:true,service:"solar-backend",timestamp:new Date().toISOString()}));
 app.get("/api/models",async()=>({models:listPublicModels()}));

 app.get("/api/authenticated", {preHandler:requireAuth}, async()=>({ok:true}));

 app.get("/api/github/repositories",{preHandler:requireAuth},async(req,reply)=>{
  const u=req.authUser!,r=await fetch("https://api.github.com/user/repos?per_page=100&sort=updated",{headers:{Accept:"application/vnd.github+json",Authorization:"Bearer "+u.githubToken,"X-GitHub-Api-Version":"2022-11-28"}});
  if(!r.ok)return reply.code(r.status).send({error:"GitHub request failed"});return r.json();
 });

 app.get("/api/projects",{preHandler:requireAuth},async(req)=>{
  const q=await pool.query(`SELECT id,name,description,repo_owner,repo_name,branch,created_at,updated_at
    FROM projects WHERE user_id=$1 ORDER BY updated_at DESC`,[req.authUser!.id]);
  return {projects:q.rows};
 });
 app.post("/api/projects",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({name:z.string().trim().min(1).max(120),description:z.string().max(1000).default(""),repoOwner:z.string().trim().max(100).optional(),repoName:z.string().trim().max(200).optional(),branch:z.string().trim().max(200).default("main")}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid project"});
  const q=await pool.query(`INSERT INTO projects(id,user_id,name,description,repo_owner,repo_name,branch)
    VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING id,name,description,repo_owner,repo_name,branch,created_at,updated_at`,
    [crypto.randomUUID(),req.authUser!.id,p.data.name,p.data.description,p.data.repoOwner??null,p.data.repoName??null,p.data.branch]);
  return reply.code(201).send({project:q.rows[0]});
 });
 app.delete("/api/projects/:id",{preHandler:requireAuth},async(req,reply)=>{
  const q=await pool.query("DELETE FROM projects WHERE id=$1 AND user_id=$2 RETURNING id",[projectId(req),req.authUser!.id]);
  if(!q.rowCount)return reply.code(404).send({error:"Project not found"});return {ok:true};
 });

 app.get("/api/sessions",{preHandler:requireAuth},async(req)=>{
  const q=await pool.query(`SELECT s.id,s.title,s.model_id,s.project_id,s.created_at,s.updated_at,p.name AS project_name
    FROM chat_sessions s LEFT JOIN projects p ON p.id=s.project_id
    WHERE s.user_id=$1 ORDER BY s.updated_at DESC LIMIT 100`,[req.authUser!.id]);
  return {sessions:q.rows};
 });
 app.post("/api/sessions",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({title:z.string().trim().min(1).max(160).default("New chat"),projectId:z.string().uuid().nullable().optional(),modelId:z.string().max(200).nullable().optional()}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid session"});
  if(p.data.projectId){
   const own=await pool.query("SELECT id FROM projects WHERE id=$1 AND user_id=$2",[p.data.projectId,req.authUser!.id]);
   if(!own.rowCount)return reply.code(404).send({error:"Project not found"});
  }
  const q=await pool.query(`INSERT INTO chat_sessions(id,user_id,project_id,title,model_id)
    VALUES($1,$2,$3,$4,$5) RETURNING id,title,model_id,project_id,created_at,updated_at`,
    [crypto.randomUUID(),req.authUser!.id,p.data.projectId??null,p.data.title,p.data.modelId??null]);
  return reply.code(201).send({session:q.rows[0]});
 });
 app.delete("/api/sessions/:id",{preHandler:requireAuth},async(req,reply)=>{
  const q=await pool.query("DELETE FROM chat_sessions WHERE id=$1 AND user_id=$2 RETURNING id",[sessionId(req),req.authUser!.id]);
  if(!q.rowCount)return reply.code(404).send({error:"Session not found"});return {ok:true};
 });
 app.get("/api/projects/:id/sessions",{preHandler:requireAuth},async(req,reply)=>{
  const pid=projectId(req);
  const own=await pool.query("SELECT id FROM projects WHERE id=$1 AND user_id=$2",[pid,req.authUser!.id]);
  if(!own.rowCount)return reply.code(404).send({error:"Project not found"});
  const q=await pool.query(`SELECT id,title,model_id,project_id,created_at,updated_at FROM chat_sessions
    WHERE project_id=$1 AND user_id=$2 ORDER BY updated_at DESC LIMIT 100`,[pid,req.authUser!.id]);
  return {sessions:q.rows};
 });
 app.get("/api/sessions/:id/messages",{preHandler:requireAuth},async(req,reply)=>{
  const q=await pool.query(`SELECT m.id,m.role,m.content,m.model_id,m.created_at
    FROM chat_messages m JOIN chat_sessions s ON s.id=m.session_id
    WHERE m.session_id=$1 AND s.user_id=$2 ORDER BY m.created_at`,[sessionId(req),req.authUser!.id]);
  const exists=await pool.query("SELECT id FROM chat_sessions WHERE id=$1 AND user_id=$2",[sessionId(req),req.authUser!.id]);
  if(!exists.rowCount)return reply.code(404).send({error:"Session not found"});
  return {messages:q.rows};
 });
 app.post("/api/sessions/:id/messages",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({message:z.string().trim().min(1).max(20000),modelId:z.string().max(200).optional(),allowWrites:z.boolean().default(false),allowTermux:z.boolean().default(false),allowInternet:z.boolean().default(true),allowBrowser:z.boolean().default(false),allowFallback:z.boolean().default(true),stream:z.boolean().default(false)}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid message"});
  const sid=sessionId(req),u=req.authUser!;
  const meta=await pool.query<any>(`SELECT s.id,s.project_id,s.model_id,p.name,p.repo_owner,p.repo_name,p.branch
    FROM chat_sessions s LEFT JOIN projects p ON p.id=s.project_id
    WHERE s.id=$1 AND s.user_id=$2`,[sid,u.id]);
  const session=meta.rows[0];if(!session)return reply.code(404).send({error:"Session not found"});
  const selectedModel=p.data.modelId??session.model_id??undefined;
  await pool.query("INSERT INTO chat_messages(session_id,role,content,model_id) VALUES($1,'user',$2,$3)",[sid,p.data.message,selectedModel??null]);
  await pool.query("UPDATE chat_sessions SET model_id=COALESCE($2,model_id),updated_at=now() WHERE id=$1",[sid,selectedModel??null]);

  const projectContext=session.project_id?[
    `Project: ${session.name??"Unnamed"}`,
    session.repo_owner&&session.repo_name?`GitHub repository: ${session.repo_owner}/${session.repo_name} (branch: ${session.branch??"main"})`:"No GitHub repository linked."
  ].join("\n"):"No project is selected for this chat.";
  try{
   const result=await runAgent(u,p.data.message,{...(selectedModel?{modelId:selectedModel}:{}),allowWrites:p.data.allowWrites,allowTermux:p.data.allowTermux,allowInternet:p.data.allowInternet,allowBrowser:p.data.allowBrowser,allowFallback:p.data.allowFallback,projectContext});
   await pool.query("INSERT INTO chat_messages(session_id,role,content,model_id) VALUES($1,'assistant',$2,$3)",[sid,result.message,result.modelId]);
   await pool.query("UPDATE chat_sessions SET updated_at=now() WHERE id=$1",[sid]);
   return {sessionId:sid,...result};
  }catch(e){
   return reply.code(500).send({error:e instanceof Error?e.message:"Agent failed",sessionId:sid});
  }
 });

 app.post("/api/agent/run",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({message:z.string().min(1).max(20000),modelId:z.string().max(200).optional(),allowWrites:z.boolean().default(false),allowTermux:z.boolean().default(false),allowInternet:z.boolean().default(true),allowBrowser:z.boolean().default(false),allowFallback:z.boolean().default(true),stream:z.boolean().default(false)}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid request"});
  const u=req.authUser!,id=crypto.randomUUID();await pool.query("INSERT INTO agent_runs(id,user_id,status,model) VALUES($1,$2,'running',$3)",[id,u.id,p.data.modelId??process.env.OPENROUTER_MODEL??"configured"]);
  try{
   if(p.data.stream){reply.raw.setHeader("Content-Type","text/event-stream");reply.raw.setHeader("Cache-Control","no-cache");reply.raw.setHeader("Connection","keep-alive");reply.hijack()}
   const result=await runAgent(u,p.data.message,{...(p.data.modelId?{modelId:p.data.modelId}:{}),allowWrites:p.data.allowWrites,allowTermux:p.data.allowTermux,allowInternet:p.data.allowInternet,allowBrowser:p.data.allowBrowser,allowFallback:p.data.allowFallback,onEvent:(e:AgentEvent)=>{if(p.data.stream)reply.raw.write(`event: ${e.type}\ndata: ${JSON.stringify(e.data??e.name??null)}\n\n`)}});
   await pool.query("UPDATE agent_runs SET status='completed',tool_calls=$2,finished_at=now() WHERE id=$1",[id,result.toolCalls]);return {runId:id,...result}
  }catch(e){await pool.query("UPDATE agent_runs SET status='failed',finished_at=now() WHERE id=$1",[id]);return reply.code(500).send({error:e instanceof Error?e.message:"Agent failed",runId:id})}
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