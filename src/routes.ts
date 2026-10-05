import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { requireAuth } from "./auth.js";
import { pool } from "./db.js";
import { bridgeUser, connectBridge, validateTermuxCommand } from "./termux.js";
import { listPublicModels, runAgent, type AgentEvent } from "./agent.js";

export async function registerRoutes(app:FastifyInstance){
 app.get("/api/health",async()=>({ok:true,service:"solar-backend",timestamp:new Date().toISOString()}));
 app.get("/api/models",async()=>({models:listPublicModels()}));

 app.get("/api/github/repositories",{preHandler:requireAuth},async(req,reply)=>{
  const u=req.authUser!,r=await fetch("https://api.github.com/user/repos?per_page=100&sort=updated",{
   headers:{Accept:"application/vnd.github+json",Authorization:"Bearer "+u.githubToken,"X-GitHub-Api-Version":"2022-11-28"}
  });
  if(!r.ok)return reply.code(r.status).send({error:"GitHub request failed"});
  return r.json();
 });

 app.get("/api/projects",{preHandler:requireAuth},async(req)=>{
  const q=await pool.query(
   "SELECT id,name,description,github_owner AS \"githubOwner\",github_repo AS \"githubRepo\",github_ref AS \"githubRef\",created_at AS \"createdAt\",updated_at AS \"updatedAt\" FROM projects WHERE user_id=$1 ORDER BY updated_at DESC",
   [req.authUser!.id]
  );
  return {projects:q.rows};
 });

 app.post("/api/projects",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({
   name:z.string().trim().min(1).max(120),
   description:z.string().max(2000).default(""),
   githubOwner:z.string().trim().max(100).optional().nullable(),
   githubRepo:z.string().trim().max(200).optional().nullable(),
   githubRef:z.string().trim().max(200).optional().nullable()
  }).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid project"});
  if((p.data.githubOwner&&!p.data.githubRepo)||(p.data.githubRepo&&!p.data.githubOwner))return reply.code(400).send({error:"githubOwner and githubRepo must be provided together"});
  const id=crypto.randomUUID();
  const q=await pool.query(
   "INSERT INTO projects(id,user_id,name,description,github_owner,github_repo,github_ref) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING id,name,description,github_owner AS \"githubOwner\",github_repo AS \"githubRepo\",github_ref AS \"githubRef\",created_at AS \"createdAt\",updated_at AS \"updatedAt\"",
   [id,req.authUser!.id,p.data.name,p.data.description,p.data.githubOwner??null,p.data.githubRepo??null,p.data.githubRef??null]
  );
  return reply.code(201).send({project:q.rows[0]});
 });

 app.get("/api/projects/:id",{preHandler:requireAuth},async(req,reply)=>{
  const id=(req.params as {id:string}).id;
  const q=await pool.query(
   "SELECT id,name,description,github_owner AS \"githubOwner\",github_repo AS \"githubRepo\",github_ref AS \"githubRef\",created_at AS \"createdAt\",updated_at AS \"updatedAt\" FROM projects WHERE id=$1 AND user_id=$2",
   [id,req.authUser!.id]
  );
  const row=q.rows[0];
  if(!row)return reply.code(404).send({error:"Project not found"});
  return {project:row};
 });

 app.patch("/api/projects/:id",{preHandler:requireAuth},async(req,reply)=>{
  const id=(req.params as {id:string}).id;
  const p=z.object({
   name:z.string().trim().min(1).max(120).optional(),
   description:z.string().max(2000).optional(),
   githubOwner:z.string().trim().max(100).nullable().optional(),
   githubRepo:z.string().trim().max(200).nullable().optional(),
   githubRef:z.string().trim().max(200).nullable().optional()
  }).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid project update"});
  const q=await pool.query(
   "UPDATE projects SET name=COALESCE($3,name),description=COALESCE($4,description),github_owner=$5,github_repo=$6,github_ref=$7,updated_at=now() WHERE id=$1 AND user_id=$2 RETURNING id,name,description,github_owner AS \"githubOwner\",github_repo AS \"githubRepo\",github_ref AS \"githubRef\",created_at AS \"createdAt\",updated_at AS \"updatedAt\"",
   [id,req.authUser!.id,p.data.name??null,p.data.description??null,p.data.githubOwner===undefined?null:p.data.githubOwner,p.data.githubRepo===undefined?null:p.data.githubRepo,p.data.githubRef===undefined?null:p.data.githubRef]
  );
  const row=q.rows[0];
  if(!row)return reply.code(404).send({error:"Project not found"});
  return {project:row};
 });

 app.delete("/api/projects/:id",{preHandler:requireAuth},async(req,reply)=>{
  const id=(req.params as {id:string}).id;
  const q=await pool.query("DELETE FROM projects WHERE id=$1 AND user_id=$2 RETURNING id",[id,req.authUser!.id]);
  if(!q.rowCount)return reply.code(404).send({error:"Project not found"});
  return {ok:true};
 });

 app.get("/api/sessions",{preHandler:requireAuth},async(req)=>{
  const q=req.query as {projectId?:string};
  if(q.projectId){
   const r=await pool.query(
    "SELECT id,project_id AS \"projectId\",title,model_id AS \"modelId\",created_at AS \"createdAt\",updated_at AS \"updatedAt\" FROM chat_sessions WHERE user_id=$1 AND project_id=$2 ORDER BY updated_at DESC",
    [req.authUser!.id,q.projectId]
   );
   return {sessions:r.rows};
  }
  const r=await pool.query(
   "SELECT id,project_id AS \"projectId\",title,model_id AS \"modelId\",created_at AS \"createdAt\",updated_at AS \"updatedAt\" FROM chat_sessions WHERE user_id=$1 ORDER BY updated_at DESC",
   [req.authUser!.id]
  );
  return {sessions:r.rows};
 });

 app.post("/api/sessions",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({title:z.string().trim().min(1).max(160).default("New chat"),projectId:z.string().uuid().nullable().optional(),modelId:z.string().max(200).nullable().optional()}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid session"});
  if(p.data.projectId){
   const exists=await pool.query("SELECT 1 FROM projects WHERE id=$1 AND user_id=$2",[p.data.projectId,req.authUser!.id]);
   if(!exists.rowCount)return reply.code(404).send({error:"Project not found"});
  }
  const id=crypto.randomUUID();
  const q=await pool.query(
   "INSERT INTO chat_sessions(id,user_id,project_id,title,model_id) VALUES($1,$2,$3,$4,$5) RETURNING id,project_id AS \"projectId\",title,model_id AS \"modelId\",created_at AS \"createdAt\",updated_at AS \"updatedAt\"",
   [id,req.authUser!.id,p.data.projectId??null,p.data.title,p.data.modelId??null]
  );
  return reply.code(201).send({session:q.rows[0]});
 });

 app.get("/api/sessions/:id",{preHandler:requireAuth},async(req,reply)=>{
  const id=(req.params as {id:string}).id;
  const s=await pool.query(
   "SELECT id,project_id AS \"projectId\",title,model_id AS \"modelId\",created_at AS \"createdAt\",updated_at AS \"updatedAt\" FROM chat_sessions WHERE id=$1 AND user_id=$2",
   [id,req.authUser!.id]
  );
  const session=s.rows[0];
  if(!session)return reply.code(404).send({error:"Session not found"});
  const m=await pool.query(
   "SELECT id,role,content,model_id AS \"modelId\",created_at AS \"createdAt\" FROM chat_messages WHERE session_id=$1 ORDER BY created_at ASC",
   [id]
  );
  return {session,messages:m.rows};
 });

 app.delete("/api/sessions/:id",{preHandler:requireAuth},async(req,reply)=>{
  const id=(req.params as {id:string}).id;
  const q=await pool.query("DELETE FROM chat_sessions WHERE id=$1 AND user_id=$2 RETURNING id",[id,req.authUser!.id]);
  if(!q.rowCount)return reply.code(404).send({error:"Session not found"});
  return {ok:true};
 });

 app.post("/api/agent/run",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({
   message:z.string().min(1).max(20000),
   modelId:z.string().max(200).optional(),
   projectId:z.string().uuid().optional().nullable(),
   sessionId:z.string().uuid().optional().nullable(),
   allowWrites:z.boolean().default(false),
   allowTermux:z.boolean().default(false),
   allowInternet:z.boolean().default(true),
   allowBrowser:z.boolean().default(false),
   allowFallback:z.boolean().default(true),
   stream:z.boolean().default(false)
  }).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid request"});

  const u=req.authUser!;
  let sessionId=p.data.sessionId??null;
  let sessionProjectId=p.data.projectId??null;
  let modelId=p.data.modelId??null;

  if(sessionId){
   const s=await pool.query("SELECT id,project_id,model_id FROM chat_sessions WHERE id=$1 AND user_id=$2",[sessionId,u.id]);
   const row=s.rows[0];
   if(!row)return reply.code(404).send({error:"Session not found"});
   sessionProjectId=sessionProjectId??row.project_id;
   modelId=modelId??row.model_id;
  }
  if(sessionProjectId){
   const pcheck=await pool.query("SELECT 1 FROM projects WHERE id=$1 AND user_id=$2",[sessionProjectId,u.id]);
   if(!pcheck.rowCount)return reply.code(404).send({error:"Project not found"});
  }
  if(!sessionId){
   sessionId=crypto.randomUUID();
   const title=p.data.message.trim().replace(/\\s+/g," ").slice(0,80)||"New chat";
   await pool.query(
    "INSERT INTO chat_sessions(id,user_id,project_id,title,model_id) VALUES($1,$2,$3,$4,$5)",
    [sessionId,u.id,sessionProjectId,title,modelId]
   );
  }
  const prior=await pool.query<{role:"user"|"assistant";content:string}>(
   "SELECT role,content FROM chat_messages WHERE session_id=$1 AND role IN ('user','assistant') ORDER BY created_at DESC LIMIT 50",
   [sessionId]
  );
  const history=prior.rows.reverse();

  await pool.query("INSERT INTO chat_messages(id,session_id,role,content,model_id) VALUES($1,$2,'user',$3,$4)",[crypto.randomUUID(),sessionId,p.data.message,modelId]);

  const id=crypto.randomUUID();
  await pool.query("INSERT INTO agent_runs(id,user_id,status,model) VALUES($1,$2,'running',$3)",[id,u.id,modelId??process.env.OPENROUTER_MODEL??"configured"]);
  try{
   if(p.data.stream){
    reply.raw.setHeader("Content-Type","text/event-stream");
    reply.raw.setHeader("Cache-Control","no-cache");
    reply.raw.setHeader("Connection","keep-alive");
    reply.hijack();
   }
   const result=await runAgent(u,p.data.message,{
    ...(modelId?{modelId}:{}),
    allowWrites:p.data.allowWrites,
    allowTermux:p.data.allowTermux,
    allowInternet:p.data.allowInternet,
    allowBrowser:p.data.allowBrowser,
    allowFallback:p.data.allowFallback,
    history,
    onEvent:(e:AgentEvent)=>{if(p.data.stream)reply.raw.write(`event: ${e.type}\\ndata: ${JSON.stringify(e.data??e.name??null)}\\n\\n`)}
   });
   await pool.query("INSERT INTO chat_messages(id,session_id,role,content,model_id) VALUES($1,$2,'assistant',$3,$4)",[crypto.randomUUID(),sessionId,result.message,result.modelId]);
   await pool.query("UPDATE chat_sessions SET model_id=$2,updated_at=now() WHERE id=$1",[sessionId,result.modelId]);
   await pool.query("UPDATE agent_runs SET status='completed',tool_calls=$2,finished_at=now(),model=$3 WHERE id=$1",[id,result.toolCalls,result.modelId]);
   if(p.data.stream){
    reply.raw.write(`event: done\\ndata: ${JSON.stringify({sessionId,...result})}\\n\\n`);
    reply.raw.end();
    return reply;
   }
   return {runId:id,sessionId,...result};
  }catch(e){
   await pool.query("UPDATE agent_runs SET status='failed',finished_at=now() WHERE id=$1",[id]);
   if(!p.data.stream)return reply.code(500).send({error:e instanceof Error?e.message:"Agent failed",runId:id,sessionId});
   reply.raw.write(`event: error\\ndata: ${JSON.stringify({error:e instanceof Error?e.message:"Agent failed",runId:id,sessionId})}\\n\\n`);
   reply.raw.end();
   return reply;
  }
 });

 app.post("/api/termux/bridge",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({name:z.string().max(100).default("Android Termux")}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid bridge request"});
  return connectBridge(req.authUser!,p.data.name);
 });
 app.post("/api/termux/bridge/heartbeat",async(req,reply)=>{
  const token=String(req.headers["x-termux-token"]??""),bridge=await bridgeUser(token);
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
