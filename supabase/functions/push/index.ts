// MayChat - Edge Function "push"
//
// Called by the database (not by the app) each time a message is saved.
// It asks the database who should be notified, then sends the notification
// through Firebase Cloud Messaging.
//
// Needs ONE secret set in Supabase (Edge Functions -> Secrets):
//   FCM_SERVICE_ACCOUNT = the full content of the Firebase service account
//                         JSON file (Project settings -> Service accounts).
// SUPABASE_URL and the Supabase keys are provided automatically.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";

// Server-side Supabase key: the new secret key if present, else the legacy one.
function serverKey(): string {
  try {
    const keys = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}");
    if (typeof keys.default === "string" && keys.default.length > 0) return keys.default;
  } catch (_e) {
    // fall through to the legacy key
  }
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
}

// Calls one of the database functions from supabase_migration_06_push.sql.
async function rpc(name: string, args: Record<string, unknown>): Promise<any> {
  const key = serverKey();
  const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${name}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      apikey: key,
      Authorization: `Bearer ${key}`,
    },
    body: JSON.stringify(args),
  });
  if (!res.ok) throw new Error(`database call ${name} failed: ${res.status} ${await res.text()}`);
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

// A web address of the sender's avatar that works for one hour, or null when
// the sender has no avatar. The "avatars" storage is private, so Firebase gets
// a temporary signed address. Any problem here returns null: the notification
// is then sent without a picture, exactly as before.
// (Needs supabase_migration_23_push_avatar.sql.)
async function senderAvatarUrl(senderId: unknown, secret: string): Promise<string | null> {
  try {
    if (!senderId) return null;
    const path = await rpc("push_avatar", { p_user: String(senderId), p_secret: secret });
    if (typeof path !== "string" || path.length === 0) return null;

    const key = serverKey();
    const encoded = path.split("/").map(encodeURIComponent).join("/");
    const res = await fetch(`${SUPABASE_URL}/storage/v1/object/sign/avatars/${encoded}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        apikey: key,
        Authorization: `Bearer ${key}`,
      },
      body: JSON.stringify({ expiresIn: 3600 }),
    });
    if (!res.ok) return null;
    const data = await res.json();
    const signed = data.signedURL ?? data.signedUrl;
    if (typeof signed !== "string" || signed.length === 0) return null;
    return `${SUPABASE_URL}/storage/v1${signed.startsWith("/") ? "" : "/"}${signed}`;
  } catch (_e) {
    return null;
  }
}

// ---- Google sign-in for the service account (needed to call Firebase) ----

function base64url(input: Uint8Array | string): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToDer(pem: string): ArrayBuffer {
  const clean = pem.replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  const binary = atob(clean);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

let cachedToken: { value: string; expiresAt: number } | null = null;

async function googleAccessToken(account: { client_email: string; private_key: string }): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.expiresAt - 60 > now) return cachedToken.value;

  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64url(JSON.stringify({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(`${header}.${claims}`),
  );
  const assertion = `${header}.${claims}.${base64url(new Uint8Array(signature))}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!res.ok) throw new Error(`Google sign-in failed: ${res.status} ${await res.text()}`);
  const data = await res.json();
  cachedToken = { value: data.access_token, expiresAt: now + (data.expires_in ?? 3600) };
  return cachedToken.value;
}

// Builds what is sent to Firebase for one phone.
//
// - A normal message: a notification that Android shows by itself, even when
//   the app is closed (unchanged from before).
// - A call (the chat line starts with the telephone emoji): a "data" message
//   instead. It wakes the app, which then rings and shows the incoming call
//   over the lock screen. It expires after 30 seconds so a late delivery does
//   not ring for a call that is long over.
function fcmMessage(token: string, payload: any, avatarUrl: string | null): Record<string, unknown> {
  const isCall = typeof payload.body === "string" && payload.body.startsWith("\u{1F4DE}");

  if (isCall) {
    return {
      token,
      data: {
        type: "call",
        conversation_id: String(payload.conversation_id),
        sender_id: String(payload.sender_id),
        sender_name: String(payload.title),
      },
      android: { priority: "HIGH", ttl: "30s" },
    };
  }

  return {
    token,
    // Shown by Android itself, even when the app is closed.
    notification: { title: payload.title, body: payload.body },
    // Read by the app when the notification is tapped.
    data: {
      conversation_id: String(payload.conversation_id),
      sender_id: String(payload.sender_id),
      sender_name: String(payload.title),
    },
    android: {
      priority: "HIGH",
      notification: {
        channel_id: "messages",
        // Same tag = one notification per conversation, updated in place.
        tag: String(payload.conversation_id),
        // The number shown on the app icon.
        notification_count: Number(payload.unread) || 1,
        sound: "default",
        // The sender's picture, shown on the notification (when they have one).
        ...(avatarUrl ? { image: avatarUrl } : {}),
      },
    },
  };
}

// ---- The function itself ----

Deno.serve(async (req: Request) => {
  try {
    if (req.method !== "POST") return new Response("Method not allowed", { status: 405 });

    const secret = req.headers.get("x-push-secret") ?? "";
    const { message_id } = await req.json();
    if (!secret || !message_id) return new Response("Bad request", { status: 400 });

    // The database checks the password and returns null if it is wrong.
    const payload = await rpc("push_payload", { p_message_id: message_id, p_secret: secret });
    if (!payload || !Array.isArray(payload.tokens) || payload.tokens.length === 0) {
      return Response.json({ sent: 0 });
    }

    const rawAccount = Deno.env.get("FCM_SERVICE_ACCOUNT");
    if (!rawAccount) throw new Error("Secret FCM_SERVICE_ACCOUNT is not set");
    const account = JSON.parse(rawAccount);
    const accessToken = await googleAccessToken(account);

    // The sender's picture for a normal message (a call rings without one).
    const isCall = typeof payload.body === "string" && payload.body.startsWith("\u{1F4DE}");
    const avatarUrl = isCall ? null : await senderAvatarUrl(payload.sender_id, secret);

    let sent = 0;
    for (const token of payload.tokens as string[]) {
      const res = await fetch(
        `https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${accessToken}`,
          },
          body: JSON.stringify({ message: fcmMessage(token, payload, avatarUrl) }),
        },
      );
      if (res.ok) {
        sent++;
      } else {
        const text = await res.text();
        console.error(`FCM error ${res.status}: ${text}`);
        // The app was uninstalled or the token expired: forget this token.
        if (res.status === 404 || text.includes("UNREGISTERED")) {
          await rpc("remove_push_token", { p_token: token, p_secret: secret });
        }
      }
    }
    return Response.json({ sent });
  } catch (e) {
    console.error(e);
    return new Response(String(e), { status: 500 });
  }
});
