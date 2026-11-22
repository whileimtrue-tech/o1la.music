package tech.whileimtrue.o1lamusic;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class RelayClient {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"o1la-relay");t.setDaemon(true);return t;});
    private volatile WebSocket socket;
    private volatile String room="";
    private volatile long serverOffsetMs=0;
    private volatile String status="Disconnected";
    private volatile String activeTrackId="";
    private volatile Identifier activeSoundId;
    private volatile ScheduledFuture<?> pingTask;
    private final AtomicBoolean closing=new AtomicBoolean(false);

    public void connect(String endpoint,String room,java.util.function.Consumer<String> log) {
        if(!endpoint.startsWith("wss://")){log.accept("Endpoint must use wss://");return;}
        this.room=room;closing.set(false);status="Connecting";
        connectAttempt(endpoint,log,0);
    }
    private void connectAttempt(String endpoint,java.util.function.Consumer<String> log,int attempt) {
        if(closing.get())return;
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(endpoint),new Listener(log,endpoint))
            .exceptionally(ex->{status="Reconnect scheduled";long delay=Math.min(30,1L<<Math.min(attempt,5));scheduler.schedule(()->connectAttempt(endpoint,log,attempt+1),delay,TimeUnit.SECONDS);return null;});
    }
    public void disconnect(){closing.set(true);WebSocket s=socket;if(s!=null)s.sendClose(WebSocket.NORMAL_CLOSURE,"client disconnect");status="Disconnected";}
    public String status(){return status+" | room="+room+" | serverOffsetMs="+serverOffsetMs;}
    private void send(JsonObject o){WebSocket s=socket;if(s!=null)s.sendText(o.toString(),true);}
    private static String stringField(JsonObject object,String key) {
        JsonElement value=object.get(key);
        return value!=null&&!value.isJsonNull()&&value.isJsonPrimitive()?value.getAsString():null;
    }
    private static Double numberField(JsonObject object,String key) {
        JsonElement value=object.get(key);
        return value!=null&&!value.isJsonNull()&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber()?value.getAsDouble():null;
    }
    private void handleTrack(JsonObject m,java.util.function.Consumer<String> log) {
        String action=stringField(m,"action");
        String trackId=stringField(m,"trackId");
        if(action==null||trackId==null) {
            log.accept("Ignoring malformed track message: missing action or trackId");
            return;
        }
        String dimension=stringField(m,"dimension");
        if(dimension==null)dimension="";
        Minecraft game=Minecraft.getInstance();
        if(action.equals("stop")) {
            if(trackId.equals(activeTrackId)&&activeSoundId!=null)game.execute(()->game.getSoundManager().stop(activeSoundId,SoundSource.RECORDS));
            if(trackId.equals(activeTrackId)){activeTrackId="";activeSoundId=null;}
            log.accept("Stopped track "+trackId);return;
        }
        String song=stringField(m,"songName");
        Double startAtValue=numberField(m,"startAtServerMs");
        Double xValue=numberField(m,"x"),yValue=numberField(m,"y"),zValue=numberField(m,"z");
        if(song==null||startAtValue==null||xValue==null||yValue==null||zValue==null) {
            log.accept("Ignoring malformed play message: missing songName, startAtServerMs, or coordinates");
            return;
        }
        Identifier soundId=Identifier.fromNamespaceAndPath("c418music",song);
        long startAt=startAtValue.longValue();
        long delay=Math.max(0,startAt-(System.currentTimeMillis()+serverOffsetMs));
        activeTrackId=trackId;activeSoundId=soundId;
        scheduler.schedule(()->game.execute(()->{
            if(!trackId.equals(activeTrackId))return;
            SoundEvent event=SoundEvent.createVariableRangeEvent(soundId);
            double x=xValue+0.5,y=yValue+0.5,z=zValue+0.5;
            game.getSoundManager().play(new SimpleSoundInstance(event,SoundSource.RECORDS,1.0F,1.0F,RandomSource.create(),x,y,z));
            log.accept("Playing c418music:"+song+" in "+dimension);
        }),delay,TimeUnit.MILLISECONDS);
    }
    private final class Listener implements WebSocket.Listener {
        private final java.util.function.Consumer<String> log; private final String endpoint; private StringBuilder fragments=new StringBuilder();
        Listener(java.util.function.Consumer<String> log,String endpoint){this.log=log;this.endpoint=endpoint;}
        @Override public void onOpen(WebSocket ws){socket=ws;status="Connected";JsonObject j=new JsonObject();j.addProperty("type","join");j.addProperty("room",room);j.addProperty("clientId",UUID.randomUUID().toString().replace("-",""));ws.sendText(j.toString(),true);ws.request(1);if(pingTask!=null)pingTask.cancel(false);pingTask=scheduler.scheduleAtFixedRate(()->{if(socket==ws&&!ws.isOutputClosed()){JsonObject p=new JsonObject();p.addProperty("type","time_ping");p.addProperty("clientTimeMs",System.currentTimeMillis());ws.sendText(p.toString(),true);}},0,5,TimeUnit.SECONDS);}
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){
            fragments.append(data);
            if(last){
                try {
                    JsonObject m=JsonParser.parseString(fragments.toString()).getAsJsonObject();
                    String type=stringField(m,"type");
                    if(type==null){log.accept("Ignoring relay message without a string type");}
                    else if(type.equals("time_pong")){
                        Double sent=numberField(m,"clientTimeMs"),server=numberField(m,"serverTimeMs");
                        if(sent!=null&&server!=null){long recv=System.currentTimeMillis();serverOffsetMs=server.longValue()-(sent.longValue()+recv)/2;}
                        else log.accept("Ignoring malformed time_pong: missing timestamps");
                    } else if(type.equals("welcome")){
                        status="Connected";log.accept("Connected; clock synchronized soon");
                    } else if(type.equals("snapshot")){
                        JsonElement trackValue=m.get("track");
                        if(trackValue!=null&&!trackValue.isJsonNull()&&trackValue.isJsonObject()){
                            JsonObject track=trackValue.getAsJsonObject();track.addProperty("action","play");handleTrack(track,log);
                        } else log.accept("Snapshot: no active track");
                    } else if(type.equals("track")){
                        handleTrack(m,log);
                    } else if(type.equals("error")){
                        String code=stringField(m,"code");log.accept("Relay error: "+(code==null?"unknown_error":code));
                    } else {
                        log.accept("Ignoring unknown relay message type: "+type);
                    }
                } catch(Exception e) {
                    log.accept("Invalid relay response: "+e.getMessage());
                }
                fragments.setLength(0);
            }
            ws.request(1);return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws,int statusCode,String reason){if(socket==ws)socket=null;if(pingTask!=null){pingTask.cancel(false);pingTask=null;}if(!closing.get()){status="Reconnecting";scheduler.schedule(()->connectAttempt(endpoint,log,1),2,TimeUnit.SECONDS);}return null;}
        @Override public void onError(WebSocket ws,Throwable error){log.accept("WebSocket: "+error.getMessage());}
    }
}
