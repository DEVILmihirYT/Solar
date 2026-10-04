export type ModelCapability="chat"|"coding"|"reasoning"|"tool_calling"|"streaming";
export type ModelRecord={id:string;displayName:string;provider:string;endpoint:string;contextLength:number;capabilities:ModelCapability[];license:string;quantization?:string;enabled:boolean;priority:number;hardwareRequirements?:string};
const fallback:ModelRecord[]=[
 {id:"qwen3.5",displayName:"Qwen 3.5",provider:"self-hosted",endpoint:"",contextLength:131072,capabilities:["chat","coding","reasoning","tool_calling","streaming"],license:"Verify exact release license before production",enabled:true,priority:10,hardwareRequirements:"GPU recommended"},
 {id:"qwen3",displayName:"Qwen 3",provider:"self-hosted",endpoint:"",contextLength:131072,capabilities:["chat","coding","reasoning","tool_calling","streaming"],license:"Verify exact release license before production",enabled:true,priority:20},
 {id:"deepseek-open",displayName:"DeepSeek open-weight",provider:"self-hosted",endpoint:"",contextLength:131072,capabilities:["chat","coding","reasoning","tool_calling","streaming"],license:"Verify exact release license before production",enabled:true,priority:30},
 {id:"glm-open",displayName:"GLM open-weight",provider:"self-hosted",endpoint:"",contextLength:128000,capabilities:["chat","coding","reasoning","tool_calling","streaming"],license:"Verify exact release license before production",enabled:true,priority:40},
 {id:"dolphin",displayName:"Dolphin fine-tune",provider:"self-hosted",endpoint:"",contextLength:32768,capabilities:["chat","coding","tool_calling","streaming"],license:"Community fine-tune; verify base/model license",enabled:false,priority:50},
 {id:"nous-hermes",displayName:"Nous Hermes",provider:"self-hosted",endpoint:"",contextLength:32768,capabilities:["chat","coding","tool_calling","streaming"],license:"Verify exact release license before production",enabled:false,priority:60}
];
function parse():ModelRecord[]{try{const x=process.env.MODEL_REGISTRY_JSON;if(!x)return fallback;const a=JSON.parse(x);return Array.isArray(a)?a:fallback}catch{return fallback}}
export function publicModels(){return parse().filter(m=>m.enabled).map(({endpoint:_endpoint,...m})=>m)}
export function getModel(id:string|undefined){const models=parse();return models.find(m=>m.enabled&&m.id===id)||models.filter(m=>m.enabled).sort((a,b)=>a.priority-b.priority)[0]}
export function getFallbackModel(excluded:string){return parse().filter(m=>m.enabled&&m.id!==excluded).sort((a,b)=>a.priority-b.priority)[0]}
