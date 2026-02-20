// BrainWaves v0.1 - Session Token Keeper Backend (CommonJS) — Tweaked for smoother app behavior
// Keeps original logic, but:
// - normalizes errors to JSON for all /spotify/* + /token
// - returns explicit invalid_session / token_refresh_failed where possible
// - adds /session/check helper (non-breaking, optional for app)
// - removes confusing stray comment about /spotify/queue close
// - keeps routes + behavior the same otherwise

require("dotenv").config();
const express = require("express");
const crypto = require("crypto");
const fetch = require("node-fetch");

const app = express();
app.use(express.json());

// ===== ENV =====
const CLIENT_ID = (process.env.SPOTIFY_CLIENT_ID || "").trim();
const CLIENT_SECRET = (process.env.SPOTIFY_CLIENT_SECRET || "").trim();
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL || "").trim(); // your ngrok base url
const APP_REDIRECT_URL = (process.env.APP_REDIRECT_URL || "").trim(); // brainwaves://callback

console.log("=== BrainWaves Backend Boot ===");
console.log("CLIENT_ID:", CLIENT_ID || "(missing)");
console.log("CLIENT_SECRET:", CLIENT_SECRET ? "loaded ✅" : "(missing)");
console.log("PUBLIC_BASE_URL:", PUBLIC_BASE_URL || "(missing)");
console.log("APP_REDIRECT_URL:", APP_REDIRECT_URL || "(missing)");
console.log("===============================");

if (!CLIENT_ID || !CLIENT_SECRET || !PUBLIC_BASE_URL || !APP_REDIRECT_URL) {
  console.log("ERROR: Missing env vars. Check C:\\brainwaves-backend\\.env");
  process.exit(1);
}

// ===== In-memory store (v0.1) =====
// sessionToken -> refreshToken
const sessions = new Map();

function randomToken() {
  return crypto.randomBytes(24).toString("hex");
}

function basicAuthHeader() {
  return "Basic " + Buffer.from(`${CLIENT_ID}:${CLIENT_SECRET}`).toString("base64");
}

// ---- JSON response helpers (non-breaking for success, smoother for errors) ----
function sendOk(res, payload = {}) {
  return res.json({ ok: true, ...payload });
}

function sendError(res, status, code, message, details) {
  const body = {
    ok: false,
    error: code,
    message: message || code
  };
  if (details !== undefined) body.details = details;
  return res.status(status).json(body);
}

async function spotifyText(resp) {
  // Spotify often returns empty body for 204, or JSON/text for errors
  try {
    return await resp.text();
  } catch {
    return "";
  }
}

async function spotifyJsonOrText(resp) {
  const txt = await spotifyText(resp);
  try {
    return { text: txt, json: txt ? JSON.parse(txt) : null };
  } catch {
    return { text: txt, json: null };
  }
}

async function getAccessTokenFromSession(session) {
  const refreshToken = sessions.get(session);
  if (!refreshToken) {
    const err = new Error("Invalid session (no refresh token stored)");
    err.code = "invalid_session";
    throw err;
  }

  const body = new URLSearchParams({
    grant_type: "refresh_token",
    refresh_token: refreshToken
  });

  const resp = await fetch("https://accounts.spotify.com/api/token", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      Authorization: basicAuthHeader()
    },
    body
  });

  const json = await resp.json();
  if (!resp.ok) {
    const err = new Error("Token refresh failed");
    err.code = "token_refresh_failed";
    err.details = json;
    throw err;
  }
  return json.access_token;
}

// ===== ROUTES =====

app.get("/", (req, res) => {
  res.send("BrainWaves backend alive");
});

// (Optional) quick check: does this session exist?
// Safe + non-breaking (your Android can ignore this endpoint)
app.post("/session/check", (req, res) => {
  const { session } = req.body || {};
  if (!session) return sendError(res, 400, "missing_session", "Missing session");
  const exists = sessions.has(session);
  return res.json({ ok: true, exists });
});

