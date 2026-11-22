package tech.whileimtrue.o1lamusic;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class RelayClient {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"o1la-relay");t.setDaemon(true);return t;});
    private volatile WebSocket socket;
    private volatile String room="";
    private volatile long serverOffsetMs=0;
    private volatile String status="Disconnected";
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
    private final class Listener implements WebSocket.Listener {
        private final java.util.function.Consumer<String> log; private final String endpoint; private StringBuilder fragments=new StringBuilder();
        Listener(java.util.function.Consumer<String> log,String endpoint){this.log=log;this.endpoint=endpoint;}
        @Override public void onOpen(WebSocket ws){socket=ws;status="Connected";JsonObject j=new JsonObject();j.addProperty("type","join");j.addProperty("room",room);j.addProperty("clientId",UUID.randomUUID().toString().replace("-",""));ws.sendText(j.toString(),true);ws.request(1);scheduler.scheduleAtFixedRate(()->{if(socket==ws&&ws.isOutputClosed()==false){JsonObject p=new JsonObject();p.addProperty("type","time_ping");p.addProperty("clientTimeMs",System.currentTimeMillis());ws.sendText(p.toString(),true);}},0,5,TimeUnit.SECONDS);}
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){fragments.append(data);if(last){try{JsonObject m=JsonParser.parseString(fragments.toString()).getAsJsonObject();String type=m.has("type")?m.get("type").getAsString():"";if(type.equals("time_pong")){long sent=m.get("clientTimeMs").getAsLong();long recv=System.currentTimeMillis();serverOffsetMs=m.get("serverTimeMs").getAsLong()-(sent+recv)/2;}else if(type.equals("welcome")){status="Connected";log.accept("Connected; clock synchronized soon");}else if(type.equals("snapshot")){log.accept("Snapshot: "+m.get("track"));}else if(type.equals("track")){log.accept("Track event: "+m); /* Playback adapter: resolve resource-pack sound ID and schedule at startAtServerMs. */}else if(type.equals("error")){log.accept("Relay error: "+m.get("code"));}}catch(Exception e){log.accept("Invalid relay response: "+e.getMessage());}fragments.setLength(0);}ws.request(1);return null;}
        @Override public CompletionStage<?> onClose(WebSocket ws,int statusCode,String reason){if(socket==ws)socket=null;if(!closing.get()){status="Reconnecting";scheduler.schedule(()->connectAttempt(endpoint,log,1),2,TimeUnit.SECONDS);}return null;}
        @Override public void onError(WebSocket ws,Throwable error){log.accept("WebSocket: "+error.getMessage());}
    }
}
