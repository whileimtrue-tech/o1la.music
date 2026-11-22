import server from "./api/ws.js";

const port = Number(process.env.PORT ?? 10000);
server.listen(port, "0.0.0.0", () => {
  console.log(`o1la.music relay listening on port ${port}`);
});
