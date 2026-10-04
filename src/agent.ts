import { config } from "./config.js";
import { githubTools } from "./github.js";
import type { AuthUser } from "./types.js";

const definitions=[
 {name:"github_list_repositories",description:"List accessible repositories.",parameters:{type:"object",properties:{},additionalProperties:false}},
 {name:"github_get_repository",description:"Get repository metadata.",parameters:{type:"object",properties:{owner:{type:"string"},repo:{type:"string"}},required:["owner","repo"],additionalProperties:false}},
 {name:"github_list_files",description:"List a repository path.",parameters:{type:"object",properties:{owner:{type:"string"},repo:{type:"string"},path:{type:"string"},ref:{type:"string"}},required:["owner","repo"],additionalProperties:false}},
 {name:"github_read_file",description:"Read a repository text file.",parameters:{type:"object",properties:{owner:{type:"string"},repo:{type:"string"},path:{type:"string"},ref:{type:"string"}},required:["owner","repo","path"],additionalProperties:false}},
 {name:"github_create_file",description:"Create a file. Requires explicit write permission.",parameters:{type:"object",properties:{owner:{type:"string"},repo:{type:"string"},path:{type:"string"},content:{type:"string"},message:{type:"string"},branch:{type:"string"}},required:["owner","repo","path","content","message"],additionalProperties:false}},
 {name:"github_update_file",description:"Update a file with its current SHA. Requires explicit write permission.",parameters:{type:"object",properties:{owner:{type:"string"},repo:{type:"string"},path:{type:"string"},content:{type:"string"},message:{type:"string"},sha:{type:"string"},branch:{type:"string"}},required:["owner","repo","path","content","message","sha"],additionalProperties:false}},
 {name:"github_delete_file",description:"Delete a file. Requires explicit write permission.",parameters:{type:"object",properties:{owner:{type:"string"},repo:{type:"string"},path:{type:"string"},message:{type:"string"},sha:{type:"string"},branch:{type:"string"}},required:["owner","repo","path","message","sha"],additionalProperties:false}},
 {name:"github_create_branch",description:"Create a branch. Requires explicit write permission.",parameters:{type:"object",properties:{owner:{type:"string"},repo:{type:"string"},branch:{type:"string"},from:{type:"string"}},required:["owner","repo","branch"],additionalProperties:false}},
 {name:"github_create_pull_request",description:"Create a pull request. Requires explicit write permission.",parameters:{type:"object",properties:{owner:{type:"string"},repo:{type:"string"},title:{type:"string"},head:{type:"string"},base:{type:"string"},body:{type:"string"}},required:["owner","repo","title","head","base"],additionalProperties:false}},
 {name:"github_get_actions_runs",description:"Inspect recent GitHub Actions runs.",parameters:{type:"object",properties:{owner:{type:"string"},repo:{type:"string"},branch:{type:"string"}},required:["owner","repo"],additionalProperties:false}}
];

const writes=new Set(["github_create_file","github_update_file","github_delete_file","github_create_branch","github_create_pull_request"]);

async function execute(u:AuthUser,name:string,a:any,allowWrites:boolean){
 if(writes.has(name)&&!allowWrites)throw new Error("Permission denied: explicit write confirmation is required.");
 switch(name){
  case "github_list_repositories":return githubTools.list_repositories(u);
  case "github_get_repository":return githubTools.get_repository(u,a.owner,a.repo);
  case "github_list_files":return githubTools.list_files(u,a.owner,a.repo,a.path??"",a.ref);
  case "github_read_file":return githubTools.read_file(u,a.owner,a.repo,a.path,a.ref);
  case "github_create_file":return githubTools.create_file(u,a.owner,a.repo,a.path,a.content,a.message,a.branch);
  case "github_update_file":return githubTools.update_file(u,a.owner,a.repo,a.path,a.content,a.message,a.sha,a.branch);
  case "github_delete_file":return githubTools.delete_file(u,a.owner,a.repo,a.path,a.message,a.sha,a.branch);
  case "github_create_branch":return githubTools.create_branch(u,a.owner,a.repo,a.branch,a.from??"main");
  case "github_create_pull_request":return githubTools.create_pull_request(u,a.owner,a.repo,a.title,a.head,a.base,a.body??"");
  case "github_get_actions_runs":return githubTools.list_actions_runs(u,a.owner,a.repo,a.branch);
  default:throw new Error("Unknown tool: "+name);
 }
}

async function callModel(messages:any[]){
 const r=await fetch("https://openrouter.ai/api/v1/chat/completions",{method:"POST",headers:{"Authorization":"Bearer "+config.OPENROUTER_API_KEY,"Content-Type":"application/json","X-Title":"Solar AI Coding Agent"},body:JSON.stringify({model:config.OPENROUTER_MODEL,messages,tools:definitions.map(x=>({type:"function",function:x})),tool_choice:"auto",temperature:0.2})});
 const b=await r.json() as any;if(!r.ok)throw new Error("AI provider "+r.status+": "+(b?.error?.message??"request failed"));return b;
}

export async function runAgent(u:AuthUser,input:string,allowWrites:boolean){
 const messages:any[]=[
  {role:"system",content:"You are Solar, a production coding agent. Inspect before modifying. Use tools instead of guessing. Never claim an operation succeeded without a successful tool result. Respect permissions and preserve unrelated work."},
  {role:"user",content:input}
 ];
 let calls=0;const deadline=Date.now()+config.MAX_EXECUTION_TIME_MS;
 while(Date.now()<deadline&&calls<config.MAX_TOOL_CALLS){
  const result=await callModel(messages);const choice=result.choices?.[0]?.message;if(!choice)throw new Error("AI provider returned no message");messages.push(choice);
  if(!choice.tool_calls?.length)return {message:String(choice.content??""),toolCalls:calls};
  for(const call of choice.tool_calls){
   calls++;if(calls>config.MAX_TOOL_CALLS)break;
   let args:any;try{args=JSON.parse(call.function.arguments||"{}")}catch{throw new Error("AI returned invalid tool arguments")};
   let out:any;try{out=await execute(u,call.function.name,args,allowWrites)}catch(e){out={error:e instanceof Error?e.message:"Tool failed"}}
   messages.push({role:"tool",tool_call_id:call.id,content:JSON.stringify(out).slice(0,100000)});
  }
 }
 throw new Error("Agent execution limit reached");
}
