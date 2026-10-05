import Fastify from "fastify";
import cookie from "@fastify/cookie";
import cors from "@fastify/cors";
import helmet from "@fastify/helmet";
import rateLimit from "@fastify/rate-limit";
import { config } from "./config.js";
import { migrate, pool } from "./db.js";
import { registerAuth } from "./auth.js";
import { registerRoutes } from "./routes.js";

const app=Fastify({logger:true,bodyLimit:1048576});
await app.register(helmet);
await app.register(cookie,{secret:config.SESSION_SECRET});
const allowedOrigins=new Set([config.FRONTEND_ORIGIN,"https://appassets.androidplatform.net","http://localhost","http://127.0.0.1"]);\nawait app.register(cors,{origin:(origin,cb)=>{if(!origin||origin==="null"||allowedOrigins.has(origin))cb(null,true);else cb(null,false)},credentials:true});
await app.register(rateLimit,{max:120,timeWindow:"1 minute"});
await migrate();
await registerAuth(app);
await registerRoutes(app);
app.setErrorHandler((error,_req,reply)=>{
 app.log.error(error);
 const status=typeof error==="object"&&error!==null&&"statusCode" in error&&typeof (error as {statusCode?:unknown}).statusCode==="number" ? (error as {statusCode:number}).statusCode : 500;
 reply.code(status>=400?status:500).send({error:"Internal server error"});
});
const shutdown=async()=>{await app.close();await pool.end();process.exit(0)};
process.on("SIGTERM",shutdown);process.on("SIGINT",shutdown);
await app.listen({port:config.PORT,host:"0.0.0.0"});
