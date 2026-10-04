import { chromium, type Browser, type BrowserContext, type Page } from "playwright-core";
import crypto from "node:crypto";
import { assertPublicUrl } from "./web.js";

type Session={id:string;browser:Browser;context:BrowserContext;page:Page;expiresAt:number};
const sessions=new Map<string,Session>();
const ttl=10*60_000;

async function get(id:string){const s=sessions.get(id);if(!s)throw new Error("Browser session not found");if(s.expiresAt<Date.now()){await closeBrowser(id);throw new Error("Browser session expired")}s.expiresAt=Date.now()+ttl;return s}
export async function createBrowser(url?:string){
 const executable=process.env.BROWSER_EXECUTABLE_PATH;if(!executable)throw new Error("Remote browser is not configured: set BROWSER_EXECUTABLE_PATH on the browser worker");
 const browser=await chromium.launch({headless:true,executablePath:executable,args:["--disable-dev-shm-usage","--no-sandbox"]});
 const context=await browser.newContext({acceptDownloads:false});
 const page=await context.newPage();page.setDefaultTimeout(15000);
 const id=crypto.randomUUID();sessions.set(id,{id,browser,context,page,expiresAt:Date.now()+ttl});
 if(url)await navigateBrowser(id,url);return {sessionId:id};
}
export async function navigateBrowser(id:string,url:string){const s=await get(id),u=await assertPublicUrl(url);await s.page.goto(u.toString(),{waitUntil:"domcontentloaded",timeout:15000});return {url:s.page.url(),title:await s.page.title()}}
export async function browserClick(id:string,selector:string){const s=await get(id);await s.page.locator(selector).first().click();return {url:s.page.url(),title:await s.page.title()}}
export async function browserType(id:string,selector:string,text:string){if(text.length>10000)throw new Error("Text is too long");const s=await get(id);await s.page.locator(selector).first().fill(text);return {ok:true}}
export async function browserScroll(id:string,amount=700){const s=await get(id);const n=Math.max(-5000,Math.min(5000,Math.trunc(amount)));await s.page.mouse.wheel(0,n);return {ok:true}}
export async function browserRead(id:string){const s=await get(id);return {url:s.page.url(),title:await s.page.title(),text:(await s.page.locator("body").innerText()).slice(0,200000)}}
export async function browserScreenshot(id:string){const s=await get(id);return {mime:"image/png",base64:(await s.page.screenshot({type:"png"})).toString("base64"),url:s.page.url()}}
export async function closeBrowser(id:string){const s=sessions.get(id);if(!s)return; sessions.delete(id);await s.context.close().catch(()=>{});await s.browser.close().catch(()=>{})}
export async function cleanupBrowsers(){for(const [id,s] of sessions){if(s.expiresAt<Date.now())await closeBrowser(id)}}
