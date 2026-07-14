import { createHash, randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { SignJWT, createRemoteJWKSet, jwtVerify } from "jose";

const PLUGIN_CLIENT_ID = "codeagent-plugin";
const ACCESS_TOKEN_SECONDS = 15 * 60;
const REFRESH_TOKEN_SECONDS = 30 * 24 * 60 * 60;
const AUTH_FLOW_SECONDS = 10 * 60;
const AUTH_CODE_SECONDS = 2 * 60;
const textEncoder = new TextEncoder();

export function createAuthenticatorFromEnv(env = process.env, fetchImpl = fetch, store) {
  const issuer = env.OIDC_ISSUER?.trim().replace(/\/$/, "");
  if (issuer) {
    if (!store) throw new Error("A product store is required when OIDC_ISSUER is configured");
    return new OidcSessionAuthenticator({
      issuer,
      clientId: required(env, "OIDC_CLIENT_ID"),
      clientSecret: required(env, "OIDC_CLIENT_SECRET"),
      audience: env.OIDC_AUDIENCE?.trim() || undefined,
      publicBaseUrl: required(env, "PUBLIC_BASE_URL"),
      sessionSigningKey: required(env, "SESSION_SIGNING_KEY"),
      store,
      fetchImpl,
    });
  }
  const sharedToken = env.CODEAGENT_AUTH_TOKEN?.trim();
  if (sharedToken) return new SharedTokenAuthenticator(sharedToken);
  return new LocalAuthenticator();
}

export class OidcSessionAuthenticator {
  constructor({ issuer, clientId, clientSecret, audience, publicBaseUrl, sessionSigningKey, store, fetchImpl = fetch }) {
    this.issuer = normalizeSecureBaseUrl(issuer, "OIDC_ISSUER");
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.audience = audience;
    this.publicBaseUrl = normalizeSecureBaseUrl(publicBaseUrl, "PUBLIC_BASE_URL");
    this.callbackUri = `${this.publicBaseUrl}/v1/auth/callback`;
    this.store = store;
    this.fetchImpl = fetchImpl;
    this.metadataPromise = null;
    this.metadata = null;
    this.jwks = null;
    if (Buffer.byteLength(sessionSigningKey, "utf8") < 32) {
      throw new Error("SESSION_SIGNING_KEY must contain at least 32 bytes");
    }
    this.signingKey = textEncoder.encode(sessionSigningKey);
    this.mode = "oidc";
  }

  async authenticate(request) {
    const token = bearerToken(request);
    if (!token) throw unauthorized("Bearer token is required");
    try {
      const { payload } = await jwtVerify(token, this.signingKey, {
        algorithms: ["HS256"],
        issuer: this.publicBaseUrl,
        audience: PLUGIN_CLIENT_ID,
      });
      if (typeof payload.sub !== "string" || !payload.sub || typeof payload.sid !== "string" || !payload.sid) {
        throw unauthorized("Session token is missing required claims");
      }
      const session = await this.store.getSession(payload.sid, payload.sub);
      if (!session) throw unauthorized("Session is no longer active");
      const user = await this.store.getUser(payload.sub);
      if (!user) throw unauthorized("Session user no longer exists");
      return { ...user, claims: {}, sessionId: session.id };
    } catch (error) {
      if (error?.statusCode === 401) throw error;
      throw unauthorized("Session token validation failed");
    }
  }

  async publicConfig() {
    await this.#discoveryMetadata();
    return {
      mode: "oidc",
      issuer: this.issuer,
      clientId: PLUGIN_CLIENT_ID,
      audience: this.audience,
      authorizationEndpoint: `${this.publicBaseUrl}/v1/auth/authorize`,
      tokenEndpoint: `${this.publicBaseUrl}/v1/auth/token`,
      endSessionEndpoint: `${this.publicBaseUrl}/v1/auth/logout`,
      scopes: ["openid", "profile", "email", "offline_access"],
    };
  }

  async handlePublicRequest(request, response) {
    const url = new URL(request.url, this.publicBaseUrl);
    if (url.pathname === "/v1/auth/authorize" && request.method === "GET") {
      return this.#authorize(url, response);
    }
    if (url.pathname === "/v1/auth/callback" && request.method === "GET") {
      return this.#callback(url, response);
    }
    if (url.pathname === "/v1/auth/token" && request.method === "POST") {
      return this.#token(request, response);
    }
    return false;
  }

  async logout(principal) {
    if (principal?.sessionId) await this.store.revokeSession(principal.sessionId, principal.id);
  }

  async sessionInfo(principal) {
    if (!principal?.sessionId) return { mode: this.mode };
    const session = await this.store.getSession(principal.sessionId, principal.id);
    return session ? { mode: this.mode, ...session } : { mode: this.mode };
  }

  async #authorize(url, response) {
    const responseType = url.searchParams.get("response_type");
    const clientId = url.searchParams.get("client_id");
    const redirectUri = validatePluginRedirect(url.searchParams.get("redirect_uri"));
    const clientState = requiredParameter(url.searchParams.get("state"), "state", 1_024);
    const clientCodeChallenge = validateCodeChallenge(url.searchParams.get("code_challenge"));
    if (responseType !== "code") throw badRequest("response_type must be code");
    if (clientId !== PLUGIN_CLIENT_ID) throw badRequest("Unknown OAuth client_id");
    if (url.searchParams.get("code_challenge_method") !== "S256") throw badRequest("code_challenge_method must be S256");

    const metadata = await this.#discoveryMetadata();
    const flowId = randomToken(32);
    const providerVerifier = randomToken(64);
    const nonce = randomToken(32);
    await this.store.createAuthFlow({
      id: flowId,
      redirectUri,
      clientState,
      clientCodeChallenge,
      providerCodeVerifier: providerVerifier,
      nonce,
      expiresAt: futureIso(AUTH_FLOW_SECONDS),
    });

    const providerUrl = new URL(metadata.authorization_endpoint);
    const parameters = {
      response_type: "code",
      client_id: this.clientId,
      redirect_uri: this.callbackUri,
      scope: "openid profile email",
      state: flowId,
      nonce,
      code_challenge: sha256Base64Url(providerVerifier),
      code_challenge_method: "S256",
    };
    if (this.audience) parameters.audience = this.audience;
    for (const [name, value] of Object.entries(parameters)) providerUrl.searchParams.set(name, value);
    return redirect(response, providerUrl);
  }

  async #callback(url, response) {
    const flowId = requiredParameter(url.searchParams.get("state"), "state", 1_024);
    const flow = await this.store.consumeAuthFlow(flowId);
    if (!flow) throw badRequest("OIDC authorization flow is invalid or expired");
    if (url.searchParams.has("error")) {
      return redirectToPlugin(response, flow, { error: "access_denied" });
    }

    try {
      const providerCode = requiredParameter(url.searchParams.get("code"), "code", 8_192);
      const principal = await this.#exchangeProviderCode(providerCode, flow);
      await this.store.upsertUser(principal);
      const authorizationCode = randomToken(48);
      await this.store.createAuthCode({
        codeHash: sha256Hex(authorizationCode),
        userId: principal.id,
        redirectUri: flow.redirectUri,
        codeChallenge: flow.clientCodeChallenge,
        expiresAt: futureIso(AUTH_CODE_SECONDS),
      });
      return redirectToPlugin(response, flow, { code: authorizationCode });
    } catch {
      return redirectToPlugin(response, flow, { error: "server_error" });
    }
  }

  async #token(request, response) {
    const form = await readForm(request);
    if (form.get("client_id") !== PLUGIN_CLIENT_ID) throw badRequest("Unknown OAuth client_id");
    const grantType = form.get("grant_type");
    if (grantType === "authorization_code") {
      const code = requiredParameter(form.get("code"), "code", 8_192);
      const record = await this.store.consumeAuthCode(sha256Hex(code));
      if (!record) throw badRequest("Authorization code is invalid or expired");
      if (validatePluginRedirect(form.get("redirect_uri")) !== record.redirectUri) {
        throw badRequest("redirect_uri does not match the authorization request");
      }
      const verifier = validateCodeVerifier(form.get("code_verifier"));
      if (!timingSafeEqualText(sha256Base64Url(verifier), record.codeChallenge)) {
        throw badRequest("PKCE verification failed");
      }
      return sendTokenResponse(response, await this.#createSession(record.userId));
    }
    if (grantType === "refresh_token") {
      const refreshToken = requiredParameter(form.get("refresh_token"), "refresh_token", 8_192);
      const replacement = randomToken(48);
      const session = await this.store.rotateSession(
        sha256Hex(refreshToken),
        sha256Hex(replacement),
        futureIso(REFRESH_TOKEN_SECONDS),
      );
      if (!session) throw badRequest("Refresh token is invalid or expired");
      return sendTokenResponse(response, await this.#tokenResponse(session, replacement));
    }
    throw badRequest("Unsupported grant_type");
  }

  async #createSession(userId) {
    const refreshToken = randomToken(48);
    const session = await this.store.createSession({
      id: randomUUID(),
      userId,
      refreshTokenHash: sha256Hex(refreshToken),
      refreshExpiresAt: futureIso(REFRESH_TOKEN_SECONDS),
    });
    return this.#tokenResponse(session, refreshToken);
  }

  async #tokenResponse(session, refreshToken) {
    const accessToken = await new SignJWT({ sid: session.id })
      .setProtectedHeader({ alg: "HS256", typ: "JWT" })
      .setIssuer(this.publicBaseUrl)
      .setAudience(PLUGIN_CLIENT_ID)
      .setSubject(session.userId)
      .setIssuedAt()
      .setExpirationTime(`${ACCESS_TOKEN_SECONDS}s`)
      .sign(this.signingKey);
    return {
      token_type: "Bearer",
      access_token: accessToken,
      refresh_token: refreshToken,
      expires_in: ACCESS_TOKEN_SECONDS,
    };
  }

  async #exchangeProviderCode(code, flow) {
    const metadata = await this.#discoveryMetadata();
    const body = new URLSearchParams({
      grant_type: "authorization_code",
      client_id: this.clientId,
      code,
      redirect_uri: this.callbackUri,
      code_verifier: flow.providerCodeVerifier,
    });
    const methods = metadata.token_endpoint_auth_methods_supported;
    const headers = {
      accept: "application/json",
      "content-type": "application/x-www-form-urlencoded",
    };
    if (Array.isArray(methods) && !methods.includes("client_secret_basic") && methods.includes("client_secret_post")) {
      body.set("client_secret", this.clientSecret);
    } else {
      headers.authorization = `Basic ${Buffer.from(`${this.clientId}:${this.clientSecret}`, "utf8").toString("base64")}`;
    }
    const response = await this.fetchImpl(metadata.token_endpoint, {
      method: "POST",
      headers,
      body: body.toString(),
    });
    const tokens = await response.json().catch(() => null);
    if (!response.ok || typeof tokens?.id_token !== "string") {
      throw new Error(`OIDC token exchange failed with HTTP ${response.status}`);
    }
    const { payload } = await jwtVerify(tokens.id_token, await this.#providerKeySet(), {
      issuer: this.issuer,
      audience: this.clientId,
    });
    if (payload.nonce !== flow.nonce) throw new Error("OIDC nonce validation failed");
    if (typeof payload.sub !== "string" || !payload.sub) throw new Error("OIDC token has no subject");
    return principalFromClaims(payload);
  }

  async #providerKeySet() {
    if (this.jwks) return this.jwks;
    const metadata = await this.#discoveryMetadata();
    this.jwks = createRemoteJWKSet(new URL(metadata.jwks_uri));
    return this.jwks;
  }

  async #discoveryMetadata() {
    if (this.metadata) return this.metadata;
    if (!this.metadataPromise) {
      this.metadataPromise = this.fetchImpl(`${this.issuer}/.well-known/openid-configuration`, {
        headers: { accept: "application/json" },
      }).then(async (response) => {
        if (!response.ok) throw new Error(`OIDC discovery failed with HTTP ${response.status}`);
        const metadata = await response.json();
        if (metadata.issuer?.replace(/\/$/, "") !== this.issuer) throw new Error("OIDC discovery returned a different issuer");
        for (const field of ["jwks_uri", "authorization_endpoint", "token_endpoint"]) {
          if (typeof metadata[field] !== "string") throw new Error(`OIDC discovery is missing ${field}`);
          validateSecureUrl(metadata[field], `OIDC ${field}`);
        }
        this.metadata = metadata;
        return metadata;
      }).catch((error) => {
        this.metadataPromise = null;
        throw error;
      });
    }
    return this.metadataPromise;
  }
}

