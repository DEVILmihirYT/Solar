import crypto from "node:crypto";
import {config} from "../config.js";
const key=Buffer.from(config.TOKEN_ENCRYPTION_KEY,"hex");
export function encryptSecret(value:string){const iv=crypto.randomBytes(12);const c=crypto.createCipheriv("aes-256-gcm",key,iv);const e=Buffer.concat([c.update(value,"utf8"),c.final()]);return [iv,c.getAuthTag(),e].map(b=>b.toString("base64url")).join(".")}
export function decryptSecret(value:string){const p=value.split(".");if(p.length!==3)throw new Error("Invalid encrypted secret");const d=crypto.createDecipheriv("aes-256-gcm",key,Buffer.from(p[0],"base64url"));d.setAuthTag(Buffer.from(p[1],"base64url"));return Buffer.concat([d.update(Buffer.from(p[2],"base64url")),d.final()]).toString("utf8")}
export function randomToken(bytes=32){return crypto.randomBytes(bytes).toString("base64url")}
export function pkceChallenge(v:string){return crypto.createHash("sha256").update(v).digest("base64url")}
