import test from "node:test";
import assert from "node:assert/strict";
import { getModel, getFallbackModel, publicModels } from "./models.js";

test("public model metadata never exposes private endpoint",()=>{
 const models=publicModels();assert.ok(models.length>0);for(const m of models)assert.equal("endpoint" in m,false);
});
test("default model is available and fallback is distinct",()=>{
 const m=getModel();assert.ok(m);const f=getFallbackModel(m!.id);if(f)assert.notEqual(f.id,m!.id);
});
test("explicit unavailable model is rejected",()=>assert.throws(()=>getModel("not-enabled"),/not enabled/));
