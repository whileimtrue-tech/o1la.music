# o1la.music — Minecraft music relay

A client-only Fabric music relay. The Paper server is untouched: Minecraft clients connect directly to this relay over **WSS**.

## Deploy to Render

Create a **Web Service** from this GitHub repository.

- Runtime: Node
- Build Command: `npm install && npm run build`
- Start Command: `npm start`
- Instance type: any always-on instance suitable for your usage
- Health Check Path: leave blank (no HTTP health route is currently provided)

The service listens on `0.0.0.0:$PORT`. WebSocket endpoint after deployment:

`wss://YOUR-SERVICE.onrender.com/api/ws`

Render supports WebSocket connections. Clients should reconnect automatically if the service restarts or an instance connection is interrupted.

## Redis

The relay requires a native Redis connection URL because it uses Redis commands and Pub/Sub.

1. Create a Redis database with a provider that supplies a native TCP/TLS Redis URL.
2. In Render, open your Web Service → **Environment**.
3. Add `REDIS_URL` with the provider's private or public connection URL, for example `rediss://default:YOUR_PASSWORD@YOUR_HOST:6379`.
4. Save changes and redeploy.

Do not commit the Redis URL or expose it to Fabric clients. REST-only Redis endpoints are not compatible with this Node Redis Pub/Sub implementation.

## Environment

| Variable | Required | Description |
|---|---:|---|
| `PORT` | No | Supplied by Render; defaults to 10000 for local runs. |
| `REDIS_URL` | Yes | Native Redis connection URL. Server-side only. |

## WebSocket JSON protocol

All messages are JSON objects. The first client message must be `join`. Maximum payload: 4096 bytes. Room code: 4–32 ASCII letters, digits, underscore or hyphen. Client IDs: 8–64 ASCII letters, digits, underscore or hyphen. Per-client limit: 30 requests per 10 seconds.

### Client → server

- `{"type":"join","room":"ABCD1234","clientId":"client-uuid"}`
- `{"type":"time_ping","clientTimeMs":1730000000000}`
- `{"type":"play","trackId":"blocks","songName":"blocks","dimension":"minecraft:overworld","x":0,"y":64,"z":0,"startAtServerMs":1730000003000}`
- `{"type":"stop","trackId":"blocks","dimension":"minecraft:overworld","x":0,"y":64,"z":0}`
- `{"type":"sync"}`

### Server → client

- `welcome`: client ID, server time and protocol version.
- `snapshot`: current room track, or `null`.
- `track`: play/stop event.
- `time_pong`: echoed client time and server time.
- `error`: machine-readable error code.

Redis stores each room's authoritative current-track snapshot for 24 hours. Redis Pub/Sub distributes events across service instances. Estimate clock offset using the midpoint of the ping round trip; schedule playback against server time plus that offset. On reconnect, join and reconcile the snapshot.

## Security and operational notes

Track and song identifiers are restricted to lowercase resource-like IDs; dimensions must be namespaced identifiers. Coordinates must be finite and within ±30,000,000. Starts must be no more than 5 seconds in the past or 120 seconds ahead. Stop clears the snapshot only if its track and jukebox coordinates match the active track. The relay cannot verify that a player actually inserted a disc: it is client-authoritative, so use trusted/private room codes.

## Fabric client

The `fabric-client/` directory contains the Java 25 Fabric client module and `/c418music connect` command. The client-side resource pack should provide sound events under the `c418music` namespace matching the configured track IDs.

Expected command syntax after the client command is installed:

```text
/c418music connect "wss://YOUR-SERVICE.onrender.com/api/ws" ROOMCODE
/c418music status
/c418music disconnect
```

## Local build and smoke test

Run `npm install && npm run build`. Set `REDIS_URL`, then `npm start`. For a two-client live test, set `WS_URL=wss://YOUR-SERVICE.onrender.com/api/ws` and run `node scripts/smoke-test.mjs`.

## Current implementation status

The relay endpoint, Redis-backed room snapshots, cross-instance Pub/Sub fan-out, message validation, rate limiting, Java 25 WebSocket client, clock-offset estimation, scheduled resource-pack playback, reconnect loop and snapshot playback recovery are present in this initial implementation.

**Not yet complete / not yet verified:** automatic detection of a named `minecraft:music_disc_blocks` insertion and automatic stop-event emission from the Fabric client are not wired to a Minecraft 26.2 client-side interaction hook. The client currently has connect/status/disconnect commands and can consume relay events, but it does not originate jukebox play/stop events itself. Render deployment, Redis provisioning, Gradle compilation against the exact 26.2 toolchain, and a live two-client test have not been performed from this repository session.
