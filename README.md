# o1la.music — Minecraft music relay

A client-only Fabric music relay. The Paper server is untouched: clients connect directly to the relay over **WSS**.

## Architecture

- Vercel Function WebSocket endpoint: `/api/ws` (Vercel WebSocket support; Fluid Compute).
- Redis (Marketplace) holds each room's authoritative current-track snapshot with a 24-hour TTL.
- Redis Pub/Sub fans events out across function instances. Function memory is only a transient connection registry, never authoritative room state.
- Clients reconnect with exponential backoff and request a snapshot after joining.

## Deploy

1. Import this repository into Vercel and select the project.
2. In Vercel **Storage / Marketplace**, add a Redis database (Redis Cloud or Upstash for Redis). Link it to this project.
3. Ensure the integration exposes `REDIS_URL` (Redis Cloud) to Production, Preview and Development. If using Upstash's REST-only integration, this Node `redis` Pub/Sub adapter is not compatible; use a Redis Marketplace database that provides a native Redis URL / TCP endpoint.
4. Enable Fluid Compute for the project. WebSockets are supported by Vercel Functions; connection duration is still bounded by the Function maximum duration.
5. Deploy. Endpoint: `wss://YOUR-DEPLOYMENT.vercel.app/api/ws` (no custom port; HTTPS/WSS standard port 443).
6. Set the maximum function duration to suit your plan. Clients reconnect automatically.

No static secret is needed by the Fabric client. Do not expose `REDIS_URL` to clients.

## Environment

| Variable | Required | Description |
|---|---:|---|
| `REDIS_URL` | yes | Redis native connection URL, e.g. `rediss://default:…@…:6379`. Server-side only. |

## WebSocket JSON protocol

All messages are JSON objects. The first client message must be `join`. Maximum payload: 4096 bytes. Room code: 4–32 ASCII letters, digits, underscore or hyphen. Client IDs: 8–64 ASCII letters, digits, underscore or hyphen. Per-client limit: 30 requests per 10 seconds.

### Client → server

- `{"type":"join","room":"ABCD1234","clientId":"client-uuid"}`
- `{"type":"time_ping","clientTimeMs":1730000000000}`
- `{"type":"play","trackId":"blocks","songName":"blocks","dimension":"minecraft:overworld","x":0,"y":64,"z":0,"startAtServerMs":1730000003000}`
- `{"type":"stop","trackId":"blocks","dimension":"minecraft:overworld","x":0,"y":64,"z":0}`
- `{"type":"sync"}`

### Server → client

- `welcome`: `{"type":"welcome","clientId":"…","serverTimeMs":1730000000000,"protocol":1}`
- `snapshot`: `{"type":"snapshot","serverTimeMs":1730000000000,"track":null}` or the current track object.
- `track`: `{"type":"track","action":"play",…track,"serverTimeMs":…}` or `action:"stop"`.
- `time_pong`: echoes `clientTimeMs` and includes `serverTimeMs`.
- `error`: `{"type":"error","code":"invalid_play"}`.

A track snapshot contains `trackId`, `songName`, `dimension`, `x/y/z`, `startAtServerMs`, and `sourceClientId`. Server time is Unix epoch milliseconds. Estimate offset with the midpoint of the ping round trip; schedule playback against server time plus this offset. On reconnect, join then reconcile the snapshot.

## Security and operational notes

Track and song identifiers are restricted to lowercase resource-like IDs; dimensions must be namespaced identifiers. Coordinates must be finite and within ±30,000,000. Starts must be no more than 5 seconds in the past or 120 seconds ahead. Snapshot keys expire after 24 hours. Stop clears the snapshot only if its track and jukebox coordinates match the active track. The relay cannot verify that a player actually inserted a disc: it is client-authoritative, so use trusted/private room codes.

## Fabric client

The `fabric-client/` directory contains the Java 25 Fabric client module and `/c418music connect` command. The client-side resource pack should provide sound events under the `c418music` namespace matching the configured track IDs.

## Local smoke test

Run `npm install && npm run build`, set `REDIS_URL`, and deploy to Vercel. For a two-client live test, connect two WebSocket clients to the deployed URL, join with the same room, publish a play and stop from client A, and request sync from client B. See `scripts/smoke-test.mjs`. A live test requires a deployed URL and Redis credentials; neither is committed.