export class SharedTokenAuthenticator {
  constructor(token) {
    this.token = token;
    this.mode = "shared-token";
  }

  async authenticate(request) {
    if (bearerToken(request) !== this.token) throw unauthorized("Unauthorized");
    return { id: "shared-token-user", email: null, displayName: "CodeAgent User", claims: {} };
  }

  publicConfig() {
    return { mode: this.mode };
  }

  async logout() {}
  async sessionInfo() { return { mode: this.mode }; }
}

export class LocalAuthenticator {
  constructor() {
    this.mode = "local";
  }

  async authenticate() {
    return { id: "local-user", email: null, displayName: "Local User", claims: {} };
  }

  publicConfig() {
    return { mode: this.mode };
  }

  async logout() {}
  async sessionInfo() { return { mode: this.mode }; }
}

function principalFromClaims(claims) {
  return {
    id: claims.sub,
    email: typeof claims.email === "string" ? claims.email : null,
    displayName: firstString(claims.name, claims.preferred_username, claims.email, claims.sub),
    claims,
  };
}

async function readForm(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 64 * 1_024) throw badRequest("OAuth request body is too large");
    chunks.push(chunk);
  }
  return new URLSearchParams(Buffer.concat(chunks).toString("utf8"));
}

function validatePluginRedirect(value) {
  const raw = requiredParameter(value, "redirect_uri", 2_048);
  const url = new URL(raw);
  if (url.protocol !== "http:" || url.hostname !== "127.0.0.1" || !url.port || url.pathname !== "/callback" ||
      url.username || url.password || url.search || url.hash) {
    throw badRequest("redirect_uri must be an ephemeral 127.0.0.1 loopback callback");
  }
  return url.toString();
}

