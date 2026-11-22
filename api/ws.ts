import { createServer } from "node:http";
import { WebSocketServer, WebSocket } from "ws";
import { createClient, type RedisClientType } from "redis";
import { randomUUID } from "node:crypto";

type Track = { trackId:string; songName:string; dimension:string; x:number;y:number;z:number; startAtServerMs:number; sourceClientId:string };
type Client = { ws:WebSocket; room:string; clientId:string };
const MAX_MESSAGE=4096, ROOM_RE=/^[A-Za-z0-9_-]{4,32}$/, ID_RE=/^[a-z0-9_.-]{1,96}$/, DIM_RE=/^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
const rooms=new Map<string,Set<Client>>();
const server=createServer((req,res)=>{
 const path=new URL(req.url??"/","http://localhost").pathname;
 if(req.method==="GET"&&(path==="/"||path==="/health")){
  res.writeHead(200,{"content-type":"application/json; charset=utf-8"});
  res.end(JSON.stringify({ok:true,service:"o1la.music-relay",websocket:"/api/ws"}));
  return;
 }
 res.writeHead(404,{"content-type":"application/json; charset=utf-8"});
 res.end(JSON.stringify({ok:false,error:"not_found"}));
});
const wss=new WebSocketServer({noServer:true,maxPayload:MAX_MESSAGE,perMessageDeflate:false});
let pub:RedisClientType|undefined, sub:RedisClientType|undefined, redisReady:Promise<void>|undefined;
const roomKey=(r:string)=>`o1la:room:${r}:snapshot`;
function send(ws:WebSocket,obj:unknown){if(ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify(obj));}
function broadcast(room:string,obj:unknown){for(const c of rooms.get(room)??[])send(c.ws,obj);}
async function redis(){if(!redisReady){redisReady=(async()=>{if(!process.env.REDIS_URL)throw new Error("REDIS_URL is not configured");pub=createClient({url:process.env.REDIS_URL});sub=pub.duplicate();pub.on("error",e=>console.error("redis publisher",e));sub.on("error",e=>console.error("redis subscriber",e));await Promise.all([pub.connect(),sub.connect()]);await sub.subscribe("o1la:events",raw=>{try{const e=JSON.parse(raw);broadcast(e.room,e.message);}catch{}});})();}await redisReady;return pub!;}
async function rateLimit(room:string,client:string){const r=await redis();const key=`o1la:rate:${room}:${client}`;const n=await r.incr(key);if(n===1)await r.expire(key,10);return n<=30;}
function validCoord(v:unknown){return typeof v==="number"&&Number.isFinite(v)&&Math.abs(v)<=30_000_000;}
function parseMessage(raw:WebSocket.RawData){const s=raw.toString();if(Buffer.byteLength(s)>MAX_MESSAGE)throw Error("message_too_large");const v=JSON.parse(s);if(!v||typeof v!=="object"||Array.isArray(v)||typeof v.type!=="string")throw Error("invalid_message");return v;}
async function publish(room:string,message:unknown){const r=await redis();await r.publish("o1la:events",JSON.stringify({room,message}));}
wss.on("connection",(ws)=>{
 let client:Client|undefined;
 ws.on("message",async raw=>{
  try{
   const m=parseMessage(raw);
   if(m.type==="join"){
    if(client)throw Error("already_joined");
    if(typeof m.room!=="string"||!ROOM_RE.test(m.room)||typeof m.clientId!=="string"||!/^[A-Za-z0-9_-]{8,64}$/.test(m.clientId))throw Error("invalid_join");
    client={ws,room:m.room,clientId:m.clientId};const set=rooms.get(m.room)??new Set<Client>();set.add(client);rooms.set(m.room,set);
    await redis();const snapshotRaw=await pub!.get(roomKey(m.room));
    const snap=snapshotRaw?JSON.parse(snapshotRaw) as Track:null;
    send(ws,{type:"welcome",clientId:m.clientId,serverTimeMs:Date.now(),protocol:1});
    send(ws,{type:"snapshot",serverTimeMs:Date.now(),track:snap});
    return;
   }
   if(!client)throw Error("join_required");
   if(!await rateLimit(client.room,client.clientId))throw Error("rate_limited");
   if(m.type==="time_ping"){if(typeof m.clientTimeMs!=="number"||!Number.isFinite(m.clientTimeMs))throw Error("invalid_time");send(ws,{type:"time_pong",clientTimeMs:m.clientTimeMs,serverTimeMs:Date.now()});return;}
   if(m.type==="sync"){const rawSnapshot=await (await redis()).get(roomKey(client.room));const snap=rawSnapshot?JSON.parse(rawSnapshot) as Track:null;send(ws,{type:"snapshot",serverTimeMs:Date.now(),track:snap??null});return;}
   if(m.type==="play"){
    if(typeof m.trackId!=="string"||!ID_RE.test(m.trackId)||typeof m.songName!=="string"||!ID_RE.test(m.songName)||typeof m.dimension!=="string"||!DIM_RE.test(m.dimension)||!validCoord(m.x)||!validCoord(m.y)||!validCoord(m.z)||typeof m.startAtServerMs!=="number"||!Number.isSafeInteger(m.startAtServerMs)||m.startAtServerMs<Date.now()-5000||m.startAtServerMs>Date.now()+120000)throw Error("invalid_play");
    const track:Track={trackId:m.trackId,songName:m.songName,dimension:m.dimension,x:m.x,y:m.y,z:m.z,startAtServerMs:m.startAtServerMs,sourceClientId:client.clientId};
    const r=await redis();await r.set(roomKey(client.room),JSON.stringify(track),{EX:86400});await publish(client.room,{type:"track",action:"play",...track,serverTimeMs:Date.now()});return;
   }
   if(m.type==="stop"){
    if(typeof m.trackId!=="string"||!ID_RE.test(m.trackId)||typeof m.dimension!=="string"||!DIM_RE.test(m.dimension)||!validCoord(m.x)||!validCoord(m.y)||!validCoord(m.z))throw Error("invalid_stop");
    const r=await redis();const currentRaw=await r.get(roomKey(client.room));const current=currentRaw?JSON.parse(currentRaw) as Track:null;if(current&&current.trackId===m.trackId&&current.dimension===m.dimension&&current.x===m.x&&current.y===m.y&&current.z===m.z)await r.del(roomKey(client.room));
    await publish(client.room,{type:"track",action:"stop",trackId:m.trackId,dimension:m.dimension,x:m.x,y:m.y,z:m.z,serverTimeMs:Date.now()});return;
   }
   throw Error("unknown_type");
  }catch(e){send(ws,{type:"error",code:e instanceof Error?e.message:"bad_request"});}
 });
 ws.on("close",()=>{if(client){const set=rooms.get(client.room);set?.delete(client);if(set?.size===0)rooms.delete(client.room);}});
});
server.on("upgrade",(req,socket,head)=>{
 if(req.url!=="/api/ws"){socket.destroy();return;}
 wss.handleUpgrade(req,socket,head,ws=>wss.emit("connection",ws,req));
});
export default server;
