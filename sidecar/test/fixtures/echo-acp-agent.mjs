import { Readable, Writable } from "node:stream";
import * as acp from "@agentclientprotocol/sdk";

const sessions = new Set();
let sequence = 0;

const app = acp.agent({ name: "codeagent-test-acp" })
  .onRequest(acp.methods.agent.initialize, () => ({
    protocolVersion: acp.PROTOCOL_VERSION,
    agentCapabilities: { loadSession: true },
    agentInfo: { name: "codeagent-test-acp", title: "CodeAgent Test ACP", version: "1.0.0" },
    authMethods: [],
  }))
  .onRequest(acp.methods.agent.session.new, () => {
    const sessionId = `session-${++sequence}`;
    sessions.add(sessionId);
    return { sessionId };
  })
  .onRequest(acp.methods.agent.session.load, ({ params }) => {
    sessions.add(params.sessionId);
    return {};
  })
  .onRequest(acp.methods.agent.session.prompt, async ({ params, client }) => {
    if (!sessions.has(params.sessionId)) throw new Error("Session not found");
    const text = params.prompt
      .filter((content) => content.type === "text")
      .map((content) => content.text)
      .join("");
    await client.notify(acp.methods.client.session.update, {
      sessionId: params.sessionId,
      update: {
        sessionUpdate: "agent_message_chunk",
        content: { type: "text", text: `echo:${text}` },
      },
    });
    return { stopReason: "end_turn" };
  })
  .onNotification(acp.methods.agent.session.cancel, () => {});

app.connect(acp.ndJsonStream(
  Writable.toWeb(process.stdout),
  Readable.toWeb(process.stdin),
));
