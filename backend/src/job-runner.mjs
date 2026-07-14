import { productJobMessages } from "./prompt.mjs";

export class ProductJobRunner {
  constructor({ store, modelGateway, concurrency = 4, logger = console }) {
    this.store = store;
    this.modelGateway = modelGateway;
    this.concurrency = Math.max(1, concurrency);
    this.logger = logger;
    this.queue = [];
    this.active = new Map();
    this.started = false;
  }

  async start() {
    if (this.started) return;
    this.started = true;
    const pending = await this.store.listRunnableJobs();
    for (const job of pending) {
      if (job.status === "running") await this.store.updateJob(job.userId, job.id, { status: "queued", error: "Resumed after backend restart" });
      this.#enqueue(job.userId, job.id);
    }
    this.#drain();
  }

  async create(userId, request) {
    validateJobRequest(request);
    const job = await this.store.createJob(userId, { type: request.type, input: request.input });
    this.#enqueue(userId, job.id);
    this.#drain();
    return job;
  }

  async cancel(userId, id) {
    const job = await this.store.getJob(userId, id);
    if (!job) return null;
    if (["completed", "failed", "cancelled"].includes(job.status)) return job;
    this.queue = this.queue.filter((entry) => !(entry.userId === userId && entry.id === id));
    this.active.get(this.#key(userId, id))?.abort();
    return this.store.updateJob(userId, id, { status: "cancelled", error: "Cancelled by user" });
  }

  #enqueue(userId, id) {
    if (this.queue.some((entry) => entry.userId === userId && entry.id === id)) return;
    if (this.active.has(this.#key(userId, id))) return;
    this.queue.push({ userId, id });
  }

  #drain() {
    while (this.active.size < this.concurrency && this.queue.length > 0) {
      const entry = this.queue.shift();
      const controller = new AbortController();
      this.active.set(this.#key(entry.userId, entry.id), controller);
      void this.#execute(entry.userId, entry.id, controller).finally(() => {
        this.active.delete(this.#key(entry.userId, entry.id));
        this.#drain();
      });
    }
  }

  async #execute(userId, id, controller) {
    const job = await this.store.getJob(userId, id);
    if (!job || job.status === "cancelled") return;
    await this.store.updateJob(userId, id, { status: "running", error: null });
    try {
      const output = await this.#run(job, controller.signal);
      if (controller.signal.aborted) return;
      await this.store.updateJob(userId, id, { status: "completed", output, error: null });
      await this.store.recordUsage(userId, { kind: `job:${job.type}`, units: 1, metadata: { jobId: id } });
    } catch (error) {
      if (controller.signal.aborted) return;
      this.logger.error?.(error);
      await this.store.updateJob(userId, id, { status: "failed", error: rootMessage(error) });
    }
  }

  async #run(job, signal) {
    if (!["subagent", "history-summary"].includes(job.type)) throw new Error(`Unsupported job type: ${job.type}`);
    const prompt = requiredText(job.input?.prompt, "Job prompt");
    let content = "";
    const turn = await this.modelGateway.stream({
      model: typeof job.input?.model === "string" ? job.input.model : undefined,
      messages: productJobMessages({ type: job.type, prompt, system: job.input?.system }),
      tools: [],
      signal,
      onTextDelta: (delta) => { content += delta || ""; },
    });
    if (!content && typeof turn?.content === "string") content = turn.content;
    if (!content.trim()) throw new Error("Job model returned empty content");
    return { content, model: job.input?.model || this.modelGateway.defaultModel || "" };
  }

  #key(userId, id) {
    return `${userId}:${id}`;
  }
}

function validateJobRequest(request) {
  if (!request || typeof request !== "object" || Array.isArray(request)) throw badRequest("Job request is required");
  if (!["subagent", "history-summary"].includes(request.type)) throw badRequest("Unsupported job type");
  if (!request.input || typeof request.input !== "object" || Array.isArray(request.input)) throw badRequest("Job input is required");
  requiredText(request.input.prompt, "Job prompt");
}

function requiredText(value, label) {
  if (typeof value !== "string" || !value.trim()) throw badRequest(`${label} is required`);
  if (value.length > 100_000) throw badRequest(`${label} is too long`);
  return value.trim();
}

function badRequest(message) {
  const error = new Error(message);
  error.statusCode = 400;
  return error;
}

function rootMessage(error) {
  let current = error;
  while (current?.cause) current = current.cause;
  return current instanceof Error ? current.message : String(current);
}