// 1) Login: send user to Spotify consent
app.get("/login", (req, res) => {
  const state = randomToken();

  const scope = [
    "user-read-playback-state",
    "user-modify-playback-state",
    "user-read-currently-playing"
  ].join(" ");

  const params = new URLSearchParams({
    response_type: "code",
    client_id: CLIENT_ID,
    redirect_uri: `${PUBLIC_BASE_URL}/callback`,
    scope,
    state,
    show_dialog: "true"
  });

  res.redirect("https://accounts.spotify.com/authorize?" + params.toString());
});

// 2) Callback: exchange code -> tokens, store refresh token, create session
app.get("/callback", async (req, res) => {
  const code = req.query.code;
  const error = req.query.error;

  // keep original behavior: plain text for browser flow (ok)
  if (error) return res.status(400).send("Spotify error: " + error);
  if (!code) return res.status(400).send("No code received from Spotify");

  try {
    const body = new URLSearchParams({
      grant_type: "authorization_code",
      code: String(code),
      redirect_uri: `${PUBLIC_BASE_URL}/callback`
    });

    const tokenResp = await fetch("https://accounts.spotify.com/api/token", {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        Authorization: basicAuthHeader()
      },
      body
    });

    const tokenJson = await tokenResp.json();

    if (!tokenResp.ok) {
      return res
        .status(400)
        .send("Token exchange error:\n\n" + JSON.stringify(tokenJson, null, 2));
    }

    if (!tokenJson.refresh_token) {
      return res
        .status(400)
        .send(
          "No refresh_token returned.\n\nFix: Go to https://www.spotify.com/account/apps/ and REMOVE access for BrainWaves, then try /login again."
        );
    }

    const session = randomToken();
    sessions.set(session, tokenJson.refresh_token);

    const deepLink = new URL(APP_REDIRECT_URL);
    deepLink.searchParams.set("session", session);

    res.redirect(deepLink.toString());
  } catch (e) {
    res.status(500).send(e.message);
  }
});

// 3) Get full access token (Android will call this)
app.post("/token", async (req, res) => {
  try {
    const { session } = req.body || {};
    if (!session) return sendError(res, 400, "missing_session", "Missing session");

    const access_token = await getAccessTokenFromSession(session);
    return res.json({ ok: true, access_token });
  } catch (e) {
    if (e.code === "invalid_session") {
      return sendError(res, 401, "invalid_session", "Invalid session");
    }
    if (e.code === "token_refresh_failed") {
      return sendError(res, 401, "token_refresh_failed", "Token refresh failed", e.details);
    }
    return sendError(res, 500, "server_error", e.message || "Server error");
  }
});

// 4) Skip track
app.post("/spotify/next", async (req, res) => {
  try {
    const { session } = req.body || {};
    if (!session) return sendError(res, 400, "missing_session", "Missing session");

    const accessToken = await getAccessTokenFromSession(session);

    const resp = await fetch("https://api.spotify.com/v1/me/player/next", {
      method: "POST",
      headers: { Authorization: `Bearer ${accessToken}` }
    });

    if (!resp.ok) {
      const { text, json } = await spotifyJsonOrText(resp);
      return sendError(res, resp.status, "spotify_error", "Spotify /next failed", json || text);
    }

    return sendOk(res);
  } catch (e) {
    if (e.code === "invalid_session") return sendError(res, 401, "invalid_session", "Invalid session");
    if (e.code === "token_refresh_failed") return sendError(res, 401, "token_refresh_failed", "Token refresh failed", e.details);
    return sendError(res, 500, "server_error", e.message || "Server error");
  }
});

