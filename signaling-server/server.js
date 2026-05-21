const http = require("http");
const { WebSocketServer } = require("ws");

const PORT = Number(process.env.PORT || 8080);
const rooms = new Map();

function getRoom(roomId) {
  if (!rooms.has(roomId)) {
    rooms.set(roomId, { publisher: null, viewer: null });
  }
  return rooms.get(roomId);
}

function send(ws, payload) {
  if (ws && ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify(payload));
  }
}

function forwardToPeer(room, fromRole, payload) {
  const target =
    fromRole === "publisher" ? room.viewer : room.publisher;
  send(target, payload);
}

const server = http.createServer((req, res) => {
  const remote = req.socket.remoteAddress;
  console.log(`HTTP ${req.method} ${req.url} from ${remote}`);
  res.writeHead(200, { "Content-Type": "text/plain" });
  res.end("POC screen-share signaling server\n");
});

const wss = new WebSocketServer({ server });

server.on("error", (err) => console.error("HTTP server error:", err));
wss.on("error", (err) => console.error("WebSocket server error:", err));

wss.on("connection", (ws, req) => {
  console.log(`WebSocket connected from ${req.socket.remoteAddress}`);
  let roomId = null;
  let role = null;

  ws.on("message", (raw) => {
    let message;
    try {
      message = JSON.parse(raw.toString());
    } catch {
      send(ws, { type: "error", message: "Invalid JSON" });
      return;
    }

    if (message.type === "join") {
      roomId = message.room || "default";
      role = message.role;
      const room = getRoom(roomId);

      if (role === "publisher") {
        room.publisher = ws;
      } else if (role === "viewer") {
        room.viewer = ws;
      } else {
        send(ws, { type: "error", message: "role must be publisher or viewer" });
        return;
      }

      send(ws, { type: "joined", room: roomId, role });
      const peer = role === "publisher" ? room.viewer : room.publisher;
      if (peer) {
        send(ws, { type: "peer-ready" });
        send(peer, { type: "peer-ready" });
      }
      return;
    }

    if (!roomId || !role) {
      send(ws, { type: "error", message: "Join a room first" });
      return;
    }

    const room = getRoom(roomId);
    forwardToPeer(room, role, message);
  });

  ws.on("error", (err) => console.error("WebSocket client error:", err.message));

  ws.on("close", () => {
    console.log(`WebSocket closed role=${role ?? "?"} room=${roomId ?? "?"}`);
    if (!roomId) return;
    const room = rooms.get(roomId);
    if (!room) return;
    if (room.publisher === ws) room.publisher = null;
    if (room.viewer === ws) room.viewer = null;
    if (!room.publisher && !room.viewer) {
      rooms.delete(roomId);
    }
  });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`Signaling server listening on ws://0.0.0.0:${PORT}`);
});
