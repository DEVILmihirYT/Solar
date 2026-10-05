export type ModelCapability="chat"|"coding"|"reasoning"|"tool_calling"|"streaming";
export type ModelRecord={id:string;displayName:string;provider:string;endpoint:string;contextLength:number;capabilities:ModelCapability[];license:string;quantization?:string;enabled:boolean;priority:number;hardwareRequirements?:string};

const defaults:ModelRecord[]=[
 {id:"openrouter/free",displayName:"OpenRouter Free",provider:"openrouter",endpoint:"https://openrouter.ai/api/v1",contextLength:32768,capabilities:["chat","coding","reasoning","tool_calling","streaming"],license:"Provider/model dependent; verify before production",enabled:true,priority:1},
 {id:"qwen/qwen3.5-397b-a17b",displayName:"Qwen 3.5 397B A17B",provider:"openrouter",endpoint:"https://openrouter.ai/api/v1",contextLength:262144,capabilities:["chat","coding","reasoning","tool_calling","streaming"],license:"Open-weight model; verify provider terms",enabled:true,priority:10},
 {id:"qwen/qwen3-30b-a3b",displayName:"Qwen 3 30B A3B",provider:"openrouter",endpoint:"https://openrouter.ai/api/v1",contextLength:131072,capabilities:["chat","coding","reasoning","tool_calling","streaming"],license:"Open-weight model; verify provider terms",enabled:true,priority:20},
 {id:"deepseek/deepseek-v3.2",displayName:"DeepSeek V3.2",provider:"openrouter",endpoint:"https://openrouter.ai/api/v1",contextLength:163840,capabilities:["chat","coding","reasoning","tool_calling","streaming"],license:"Provider/model dependent; verify before production",enabled:true,priority:30},
 {id:"z-ai/glm-5",displayName:"GLM 5",provider:"openrouter",endpoint:"https://openrouter.ai/api/v1",contextLength:204800,capabilities:["chat","coding","reasoning","tool_calling","streaming"],license:"Open-weight model; verify provider terms",enabled:true,priority:40},
 {id:"cognitivecomputations/dolphin3.0-r1-mistral-24b",displayName:"Dolphin 3.0 R1 Mistral 24B",provider:"openrouter",endpoint:"https://openrouter.ai/api/v1",contextLength:32768,capabilities:["chat","coding","reasoning","tool_calling","streaming"],license:"Community fine-tune; verify model license",enabled:true,priority:50},
 {id:"nousresearch/hermes-4-70b",displayName:"Hermes 4 70B",provider:"openrouter",endpoint:"https://openrouter.ai/api/v1",contextLength:131072,capabilities:["chat","coding","reasoning","tool_calling","streaming"],license:"Model-specific license; verify before production",enabled:true,priority:60}
];

function parse():ModelRecord[]{try{const raw=process.env.MODEL_REGISTRY_JSON;if(!raw)return defaults;const parsed=JSON.parse(raw);if(!Array.isArray(parsed))throw new Error("registry must be an array");return parsed as ModelRecord[]}catch(e){throw new Error("Invalid MODEL_REGISTRY_JSON: "+(e instanceof Error?e.message:"parse error"))}}

export function publicModels(){return parse().filter(m=>m.enabled).map(({endpoint:_endpoint,...m})=>m)}
export function getModel(id?:string){const models=parse(),selected=id?models.find(m=>m.enabled&&m.id===id):undefined;if(id&&!selected)throw new Error("Selected model is not enabled");return selected??models.filter(m=>m.enabled).sort((a,b)=>a.priority-b.priority)[0]}
export function getFallbackModel(excluded:string){return parse().filter(m=>m.enabled&&m.id!==excluded).sort((a,b)=>a.priority-b.priority)[0]}
