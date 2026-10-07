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
const MOBILE_OAUTH_TTL=10*60*1000;

declare module "fastify" { interface FastifyRequest { authUser?: AuthUser } }

const cookieOptions=(secure:boolean)=>({httpOnly:true,secure,sameSite:"lax" as const,path:"/"});
const hash=(value:string)=>crypto.createHash("sha256").update(value).digest("hex");

function sessionIdFromRequest(req:FastifyRequest):string|undefined{
 const auth=String(req.headers.authorization??"");
 if(auth.toLowerCase().startsWith("bearer "))return auth.slice(7).trim()||undefined;
 return req.cookies[SESSION_COOKIE]||undefined;
}

async function finishMobileRequest(requestIdHash:string,sessionId:string){
 await pool.query("UPDATE oauth_mobile_requests SET status='completed',session_id=$2,completed_at=now() WHERE id_hash=$1",[requestIdHash,sessionId]);
}

function mobileDoneHtml(ok:boolean,message:string){
 const safe=message.replace(/[&<>"]/g,m=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[m]!));
 return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Solar GitHub</title></head><body style="font-family:system-ui;background:#0b0f14;color:#fff;padding:32px;text-align:center"><h2>${ok?"Solar connected":"Solar connection failed"}</h2><p>${safe}</p><script>setTimeout(()=>window.close(),400)</script></body></html>`;
}

export async function registerAuth(app:FastifyInstance){
 const secure=config.NODE_ENV==="production";
 const requireDatabase=(reply:FastifyReply)=>{if(!config.DATABASE_URL){reply.code(503).send({error:"Database is not configured"});return false}return true};
 const requireGitHub=(reply:FastifyReply)=>{if(!config.GITHUB_CLIENT_ID||!config.GITHUB_CLIENT_SECRET){reply.code(503).send({error:"GitHub OAuth is not configured"});return false}return true};

 app.post("/api/auth/github/mobile/start",async(_req,reply)=>{
  if(!requireDatabase(reply)||!requireGitHub(reply))return;
  const state=randomToken(32),verifier=randomToken(48),requestId=randomToken(32);
  await pool.query(
   "INSERT INTO oauth_mobile_requests(id_hash,state_hash,verifier_enc,status,expires_at) VALUES($1,$2,$3,'pending',$4)",
   [hash(requestId),hash(state),encryptSecret(verifier),new Date(Date.now()+MOBILE_OAUTH_TTL)]
  );
  const p=new URLSearchParams({
   client_id:config.GITHUB_CLIENT_ID,
   redirect_uri:config.GITHUB_CALLBACK_URL,
   state,
   code_challenge:pkceChallenge(verifier),
   code_challenge_method:"S256",
   scope:"read:user repo"
  });
  return {requestId,authorizationUrl:"https://github.com/login/oauth/authorize?"+p.toString(),expiresInSeconds:MOBILE_OAUTH_TTL/1000};
 });

 app.get("/api/auth/github/mobile/status",async(req,reply)=>{
  if(!requireDatabase(reply))return;
  const requestId=String((req.query as {requestId?:string}).requestId??"");
  if(!/^[A-Za-z0-9_-]{20,200}$/.test(requestId))return reply.code(400).send({error:"Invalid request id"});
  const c=await pool.connect();
  try{
   await c.query("BEGIN");
   const q=await c.query<any>("SELECT om.status,om.error,om.session_id,om.expires_at,om.consumed_at,u.github_id,u.github_login,u.github_name,u.github_avatar_url FROM oauth_mobile_requests om LEFT JOIN sessions s ON s.id=om.session_id LEFT JOIN users u ON u.id=s.user_id WHERE om.id_hash=$1 FOR UPDATE OF om",[hash(requestId)]);
   const row=q.rows[0];
   if(!row){await c.query("COMMIT");return reply.code(404).send({error:"Login request not found"})}
   if(new Date(row.expires_at).getTime()<Date.now()&&!row.session_id){await c.query("DELETE FROM oauth_mobile_requests WHERE id_hash=$1",[hash(requestId)]);await c.query("COMMIT");return {status:"expired"}}
   if(row.status==="failed"){await c.query("DELETE FROM oauth_mobile_requests WHERE id_hash=$1",[hash(requestId)]);await c.query("COMMIT");return {status:"failed",error:row.error??"GitHub authorization failed"}}
   if(!row.session_id){await c.query("COMMIT");return {status:"pending"}}
   if(row.consumed_at){await c.query("COMMIT");return {status:"consumed"}}
   await c.query("UPDATE oauth_mobile_requests SET consumed_at=now() WHERE id_hash=$1",[hash(requestId)]);
   await c.query("COMMIT");
   return {status:"complete",token:row.session_id,user:{id:row.github_id,login:row.github_login,name:row.github_name,avatarUrl:row.github_avatar_url}};
  }catch(e){await c.query("ROLLBACK");throw e}finally{c.release()}
 });

 app.get("/api/auth/github",async(_req,reply)=>{
  if(!requireDatabase(reply)||!requireGitHub(reply))return;
  const state=randomToken(32),verifier=randomToken(48);
  reply.setCookie(OAUTH_COOKIE,JSON.stringify({state,verifier}),{...cookieOptions(secure),maxAge:600});
  const p=new URLSearchParams({client_id:config.GITHUB_CLIENT_ID,redirect_uri:config.GITHUB_CALLBACK_URL,state,code_challenge:pkceChallenge(verifier),code_challenge_method:"S256",scope:"read:user repo"});
  return reply.redirect("https://github.com/login/oauth/authorize?"+p.toString());
 });

 app.get("/api/auth/github/callback",async(req,reply)=>{
  if(!requireDatabase(reply)||!requireGitHub(reply))return;
  const q=req.query as {code?:string;state?:string};
  const state=q.state??"";
  if(!q.code||!state)return reply.code(400).send({error:"Invalid OAuth callback"});

  const mobile=await pool.query<any>(
   "SELECT id_hash,verifier_enc,expires_at,status FROM oauth_mobile_requests WHERE state_hash=$1",
   [hash(state)]
  );
  const mobileRow=mobile.rows[0];

  let verifier:string|undefined;
  let mobileRequestIdHash:string|undefined;
  if(mobileRow){
   mobileRequestIdHash=mobileRow.id_hash;
   if(mobileRow.status!=="pending"||new Date(mobileRow.expires_at).getTime()<Date.now()){
    await pool.query("UPDATE oauth_mobile_requests SET status='failed',error='Login request expired' WHERE id_hash=$1",[mobileRequestIdHash]);
    return reply.type("text/html").send(mobileDoneHtml(false,"Login request expired. Return to Solar and try again."));
   }
   verifier=decryptSecret(mobileRow.verifier_enc);
  }else{
   const raw=req.cookies[OAUTH_COOKIE];
   if(!raw)return reply.code(400).send({error:"Invalid OAuth state"});
   let o:{state:string;verifier:string};
   try{o=JSON.parse(raw)}catch{return reply.code(400).send({error:"Invalid OAuth state"})}
   if(o.state!==state)return reply.code(400).send({error:"OAuth state mismatch"});
   verifier=o.verifier;
  }

  try{
   const tr=await fetch("https://github.com/login/oauth/access_token",{method:"POST",headers:{Accept:"application/json","Content-Type":"application/json"},body:JSON.stringify({client_id:config.GITHUB_CLIENT_ID,client_secret:config.GITHUB_CLIENT_SECRET,code:q.code,redirect_uri:config.GITHUB_CALLBACK_URL,code_verifier:verifier})});
   if(!tr.ok)throw new Error("GitHub token exchange failed");
   const t=await tr.json() as any;if(!t.access_token)throw new Error(t.error??"GitHub authorization failed");

   const gr=await fetch("https://api.github.com/user",{headers:{Accept:"application/vnd.github+json",Authorization:"Bearer "+t.access_token,"X-GitHub-Api-Version":"2022-11-28"}});
   if(!gr.ok)throw new Error("GitHub identity lookup failed");
   const profile=await gr.json() as {id:number;login:string;name?:string|null;avatar_url?:string|null};

   const c=await pool.connect();
   let sid="";
   try{
    await c.query("BEGIN");
    const ur=await c.query<{id:string}>("INSERT INTO users(id,github_id,github_login,github_name,github_avatar_url) VALUES($1,$2,$3,$4,$5) ON CONFLICT(github_id) DO UPDATE SET github_login=EXCLUDED.github_login,github_name=EXCLUDED.github_name,github_avatar_url=EXCLUDED.github_avatar_url,updated_at=now() RETURNING id",[crypto.randomUUID(),String(profile.id),profile.login,profile.name??null,profile.avatar_url??null]);
    const uid=ur.rows[0]!.id;
    await c.query("INSERT INTO github_accounts(user_id,access_token_enc,refresh_token_enc,access_expires_at,refresh_expires_at,scopes) VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT(user_id) DO UPDATE SET access_token_enc=EXCLUDED.access_token_enc,refresh_token_enc=EXCLUDED.refresh_token_enc,access_expires_at=EXCLUDED.access_expires_at,refresh_expires_at=EXCLUDED.refresh_expires_at,scopes=EXCLUDED.scopes,updated_at=now()",[
     uid,
     encryptSecret(t.access_token),
     t.refresh_token?encryptSecret(t.refresh_token):null,
     t.expires_in?new Date(Date.now()+Number(t.expires_in)*1000):null,
     t.refresh_token_expires_in?new Date(Date.now()+Number(t.refresh_token_expires_in)*1000):null,
     t.scope??""
    ]);
    sid=randomToken(32);
    await c.query("INSERT INTO sessions(id,user_id,expires_at) VALUES($1,$2,$3)",[sid,uid,new Date(Date.now()+SESSION_TTL)]);
    if(mobileRequestIdHash)await c.query("UPDATE oauth_mobile_requests SET status='completed',session_id=$2,completed_at=now() WHERE id_hash=$1",[mobileRequestIdHash,sid]);
    await c.query("COMMIT");
   }catch(e){await c.query("ROLLBACK");throw e}finally{c.release()}

   reply.clearCookie(OAUTH_COOKIE,cookieOptions(secure));
   reply.setCookie(SESSION_COOKIE,sid,{...cookieOptions(secure),maxAge:SESSION_TTL/1000});
   if(mobileRequestIdHash)return reply.type("text/html").send(mobileDoneHtml(true,"GitHub is connected. You can return to the Solar app."));
   return reply.redirect(config.FRONTEND_ORIGIN);
  }catch(e){
   if(mobileRequestIdHash)await finishMobileFailure(mobileRequestIdHash,e instanceof Error?e.message:"GitHub authorization failed");
   if(mobileRequestIdHash)return reply.type("text/html").send(mobileDoneHtml(false,e instanceof Error?e.message:"GitHub authorization failed"));
   throw e;
  }
 });

 app.post("/api/auth/logout",async(req,reply)=>{
  const sid=sessionIdFromRequest(req);
  if(sid)await pool.query("DELETE FROM sessions WHERE id=$1",[sid]);
  reply.clearCookie(SESSION_COOKIE,cookieOptions(secure));
  return {ok:true};
 });

 app.get("/api/auth/me",{preHandler:requireAuth},async(req)=>{
  const u=req.authUser!;return {id:u.id,githubId:u.githubId,login:u.login,name:u.name,avatarUrl:u.avatarUrl};
 });
}

async function finishMobileFailure(requestIdHash:string,error:string){
 await pool.query("UPDATE oauth_mobile_requests SET status='failed',error=$2 WHERE id_hash=$1",[requestIdHash,error.slice(0,500)]);
}

export async function requireAuth(req:FastifyRequest,reply:FastifyReply){
 if(!config.DATABASE_URL)return void reply.code(503).send({error:"Database is not configured"});
 const sid=sessionIdFromRequest(req);
 if(!sid)return void reply.code(401).send({error:"Authentication required"});
 const q=await pool.query<any>("SELECT s.user_id,u.github_id,u.github_login,u.github_name,u.github_avatar_url,ga.access_token_enc FROM sessions s JOIN users u ON u.id=s.user_id JOIN github_accounts ga ON ga.user_id=u.id WHERE s.id=$1 AND s.expires_at>now()",[sid]);
 const row=q.rows[0];if(!row)return void reply.code(401).send({error:"Session expired"});
 req.authUser={id:row.user_id,githubId:row.github_id,login:row.github_login,name:row.github_name,avatarUrl:row.github_avatar_url,githubToken:decryptSecret(row.access_token_enc)};
}
