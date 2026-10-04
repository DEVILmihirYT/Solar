import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { requireAuth } from "./auth.js";
import { runAgent } from "./agent.js";
import { pool } from "./db.js";

export async function registerRoutes(app:FastifyInstance){
 app.get("/api/health",async()=>({ok:true,service:"solar-backend",timestamp:new Date().toISOString()}));
 app.get("/api/github/repositories",{preHandler:requireAuth},async(req,reply)=>{
  const u=req.authUser!;
  const r=await fetch("https://api.github.com/user/repos?per_page=100&sort=updated",{headers:{Accept:"application/vnd.github+json",Authorization:"Bearer "+u.githubToken,"X-GitHub-Api-Version":"2022-11-28"}});
  if(!r.ok)return reply.code(r.status).send({error:"GitHub request failed"});
  return r.json();
 });
 app.post("/api/agent/run",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({message:z.string().min(1).max(20000),allowWrites:z.boolean().default(false)}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid request"});
  const u=req.authUser!,id=crypto.randomUUID();
  await pool.query("INSERT INTO agent_runs(id,user_id,status,model) VALUES($1,$2,'running',$3)",[id,u.id,process.env.OPENROUTER_MODEL??"configured"]);
  try{
   const result=await runAgent(u,p.data.message,p.data.allowWrites);
   await pool.query("UPDATE agent_runs SET status='completed',tool_calls=$2,finished_at=now() WHERE id=$1",[id,result.toolCalls]);
   await pool.query("INSERT INTO audit_log(user_id,action,resource,metadata) VALUES($1,$2,$3,$4)",[u.id,"agent.run",id,JSON.stringify({toolCalls:result.toolCalls})]);
   return {runId:id,...result};
  }catch(e){
   await pool.query("UPDATE agent_runs SET status='failed',finished_at=now() WHERE id=$1",[id]);
   return reply.code(500).send({error:e instanceof Error?e.message:"Agent failed",runId:id});
  }
 });
 app.post("/api/termux/commands",{preHandler:requireAuth},async(req,reply)=>{
  const p=z.object({projectId:z.string().min(1).max(200),command:z.string().min(1).max(4000),reason:z.string().min(1).max(1000),destructive:z.boolean().default(false)}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:"Invalid command request"});
  if(p.data.destructive)return reply.code(403).send({error:"Destructive Termux commands require explicit confirmation"});
  return reply.code(503).send({error:"Termux bridge unavailable"});
 });
}