// 5) Get currently playing track
app.post("/spotify/current", async (req, res) => {
  try {
    const { session } = req.body || {};
    if (!session) return sendError(res, 400, "missing_session", "Missing session");

    const accessToken = await getAccessTokenFromSession(session);

    const resp = await fetch("https://api.spotify.com/v1/me/player/currently-playing", {
      method: "GET",
      headers: { Authorization: `Bearer ${accessToken}` }
    });

    if (resp.status === 204) {
      // keep original semantics: not an error
      return res.json({ ok: true, message: "Nothing is currently playing" });
    }

    if (!resp.ok) {
      const { text, json } = await spotifyJsonOrText(resp);
      return sendError(res, resp.status, "spotify_error", "Spotify /currently-playing failed", json || text);
    }

    const data = await resp.json();

    return res.json({
      ok: true,
      is_playing: data.is_playing,
      track: data.item?.name,
      artist: data.item?.artists?.map((a) => a.name).join(", "),
      album: data.item?.album?.name
    });
  } catch (e) {
    if (e.code === "invalid_session") return sendError(res, 401, "invalid_session", "Invalid session");
    if (e.code === "token_refresh_failed") return sendError(res, 401, "token_refresh_failed", "Token refresh failed", e.details);
    return sendError(res, 500, "server_error", e.message || "Server error");
  }
});

// 6) Search tracks by text
app.post("/spotify/search", async (req, res) => {
  try {
    const { session, q } = req.body || {};
    if (!session) return sendError(res, 400, "missing_session", "Missing session");
    if (!q) return sendError(res, 400, "missing_q", "Missing q (search text)");

    const accessToken = await getAccessTokenFromSession(session);

    const url =
      "https://api.spotify.com/v1/search?" +
      new URLSearchParams({
        q: String(q),
        type: "track",
        limit: "5"
      }).toString();

    const resp = await fetch(url, {
      headers: { Authorization: `Bearer ${accessToken}` }
    });

    const data = await resp.json().catch(() => null);
    if (!resp.ok) {
      return sendError(res, resp.status, "spotify_error", "Spotify /search failed", data || "Unknown error");
    }

    const results = (data?.tracks?.items || []).map((t) => ({
      id: t.id,
      name: t.name,
      artist: t.artists.map((a) => a.name).join(", "),
      album: t.album.name,
      uri: t.uri
    }));

    return res.json({ ok: true, q, results });
  } catch (e) {
    if (e.code === "invalid_session") return sendError(res, 401, "invalid_session", "Invalid session");
    if (e.code === "token_refresh_failed") return sendError(res, 401, "token_refresh_failed", "Token refresh failed", e.details);
    return sendError(res, 500, "server_error", e.message || "Server error");
  }
});

// 7) Pause playback
app.post("/spotify/pause", async (req, res) => {
  try {
    const { session } = req.body || {};
    if (!session) return sendError(res, 400, "missing_session", "Missing session");

    const accessToken = await getAccessTokenFromSession(session);

    const resp = await fetch("https://api.spotify.com/v1/me/player/pause", {
      method: "PUT",
      headers: { Authorization: `Bearer ${accessToken}` }
    });

    // Spotify returns 204 for success
    if (!resp.ok && resp.status !== 204) {
      const { text, json } = await spotifyJsonOrText(resp);
      return sendError(res, resp.status, "spotify_error", "Spotify /pause failed", json || text);
    }

    return sendOk(res);
  } catch (e) {
    if (e.code === "invalid_session") return sendError(res, 401, "invalid_session", "Invalid session");
    if (e.code === "token_refresh_failed") return sendError(res, 401, "token_refresh_failed", "Token refresh failed", e.details);
    return sendError(res, 500, "server_error", e.message || "Server error");
  }
});

// 8) Resume / Play playback
app.post("/spotify/play", async (req, res) => {
  try {
    const { session } = req.body || {};
    if (!session) return sendError(res, 400, "missing_session", "Missing session");

    const accessToken = await getAccessTokenFromSession(session);

    const resp = await fetch("https://api.spotify.com/v1/me/player/play", {
      method: "PUT",
      headers: { Authorization: `Bearer ${accessToken}` }
    });

    if (!resp.ok && resp.status !== 204) {
      const { text, json } = await spotifyJsonOrText(resp);
      return sendError(res, resp.status, "spotify_error", "Spotify /play failed", json || text);
    }

    return sendOk(res);
  } catch (e) {
    if (e.code === "invalid_session") return sendError(res, 401, "invalid_session", "Invalid session");
    if (e.code === "token_refresh_failed") return sendError(res, 401, "token_refresh_failed", "Token refresh failed", e.details);
    return sendError(res, 500, "server_error", e.message || "Server error");
  }
});

