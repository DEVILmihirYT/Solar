import type { FastifyInstance, FastifyRequest, FastifyReply } from "fastify";
import "@fastify/cookie";
import { config } from "./config.js";
import { pool } from "./db.js";
import { decryptSecret, encryptSecret, pkceChallenge, randomToken } from "./security/crypto.js";
import type { AuthUser } from "./types.js";
import crypto from "node:crypto";

const SESSION_COOKIE="solar_session";
const OAUTH_COOKIE="solar_oauth";
const SESSION_TTL=604800000;
const MOBILE_CALLBACK="solar://oauth";

declare module "fastify" { interface FastifyRequest { authUser?: AuthUser } }

const cookieOptions=(secure:boolean)=>({httpOnly:true,secure,sameSite:"lax" as const,path:"/",signed:true});

export async function registerAuth(app:FastifyInstance){
 const secure=config.NODE_ENV==="production";
 app.get("/api/auth/github",async(req,reply)=>{
  const q=req.query as {mobile?:string};
  const state=randomToken(32),verifier=randomToken(48),mobile=q.mobile==="1";
  reply.setCookie(OAUTH_COOKIE,JSON.stringify({state,verifier,mobile}),{...cookieOptions(secure),maxAge:600});
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
   const ur=await c.query<{id:string}>(`INSERT INTO users(id,github_id,github_login,github_name,github_avatar_url) VALUES($1,$2,$3,$4,$5)
    ON CONFLICT(github_id) DO UPDATE SET github_login=EXCLUDED.github_login,github_name=EXCLUDED.github_name,github_avatar_url=EXCLUDED.github_avatar_url,updated_at=now() RETURNING id`,
    [crypto.randomUUID(),String(profile.id),profile.login,profile.name??null,profile.avatar_url??null]);
   const uid=ur.rows[0]!.id;
   await c.query(`INSERT INTO github_accounts(user_id,access_token_enc,refresh_token_enc,scopes) VALUES($1,$2,$3,$4)
    ON CONFLICT(user_id) DO UPDATE SET access_token_enc=EXCLUDED.access_token_enc,refresh_token_enc=EXCLUDED.refresh_token_enc,scopes=EXCLUDED.scopes,updated_at=now()`,
    [uid,encryptSecret(t.access_token),t.refresh_token?encryptSecret(t.refresh_token):null,t.scope??""]);
   const sid=randomToken(32);
   await c.query("INSERT INTO sessions(id,user_id,expires_at) VALUES($1,$2,$3)",[sid,uid,new Date(Date.now()+SESSION_TTL)]);
   let mobileCode:string|undefined;
   if(o.mobile){
    mobileCode=randomToken(32);
    const hash=crypto.createHash("sha256").update(mobileCode).digest("hex");
    await c.query("INSERT INTO mobile_auth_codes(code_hash,user_id,session_id,expires_at) VALUES($1,$2,$3,$4)",[hash,uid,sid,new Date(Date.now()+120000)]);
   }
   await c.query("COMMIT");
   reply.clearCookie(OAUTH_COOKIE,cookieOptions(secure));
   if(o.mobile) return reply.redirect(MOBILE_CALLBACK+"?code="+encodeURIComponent(mobileCode!));
   reply.setCookie(SESSION_COOKIE,sid,{...cookieOptions(secure),maxAge:SESSION_TTL/1000});
   return reply.redirect(config.FRONTEND_ORIGIN);
  }catch(e){await c.query("ROLLBACK");throw e}finally{c.release()}
 });
 app.post("/api/auth/mobile/exchange",async(req,reply)=>{
  const body=req.body as {code?:unknown};const code=typeof body?.code==="string"?body.code.trim():"";
  if(code.length<20||code.length>200)return reply.code(400).send({error:"Invalid mobile auth code"});
  const hash=crypto.createHash("sha256").update(code).digest("hex");
  const c=await pool.connect();
  try{
   await c.query("BEGIN");
   const q=await c.query<{user_id:string;session_id:string}>(`UPDATE mobile_auth_codes SET used_at=now()
     WHERE code_hash=$1 AND used_at IS NULL AND expires_at>now() RETURNING user_id,session_id`,[hash]);
   const row=q.rows[0];if(!row){await c.query("ROLLBACK");return reply.code(401).send({error:"Mobile auth code expired or already used"})}
   const u=await c.query<{id:string;github_id:string;github_login:string;github_name:string|null;github_avatar_url:string|null}>(
     "SELECT id,github_id,github_login,github_name,github_avatar_url FROM users WHERE id=$1",[row.user_id]);
   await c.query("COMMIT");
   const user=u.rows[0]!;return {accessToken:row.session_id,user:{id:user.id,githubId:user.github_id,login:user.github_login,name:user.github_name,avatarUrl:user.github_avatar_url}};
  }catch(e){await c.query("ROLLBACK");throw e}finally{c.release()}
 });
 app.post("/api/auth/logout",async(req,reply)=>{
  const sid=(req.headers.authorization?.startsWith("Bearer ")?req.headers.authorization.slice(7):undefined)??req.cookies[SESSION_COOKIE];
  if(sid)await pool.query("DELETE FROM sessions WHERE id=$1",[sid]);reply.clearCookie(SESSION_COOKIE,cookieOptions(secure));return {ok:true}
 });
 app.get("/api/auth/me",{preHandler:requireAuth},async(req)=>{const u=req.authUser!;return {id:u.id,githubId:u.githubId,login:u.login,name:u.name,avatarUrl:u.avatarUrl}});
}

export async function requireAuth(req:FastifyRequest,reply:FastifyReply){
 const bearer=req.headers.authorization?.startsWith("Bearer ")?req.headers.authorization.slice(7).trim():"";
 const sid=bearer||req.cookies[SESSION_COOKIE];
 if(!sid)return void reply.code(401).send({error:"Authentication required"});
 const q=await pool.query<any>(`SELECT s.user_id,u.github_id,u.github_login,u.github_name,u.github_avatar_url,ga.access_token_enc
   FROM sessions s JOIN users u ON u.id=s.user_id JOIN github_accounts ga ON ga.user_id=u.id
   WHERE s.id=$1 AND s.expires_at>now()`,[sid]);
 const row=q.rows[0];if(!row)return void reply.code(401).send({error:"Session expired"});
 req.authUser={id:row.user_id,githubId:row.github_id,login:row.github_login,name:row.github_name,avatarUrl:row.github_avatar_url,githubToken:decryptSecret(row.access_token_enc)};
}