function validateCodeChallenge(value) {
  const challenge = requiredParameter(value, "code_challenge", 128);
  if (!/^[A-Za-z0-9_-]{43,128}$/.test(challenge)) throw badRequest("code_challenge is invalid");
  return challenge;
}

function validateCodeVerifier(value) {
  const verifier = requiredParameter(value, "code_verifier", 128);
  if (!/^[A-Za-z0-9._~-]{43,128}$/.test(verifier)) throw badRequest("code_verifier is invalid");
  return verifier;
}

function requiredParameter(value, name, maxLength) {
  if (typeof value !== "string" || !value || value.length > maxLength) throw badRequest(`${name} is required`);
  return value;
}

function normalizeSecureBaseUrl(value, name) {
  const url = validateSecureUrl(value, name);
  if ((url.pathname && url.pathname !== "/") || url.search || url.hash || url.username || url.password) {
    throw new Error(`${name} must be an origin without a path, query, credentials, or fragment`);
  }
  return url.origin;
}

function validateSecureUrl(value, name) {
  const url = new URL(value);
  const localHttp = url.protocol === "http:" && ["127.0.0.1", "localhost"].includes(url.hostname);
  if (url.protocol !== "https:" && !localHttp) throw new Error(`${name} must use HTTPS`);
  return url;
}

