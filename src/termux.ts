import crypto from "node:crypto";
import type { AuthUser } from "./types.js";
import { pool } from "./db.js";

export function hashBridgeToken(token:string){return crypto.createHash("sha256").update(token).digest("hex")}
export function createBridgeToken(){return crypto.randomBytes(32).toString("base64url")}

const forbiddenWords=/\b(rm|mv|cp|chmod|chown|dd|mkfs|shutdown|reboot|kill|pkill|su|sudo|termux-reset|apt|pkg|curl|wget)\b/i;
const allowedPrefixes=["pwd","ls","git status","git diff","./gradlew build","./gradlew test","./gradlew check","gradle build","gradle test","npm test","npm run build","npm run typecheck","pnpm test","pnpm build","python -m pytest","pytest","cargo test","cargo build","mvn test","mvn package"];

export function validateTermuxCommand(command:string){
 const c=command.trim();
 if(!c||c.length>4000)return {ok:false,reason:"Command is empty or too long"};
 if(forbiddenWords.test(c)||c.includes(";")||c.includes("&&")||c.includes("||")||c.includes("|")||c.includes(">")||c.includes("<")||c.includes("$(")||c.includes("\n")||c.includes("\r"))return {ok:false,reason:"Command contains a forbidden or destructive operation"};
 if(c.includes("..")||c.startsWith("/")||c.includes("/etc/")||c.includes("/data/"))return {ok:false,reason:"Command path escapes the selected project workspace"};
 if(!allowedPrefixes.some(p=>c===p||c.startsWith(p+" ")))return {ok:false,reason:"Command is not in the safe development allowlist"};
 return {ok:true,reason:"Safe development command"};
}

export async function connectBridge(user:AuthUser,name:string){
 const token=createBridgeToken(),id=crypto.randomUUID();
 await pool.query("INSERT INTO termux_bridges(id,user_id,name,token_hash) VALUES($1,$2,$3,$4)",[id,user.id,name.trim().slice(0,100)||"Android Termux",hashBridgeToken(token)]);
 return {bridgeId:id,token};
}

export async function bridgeUser(token:string){
 const r=await pool.query<any>("SELECT id,user_id FROM termux_bridges WHERE token_hash=$1",[hashBridgeToken(token)]);
 const row=r.rows[0];if(!row)return null;
 await pool.query("UPDATE termux_bridges SET connected_at=now(),last_seen_at=now() WHERE id=$1",[row.id]);
 return {bridgeId:row.id,userId:row.user_id};
}

export async function queueTermuxCommand(user:AuthUser,projectId:string,command:string,reason:string){
 const check=validateTermuxCommand(command);if(!check.ok)throw new Error(check.reason);
 const bridge=await pool.query<any>("SELECT id FROM termux_bridges WHERE user_id=$1 AND last_seen_at>now()-interval '60 seconds' ORDER BY last_seen_at DESC LIMIT 1",[user.id]);
 const row=bridge.rows[0];if(!row)throw new Error("Termux bridge is not connected");
 const id=crypto.randomUUID();
 await pool.query("INSERT INTO termux_commands(id,user_id,bridge_id,project_id,command,reason,status,destructive) VALUES($1,$2,$3,$4,$5,$6,'queued',false)",[id,user.id,row.id,projectId,command,reason]);
 return {commandId:id,status:"queued"};
}
