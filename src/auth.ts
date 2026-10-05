import type { FastifyInstance, FastifyRequest, FastifyReply } from "fastify";
import "@fastify/cookie";
import crypto from "node:crypto";
import { config } from "./config.js";
import { pool } from "./db.js";
import { decryptSecret, encryptSecret, pkceChallenge, randomToken } from "./security/crypto.js";
import type { AuthUser } from "./types.js";

const SESSION_COOKIE="solar_session";
const OAUTH_COOKIE="solar_oauth";
const SESSION_TTL=604800000;
declare module "fastify" { interface FastifyRequest { authUser?: AuthUser } }
const cookieOptions=(secure:boolean)=>({httpOnly:true,secure,sameSite:"lax" as const,path:"/",signed:true});

export async function registerAuth(app:FastifyInstance){
 const secure=config.NODE_ENV==="production";
 app.get("/api/auth/github",async(req,reply)=>{
  const q=req.query as {mobile?:string},state=randomToken(32),verifier=randomToken(48);
  reply.setCookie(OAUTH_COOKIE,JSON.stringify({state,verifier,mobile:q.mobile==="1"}),{...cookieOptions(secure),maxAge:600});
  const p=new URLSearchParams({client_id:config.GITHUB_CLIENT_ID,redirect_uri:config.GITHUB_CALLBACK_URL,state,code_challenge:pkceChallenge(verifier),code_challenge_method:"S256",scope:"read:user repo"});
  return reply.redirect("https://github.com/login/oauth/authorize?"+p.toString());
 });
 app.get("/api/auth/github/callback",async(req,reply)=>{
  const q=req.query as {code?:string;state?:string},raw=req.cookies[OAUTH_COOKIE];
  if(!raw||!q.code||!q.state)return reply.code(400).send({error:"Invalid OAuth callback"});
  let o:{state:string;verifier:string;mobile?:boolean};try{o=JSON.parse(raw)}catch{return reply.code(400).send({error:"Invalid OAuth state"})}
  if(o.state!==q.state)return reply.code(400).send({error:"OAuth state mismatch"});
  const tr=await fetch("https://github.com/login/oauth/access_token",{method:"POST",headers:{Accept:"application/json","Content-Type":"application/json"},body:JSON.stringify({client_id:config.GITHUB_CLIENT_ID,client_secret:config.GITHUB_CLIENT_SECRET,code:q.code,redirect_uri:config.GITHUB_CALLBACK_URL,code_verifier:o.verifier})});
  if(!tr.ok)return reply.code(502).send({error:"GitHub token exchange failed"});
  const t=await tr.json() as any;if(!t.access_token)return reply.code(401).send({error:t.error??"GitHub authorization failed"});
  const gr=await fetch("https://api.github.com/user",{headers:{Accept:"application/vnd.github+json",Authorization:"Bearer "+t.access_token,"X-GitHub-Api-Version":"2022-11-28"}});
  if(!gr.ok)return reply.code(502).send({error:"GitHub identity lookup failed"});
  const profile=await gr.json() as {id:number;login:string;name?:string|null;avatar_url?:string|null};
  const c=await pool.connect();
  try{
   await c.query("BEGIN");
   const ur=await c.query<{id:string}>("INSERT INTO users(id,github_id,github_login,github_name,github_avatar_url) VALUES($1,$2,$3,$4,$5) ON CONFLICT(github_id) DO UPDATE SET github_login=EXCLUDED.github_login,github_name=EXCLUDED.github_name,github_avatar_url=EXCLUDED.github_avatar_url,updated_at=now() RETURNING id",[crypto.randomUUID(),String(profile.id),profile.login,profile.name??null,profile.avatar_url??null]);
   const uid=ur.rows[0]!.id;
   await c.query("INSERT INTO github_accounts(user_id,access_token_enc,refresh_token_enc,scopes) VALUES($1,$2,$3,$4) ON CONFLICT(user_id) DO UPDATE SET access_token_enc=EXCLUDED.access_token_enc,refresh_token_enc=EXCLUDED.refresh_token_enc,scopes=EXCLUDED.scopes,updated_at=now()",[uid,encryptSecret(t.access_token),t.refresh_token?encryptSecret(t.refresh_token):null,t.scope??""]);
   if(o.mobile){
    const ticket=randomToken(32);
    await c.query("INSERT INTO oauth_handoffs(id,user_id,expires_at) VALUES($1,$2,$3)",[ticket,uid,new Date(Date.now()+5*60*1000)]);
    await c.query("COMMIT");
    reply.clearCookie(OAUTH_COOKIE,cookieOptions(secure));
    return reply.redirect("solar://oauth?ticket="+encodeURIComponent(ticket));
   }
   const sid=randomToken(32);
   await c.query("INSERT INTO sessions(id,user_id,expires_at) VALUES($1,$2,$3)",[sid,uid,new Date(Date.now()+SESSION_TTL)]);
   await c.query("COMMIT");
   reply.clearCookie(OAUTH_COOKIE,cookieOptions(secure));
   reply.setCookie(SESSION_COOKIE,sid,{...cookieOptions(secure),maxAge:SESSION_TTL/1000});
   return reply.redirect(config.FRONTEND_ORIGIN);
  }catch(e){await c.query("ROLLBACK");throw e}finally{c.release()}
 });
 app.post("/api/auth/mobile/exchange",async(req,reply)=>{
  const body=req.body as {ticket?:string}|undefined,ticket=typeof body?.ticket==="string"?body.ticket:"";
  if(ticket.length<20)return reply.code(400).send({error:"Invalid OAuth ticket"});
  const c=await pool.connect();
  try{
   await c.query("BEGIN");
   const q=await c.query<any>("SELECT h.user_id,u.github_id,u.github_login,u.github_name,u.github_avatar_url FROM oauth_handoffs h JOIN users u ON u.id=h.user_id WHERE h.id=$1 AND h.expires_at>now() AND h.used_at IS NULL FOR UPDATE",[ticket]);
   const row=q.rows[0];if(!row){await c.query("ROLLBACK");return reply.code(401).send({error:"OAuth ticket expired or already used"})}
   await c.query("UPDATE oauth_handoffs SET used_at=now() WHERE id=$1",[ticket]);
   const sid=randomToken(32);
   await c.query("INSERT INTO sessions(id,user_id,expires_at) VALUES($1,$2,$3)",[sid,row.user_id,new Date(Date.now()+SESSION_TTL)]);
   await c.query("DELETE FROM oauth_handoffs WHERE expires_at<now()",[]);
   await c.query("COMMIT");
   return {sessionId:sid,user:{id:row.user_id,githubId:row.github_id,login:row.github_login,name:row.github_name,avatarUrl:row.github_avatar_url}};
  }catch(e){await c.query("ROLLBACK");throw e}finally{c.release()}
 });
 app.post("/api/auth/logout",async(req,reply)=>{const sid=req.cookies[SESSION_COOKIE]??String(req.headers["x-solar-session"]??"");if(sid)await pool.query("DELETE FROM sessions WHERE id=$1",[sid]);reply.clearCookie(SESSION_COOKIE,cookieOptions(secure));return {ok:true}});
 app.get("/api/auth/me",{preHandler:requireAuth},async(req)=>{const u=req.authUser!;return {id:u.id,githubId:u.githubId,login:u.login,name:u.name,avatarUrl:u.avatarUrl}});
}
export async function requireAuth(req:FastifyRequest,reply:FastifyReply){
 const sid=req.cookies[SESSION_COOKIE]??String(req.headers["x-solar-session"]??"");if(!sid)return void reply.code(401).send({error:"Authentication required"});
 const q=await pool.query<any>("SELECT s.user_id,u.github_id,u.github_login,u.github_name,u.github_avatar_url,ga.access_token_enc FROM sessions s JOIN users u ON u.id=s.user_id JOIN github_accounts ga ON ga.user_id=u.id WHERE s.id=$1 AND s.expires_at>now()",[sid]);
 const row=q.rows[0];if(!row)return void reply.code(401).send({error:"Session expired"});
 req.authUser={id:row.user_id,githubId:row.github_id,login:row.github_login,name:row.github_name,avatarUrl:row.github_avatar_url,githubToken:decryptSecret(row.access_token_enc)};
}