// 9) Queue a track by Spotify URI
app.post("/spotify/queue", async (req, res) => {
  try {
    const { session, uri } = req.body || {};
    if (!session) return sendError(res, 400, "missing_session", "Missing session");
    if (!uri) return sendError(res, 400, "missing_uri", "Missing uri (e.g. spotify:track:...)");

    const accessToken = await getAccessTokenFromSession(session);

    const url =
      "https://api.spotify.com/v1/me/player/queue?" +
      new URLSearchParams({ uri: String(uri) }).toString();

    const resp = await fetch(url, {
      method: "POST",
      headers: { Authorization: `Bearer ${accessToken}` }
    });

    if (!resp.ok && resp.status !== 204) {
      const { text, json } = await spotifyJsonOrText(resp);
      return sendError(res, resp.status, "spotify_error", "Spotify /queue failed", json || text);
    }

    return sendOk(res, { queued: uri });
  } catch (e) {
    if (e.code === "invalid_session") return sendError(res, 401, "invalid_session", "Invalid session");
    if (e.code === "token_refresh_failed") return sendError(res, 401, "token_refresh_failed", "Token refresh failed", e.details);
    return sendError(res, 500, "server_error", e.message || "Server error");
  }
});

// 11) Seek to a position in the current track (ms)
app.post("/spotify/seek", async (req, res) => {
  try {
    const { session, position_ms } = req.body || {};
    if (!session) return sendError(res, 400, "missing_session", "Missing session");
    if (position_ms === undefined || position_ms === null) {
      return sendError(res, 400, "missing_position_ms", "Missing position_ms");
    }

    const accessToken = await getAccessTokenFromSession(session);

    const url =
      "https://api.spotify.com/v1/me/player/seek?" +
      new URLSearchParams({ position_ms: String(position_ms) }).toString();

    const resp = await fetch(url, {
      method: "PUT",
      headers: { Authorization: `Bearer ${accessToken}` }
    });

    if (!resp.ok && resp.status !== 204) {
      const { text, json } = await spotifyJsonOrText(resp);
      return sendError(res, resp.status, "spotify_error", "Spotify /seek failed", json || text);
    }

    return sendOk(res, { position_ms });
  } catch (e) {
    if (e.code === "invalid_session") return sendError(res, 401, "invalid_session", "Invalid session");
    if (e.code === "token_refresh_failed") return sendError(res, 401, "token_refresh_failed", "Token refresh failed", e.details);
    return sendError(res, 500, "server_error", e.message || "Server error");
  }
});

// 10) Get playback position + duration (for jog wheel)
app.post("/spotify/position", async (req, res) => {
  try {
    const { session } = req.body || {};
    if (!session) return sendError(res, 400, "missing_session", "Missing session");

    const accessToken = await getAccessTokenFromSession(session);

    const resp = await fetch("https://api.spotify.com/v1/me/player", {
      method: "GET",
      headers: { Authorization: `Bearer ${accessToken}` }
    });

    if (resp.status === 204) {
      return res.json({ ok: true, message: "No active device" });
    }

    const data = await resp.json().catch(() => null);
    if (!resp.ok) {
      return sendError(res, resp.status, "spotify_error", "Spotify /player failed", data || "Unknown error");
    }

    const progress_ms = data?.progress_ms ?? 0;
    const duration_ms = data?.item?.duration_ms ?? 0;

    return res.json({ ok: true, progress_ms, duration_ms });
  } catch (e) {
    if (e.code === "invalid_session") return sendError(res, 401, "invalid_session", "Invalid session");
    if (e.code === "token_refresh_failed") return sendError(res, 401, "token_refresh_failed", "Token refresh failed", e.details);
    return sendError(res, 500, "server_error", e.message || "Server error");
  }
});

app.listen(3000, () => {
  console.log("BrainWaves backend running on http://localhost:3000");
  console.log("Public URL:", PUBLIC_BASE_URL);
});