function redirectToPlugin(response, flow, values) {
  const url = new URL(flow.redirectUri);
  for (const [name, value] of Object.entries({ ...values, state: flow.clientState })) url.searchParams.set(name, value);
  return redirect(response, url);
}

function redirect(response, url) {
  response.writeHead(302, {
    location: url.toString(),
    "cache-control": "no-store",
  });
  response.end();
  return true;
}

function sendTokenResponse(response, body) {
  response.writeHead(200, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    pragma: "no-cache",
  });
  response.end(JSON.stringify(body));
  return true;
}

function randomToken(bytes) {
  return randomBytes(bytes).toString("base64url");
}

function sha256Base64Url(value) {
  return createHash("sha256").update(value, "utf8").digest("base64url");
}

function sha256Hex(value) {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

function timingSafeEqualText(left, right) {
  const leftHash = createHash("sha256").update(left, "utf8").digest();
  const rightHash = createHash("sha256").update(right, "utf8").digest();
  return timingSafeEqual(leftHash, rightHash);
}

function futureIso(seconds) {
  return new Date(Date.now() + seconds * 1_000).toISOString();
}

function bearerToken(request) {
  const value = request.headers.authorization;
  if (typeof value !== "string" || !value.startsWith("Bearer ")) return null;
  return value.slice("Bearer ".length).trim() || null;
}

function firstString(...values) {
  return values.find((value) => typeof value === "string" && value.trim()) || "CodeAgent User";
}

function unauthorized(message) {
  const error = new Error(message);
  error.statusCode = 401;
  return error;
}

function badRequest(message) {
  const error = new Error(message);
  error.statusCode = 400;
  return error;
}

function required(env, name) {
  const value = env[name]?.trim();
  if (!value) throw new Error(`${name} is required when OIDC_ISSUER is configured`);
  return value;
}
