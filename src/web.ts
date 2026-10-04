import dns from "node:dns/promises";
import net from "node:net";
import { URL } from "node:url";

function privateIp(ip:string){
 if(net.isIP(ip)===4){const p=ip.split(".").map(Number);const [a,b]=p;return a===10||a===127||a===0||a===169&&b===254||a===172&&b>=16&&b<=31||a===192&&b===168}
 if(net.isIP(ip)===6){const x=ip.toLowerCase();return x==="::1"||x==="::"||x.startsWith("fc")||x.startsWith("fd")||x.startsWith("fe80:")}
 return true;
}
export async function assertPublicUrl(input:string){
 const u=new URL(input);if(!["http:","https:"].includes(u.protocol))throw new Error("Only HTTP(S) URLs are allowed");
 if(u.username||u.password)throw new Error("Credentials in URLs are not allowed");
 if(privateIp(u.hostname))throw new Error("Private or local network targets are blocked");
 const records=await dns.lookup(u.hostname,{all:true});if(!records.length)throw new Error("Host could not be resolved");
 if(records.some(r=>privateIp(r.address)))throw new Error("Resolved host points to a private network");
 return u;
}
export async function fetchWeb(url:string){
 const u=await assertPublicUrl(url);const c=new AbortController();const t=setTimeout(()=>c.abort(),15000);
 try{const r=await fetch(u,{redirect:"manual",signal:c.signal,headers:{"User-Agent":"SolarBot/1.0 (+web-agent)"}});if(r.status>=300&&r.status<400){const loc=r.headers.get("location");if(!loc)throw new Error("Redirect without location");const next=new URL(loc,u);return {redirect:next.toString(),status:r.status}}
 if(!r.ok)throw new Error("Web fetch "+r.status);const type=r.headers.get("content-type")??"";const body=await r.text();return {url:u.toString(),status:r.status,contentType:type,content:body.slice(0,500000)}
 }finally{clearTimeout(t)}
}
export async function webSearch(query:string){
 const q=query.trim();if(!q||q.length>500)throw new Error("Invalid search query");
 const url="https://html.duckduckgo.com/html/?q="+encodeURIComponent(q);
 const x=await fetch(url,{headers:{"User-Agent":"SolarBot/1.0 (+web-search)"}});
 if(!x.ok)throw new Error("Web search "+x.status);const html=await x.text();
 const results:string[]=[];const re=/<a[^>]+class=["'][^"']*result__a[^"']*["'][^>]+href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;let m:RegExpExecArray|null;
 while((m=re.exec(html))&&results.length<10){const href=m[1],rawTitle=m[2];if(!href||rawTitle===undefined)continue;const title=rawTitle.replace(/<[^>]+>/g,"").replace(/&amp;/g,"&").trim();results.push(JSON.stringify({title,url:href}))}
 return results.map(s=>JSON.parse(s));
}
