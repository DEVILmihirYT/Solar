import type { FastifyInstance, FastifyRequest, FastifyReply } from "fastify";
import "@fastify/cookie";
import crypto from "node:crypto";
import { config } from "./config.js";
import { pool } from "./db.js";
import { decryptSecret, encryptSecret, pkceChallenge, randomToken } from "./security/crypto.js";
import type { AuthUser } from "./types.js";

const SESSION_COOKIE = "solar_session";
const OAUTH_COOKIE = "solar_oauth";
const SESSION_TTL = 604800000;
const MOBILE_HANDOFF_TTL = 300000;
const MOBILE_REDIRECT = "solar://auth/callback";

declare module "fastify" {
  interface FastifyRequest {
    authUser?: AuthUser;
  }
}

const cookieOptions = (secure: boolean) => ({
  httpOnly: true,
  secure,
  sameSite: "lax" as const,
  path: "/",
  signed: true
});

const hashHandoff = (value: string) =>
  crypto.createHash("sha256").update(value).digest("hex");

function githubAuthorizeUrl(
  state: string,
  verifier: string
): string {
  const p = new URLSearchParams({
    client_id: config.GITHUB_CLIENT_ID,
    redirect_uri: config.GITHUB_CALLBACK_URL,
    state,
    code_challenge: pkceChallenge(verifier),
    code_challenge_method: "S256",
    scope: "read:user repo"
  });
  return "https://github.com/login/oauth/authorize?" + p.toString();
}

