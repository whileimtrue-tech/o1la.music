// Usage: WS_URL=wss://deployment.vercel.app/api/ws node scripts/smoke-test.mjs
import { WebSocket } from "ws";
const url=process.env.WS_URL;if(!url)throw new Error("Set WS_URL to the deployed wss endpoint");
const room="SMOKE_"+Math.random().toString(36).slice(2,9),a=new WebSocket(url),b=new WebSocket(url);
const wait=(ws,type)=>new Promise((resolve,reject)=>{const t=setTimeout(()=>reject(new Error("timeout: "+type)),10000);ws.on("message",d=>{const m=JSON.parse(d);if(m.type===type){clearTimeout(t);resolve(m);}});ws.on("error",reject);});
const join=ws=>new Promise((resolve,reject)=>{ws.once("open",()=>{ws.send(JSON.stringify({type:"join",room,clientId:crypto.randomUUID().replaceAll("-","")}));resolve(wait(ws,"snapshot"));});ws.once("error",reject);});
try{const sa=await join(a),sb=await join(b);console.log("client A snapshot",sa);console.log("client B snapshot",sb);
const trackId="smoke";const event=wait(b,"track");a.send(JSON.stringify({type:"play",trackId,songName:"blocks",dimension:"minecraft:overworld",x:1,y:64,z:1,startAtServerMs:Date.now()+3000}));console.log("play received by B",await event);
const stopped=wait(b,"track");a.send(JSON.stringify({type:"stop",trackId,dimension:"minecraft:overworld",x:1,y:64,z:1}));console.log("stop received by B",await stopped);
b.send(JSON.stringify({type:"sync"}));console.log("snapshot after stop",await wait(b,"snapshot"));
}finally{a.close();b.close();}