export async function registerAuth(app: FastifyInstance) {
  const secure = config.NODE_ENV === "production";

  // Browser/web flow and direct Android deep-link flow.
  app.get("/api/auth/github", async (req, reply) => {
    const q = req.query as { platform?: string };
    const state = randomToken(32);
    const verifier = randomToken(48);
    const mobile = q.platform === "android";

    reply.setCookie(
      OAUTH_COOKIE,
      JSON.stringify({ state, verifier, mobile }),
      { ...cookieOptions(secure), maxAge: 600 }
    );

    return reply.redirect(githubAuthorizeUrl(state, verifier));
  });

  // Polling-based Android flow used by the current client. It does not depend
  // on Android receiving a custom-scheme callback from the browser.
  app.post("/api/auth/github/mobile/start", async (_req, reply) => {
    const state = randomToken(32);
    const verifier = randomToken(48);
    const requestId = randomToken(32);

    reply.setCookie(
      OAUTH_COOKIE,
      JSON.stringify({
        state,
        verifier,
        mobilePolling: true,
        requestId
      }),
      { ...cookieOptions(secure), maxAge: 600 }
    );

    return {
      requestId,
      authorizationUrl: githubAuthorizeUrl(state, verifier)
    };
  });

  app.get("/api/auth/github/callback", async (req, reply) => {
    const q = req.query as { code?: string; state?: string };
    const raw = req.cookies[OAUTH_COOKIE];

    if (!raw || !q.code || !q.state) {
      return reply.code(400).send({ error: "Invalid OAuth callback" });
    }

    let oauth: {
      state: string;
      verifier: string;
      mobile?: boolean;
      mobilePolling?: boolean;
      requestId?: string;
    };

    try {
      oauth = JSON.parse(raw);
    } catch {
      return reply.code(400).send({ error: "Invalid OAuth state" });
    }

    if (oauth.state !== q.state) {
      return reply.code(400).send({ error: "OAuth state mismatch" });
    }

    const tokenResponse = await fetch(
      "https://github.com/login/oauth/access_token",
      {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          client_id: config.GITHUB_CLIENT_ID,
          client_secret: config.GITHUB_CLIENT_SECRET,
          code: q.code,
          redirect_uri: config.GITHUB_CALLBACK_URL,
          code_verifier: oauth.verifier
        })
      }
    );

    if (!tokenResponse.ok) {
      return reply.code(502).send({ error: "GitHub token exchange failed" });
    }

    const token = await tokenResponse.json() as any;
    if (!token.access_token) {
      return reply
        .code(401)
        .send({ error: token.error ?? "GitHub authorization failed" });
    }

    const profileResponse = await fetch("https://api.github.com/user", {
      headers: {
        Accept: "application/vnd.github+json",
        Authorization: "Bearer " + token.access_token,
        "X-GitHub-Api-Version": "2022-11-28"
      }
    });

    if (!profileResponse.ok) {
      return reply.code(502).send({ error: "GitHub identity lookup failed" });
    }

    const profile = await profileResponse.json() as {
      id: number;
      login: string;
      name?: string | null;
      avatar_url?: string | null;
    };

    const c = await pool.connect();

    try {
      await c.query("BEGIN");

      const userResult = await c.query<{ id: string }>(
        "INSERT INTO users(id,github_id,github_login,github_name,github_avatar_url) VALUES($1,$2,$3,$4,$5) " +
        "ON CONFLICT(github_id) DO UPDATE SET github_login=EXCLUDED.github_login,github_name=EXCLUDED.github_name,github_avatar_url=EXCLUDED.github_avatar_url,updated_at=now() " +
        "RETURNING id",
        [
          crypto.randomUUID(),
          String(profile.id),
          profile.login,
          profile.name ?? null,
          profile.avatar_url ?? null
        ]
      );

      const userId = userResult.rows[0]!.id;

      await c.query(
        "INSERT INTO github_accounts(user_id,access_token_enc,refresh_token_enc,scopes) VALUES($1,$2,$3,$4) " +
        "ON CONFLICT(user_id) DO UPDATE SET access_token_enc=EXCLUDED.access_token_enc,refresh_token_enc=EXCLUDED.refresh_token_enc,scopes=EXCLUDED.scopes,updated_at=now()",
        [
          userId,
          encryptSecret(token.access_token),
          token.refresh_token ? encryptSecret(token.refresh_token) : null,
          token.scope ?? ""
        ]
      );

      const sessionId = randomToken(32);

      await c.query(
        "INSERT INTO sessions(id,user_id,expires_at) VALUES($1,$2,$3)",
        [sessionId, userId, new Date(Date.now() + SESSION_TTL)]
      );

      reply.clearCookie(OAUTH_COOKIE, cookieOptions(secure));

      if (oauth.mobilePolling && oauth.requestId) {
        await c.query(
          "INSERT INTO auth_handoffs(code_hash,session_id,expires_at) VALUES($1,$2,$3)",
          [
            hashHandoff(oauth.requestId),
            sessionId,
            new Date(Date.now() + MOBILE_HANDOFF_TTL)
          ]
        );

        await c.query("COMMIT");

        return reply
          .type("text/html; charset=utf-8")
          .send(
            "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>Solar GitHub connected</title></head><body style=\"font-family:system-ui;padding:32px\">" +
            "<h2>GitHub connected</h2><p>Return to the Solar Android app. You can close this page.</p>" +
            "</body></html>"
          );
      }

      if (oauth.mobile) {
        const handoff = randomToken(32);

        await c.query(
          "INSERT INTO auth_handoffs(code_hash,session_id,expires_at) VALUES($1,$2,$3)",
          [
            hashHandoff(handoff),
            sessionId,
            new Date(Date.now() + MOBILE_HANDOFF_TTL)
          ]
        );

        await c.query("COMMIT");
        return reply.redirect(
          MOBILE_REDIRECT + "?code=" + encodeURIComponent(handoff)
        );
      }

      reply.setCookie(
        SESSION_COOKIE,
        sessionId,
        { ...cookieOptions(secure), maxAge: SESSION_TTL / 1000 }
      );

      await c.query("COMMIT");
      return reply.redirect(config.FRONTEND_ORIGIN);
    } catch (error) {
      await c.query("ROLLBACK");
      throw error;
    } finally {
      c.release();
    }
  });

  app.get("/api/auth/github/mobile/status", async (req, reply) => {
    const q = req.query as { requestId?: string };
    const requestId = String(q.requestId ?? "");

    if (requestId.length < 20) {
      return reply.code(400).send({ error: "Invalid mobile auth request" });
    }

    const c = await pool.connect();

    try {
      await c.query("BEGIN");

      const result = await c.query<any>(
        "SELECT h.session_id,h.expires_at,h.used_at,u.id,u.github_id,u.github_login,u.github_name,u.github_avatar_url " +
        "FROM auth_handoffs h " +
        "JOIN sessions s ON s.id=h.session_id " +
        "JOIN users u ON u.id=s.user_id " +
        "WHERE h.code_hash=$1 " +
        "FOR UPDATE",
        [hashHandoff(requestId)]
      );

      const row = result.rows[0];

      if (!row) {
        await c.query("COMMIT");
        return { status: "pending" };
      }

      if (row.used_at) {
        await c.query("COMMIT");
        return { status: "consumed" };
      }

      if (new Date(row.expires_at).getTime() <= Date.now()) {
        await c.query("DELETE FROM auth_handoffs WHERE code_hash=$1", [
          hashHandoff(requestId)
        ]);
        await c.query("COMMIT");
        return { status: "expired" };
      }

      await c.query(
        "UPDATE auth_handoffs SET used_at=now() WHERE code_hash=$1",
        [hashHandoff(requestId)]
      );

      await c.query("COMMIT");

      return {
        status: "complete",
        token: row.session_id,
        user: {
          id: row.id,
          githubId: row.github_id,
          login: row.github_login,
          name: row.github_name,
          avatarUrl: row.github_avatar_url
        }
      };
    } catch (error) {
      await c.query("ROLLBACK");
      throw error;
    } finally {
      c.release();
    }
  });

  app.post("/api/auth/mobile/exchange", async (req, reply) => {
    const body = req.body as { code?: string } | undefined;
    const code = String(body?.code ?? "");

    if (!code || code.length < 20) {
      return reply.code(400).send({ error: "Invalid mobile auth code" });
    }

    const c = await pool.connect();

    try {
      await c.query("BEGIN");

      const result = await c.query<any>(
        "SELECT h.session_id,s.expires_at,u.id,u.github_id,u.github_login,u.github_name,u.github_avatar_url " +
        "FROM auth_handoffs h JOIN sessions s ON s.id=h.session_id JOIN users u ON u.id=s.user_id " +
        "WHERE h.code_hash=$1 AND h.expires_at>now() AND s.expires_at>now() AND h.used_at IS NULL FOR UPDATE",
        [hashHandoff(code)]
      );

      const row = result.rows[0];

      if (!row) {
        await c.query("ROLLBACK");
        return reply
          .code(401)
          .send({ error: "Mobile auth code expired or already used" });
      }

      await c.query(
        "UPDATE auth_handoffs SET used_at=now() WHERE code_hash=$1",
        [hashHandoff(code)]
      );

      await c.query("COMMIT");

      return {
        sessionToken: row.session_id,
        user: {
          id: row.id,
          githubId: row.github_id,
          login: row.github_login,
          name: row.github_name,
          avatarUrl: row.github_avatar_url
        }
      };
    } catch (error) {
      await c.query("ROLLBACK");
      throw error;
    } finally {
      c.release();
    }
  });

  app.post("/api/auth/logout", async (req, reply) => {
    const sid = req.cookies[SESSION_COOKIE] ?? bearerToken(req);

    if (sid) {
      await pool.query("DELETE FROM sessions WHERE id=$1", [sid]);
    }

    reply.clearCookie(SESSION_COOKIE, cookieOptions(secure));
    return { ok: true };
  });

  app.get("/api/auth/me", { preHandler: requireAuth }, async (req) => {
    const user = req.authUser!;
    return {
      id: user.id,
      githubId: user.githubId,
      login: user.login,
      name: user.name,
      avatarUrl: user.avatarUrl
    };
  });
}

function bearerToken(req: FastifyRequest): string | undefined {
  const header = String(req.headers.authorization ?? "");
  return header.startsWith("Bearer ")
    ? header.slice(7).trim() || undefined
    : undefined;
}

export async function requireAuth(
  req: FastifyRequest,
  reply: FastifyReply
) {
  const sid = req.cookies[SESSION_COOKIE] ?? bearerToken(req);

  if (!sid) {
    return void reply.code(401).send({ error: "Authentication required" });
  }

  const result = await pool.query<any>(
    "SELECT s.user_id,u.github_id,u.github_login,u.github_name,u.github_avatar_url,ga.access_token_enc " +
    "FROM sessions s JOIN users u ON u.id=s.user_id JOIN github_accounts ga ON ga.user_id=u.id " +
    "WHERE s.id=$1 AND s.expires_at>now()",
    [sid]
  );

  const row = result.rows[0];

  if (!row) {
    return void reply.code(401).send({ error: "Session expired" });
  }

  req.authUser = {
    id: row.user_id,
    githubId: row.github_id,
    login: row.github_login,
    name: row.github_name,
    avatarUrl: row.github_avatar_url,
    githubToken: decryptSecret(row.access_token_enc)
  };
}
