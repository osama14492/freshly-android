const PROJECT_ID = "freshly-pos";
const DATABASE_URL = "https://freshly-pos-default-rtdb.firebaseio.com";

function base64Url(bytes) {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToArrayBuffer(pem) {
  const body = pem
    .replace(/-----BEGIN PRIVATE KEY-----/g, "")
    .replace(/-----END PRIVATE KEY-----/g, "")
    .replace(/\s/g, "");
  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

let cachedAccessToken = null;
let cachedTokenExpiry = 0;

async function getGoogleAccessToken(serviceAccount) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedAccessToken && cachedTokenExpiry > now + 60) return cachedAccessToken;

  const header = base64Url(new TextEncoder().encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = base64Url(
    new TextEncoder().encode(
      JSON.stringify({
        iss: serviceAccount.client_email,
        scope:
          "https://www.googleapis.com/auth/firebase.database https://www.googleapis.com/auth/firebase.messaging",
        aud: serviceAccount.token_uri || "https://oauth2.googleapis.com/token",
        iat: now,
        exp: now + 3600,
      })
    )
  );
  const unsigned = `${header}.${claims}`;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(serviceAccount.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signature = new Uint8Array(
    await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned))
  );
  const assertion = `${unsigned}.${base64Url(signature)}`;

  const response = await fetch(serviceAccount.token_uri || "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) {
    throw new Error(`Google OAuth failed: ${response.status} ${await response.text()}`);
  }
  const data = await response.json();
  cachedAccessToken = data.access_token;
  cachedTokenExpiry = now + Number(data.expires_in || 3600);
  return cachedAccessToken;
}

async function readPendingOrders(accessToken) {
  const response = await fetch(`${DATABASE_URL}/pendingOrders.json`, {
    headers: { authorization: `Bearer ${accessToken}` },
  });
  if (!response.ok) {
    throw new Error(`Realtime Database read failed: ${response.status} ${await response.text()}`);
  }
  return (await response.json()) || {};
}

function buildOrderSummaryBody(orderKey, orderData) {
  const safeKey = String(orderKey);
  if (!orderData || typeof orderData !== "object") {
    return `وصل طلب جديد رقم #${safeKey} من المنيو الإلكتروني`;
  }
  const customerName = orderData.customerName || orderData.customer || orderData.name || "";
  const total = orderData.total || orderData.grandTotal || orderData.amount || "";
  const parts = [`طلب جديد #${safeKey}`];
  if (customerName) parts.push(`العميل: ${customerName}`);
  if (total !== "") parts.push(`الإجمالي: ${total}`);
  return parts.join(" · ");
}

async function sendPush(accessToken, deviceToken, orderKey, orderData) {
  const title = "FRESHLY · طلب جديد";
  const body = buildOrderSummaryBody(orderKey, orderData);

  const response = await fetch(
    `https://fcm.googleapis.com/v1/projects/${PROJECT_ID}/messages:send`,
    {
      method: "POST",
      headers: {
        authorization: `Bearer ${accessToken}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token: deviceToken,
          data: {
            orderId: String(orderKey),
            type: "new_order",
            title,
            body,
            sentAt: String(Date.now()),
          },
          android: {
            priority: "high",
            ttl: "120s",
          },
        },
      }),
    }
  );
  if (!response.ok) {
    throw new Error(`FCM send failed: ${response.status} ${await response.text()}`);
  }
}

export default {
  async scheduled(controller, env, ctx) {
    try {
      if (!env.FIREBASE_SERVICE_ACCOUNT || !env.FCM_DEVICE_TOKEN || !env.ORDER_STATE) {
        throw new Error("Missing FIREBASE_SERVICE_ACCOUNT, FCM_DEVICE_TOKEN, or ORDER_STATE binding");
      }
      const serviceAccount = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT);
      const accessToken = await getGoogleAccessToken(serviceAccount);
      const orders = await readPendingOrders(accessToken);
      const keys = Object.keys(orders);

      const bootstrapped = await env.ORDER_STATE.get("BOOTSTRAPPED");
      if (!bootstrapped) {
        for (const key of keys) {
          await env.ORDER_STATE.put(`order:${encodeURIComponent(key)}`, "seen");
        }
        await env.ORDER_STATE.put("BOOTSTRAPPED", "1");
        console.log(`Baseline created for ${keys.length} existing orders; no notifications sent.`);
        return;
      }

      let sent = 0;
      for (const key of keys) {
        const stateKey = `order:${encodeURIComponent(key)}`;
        if (await env.ORDER_STATE.get(stateKey)) continue;
        await sendPush(accessToken, env.FCM_DEVICE_TOKEN, key, orders[key]);
        await env.ORDER_STATE.put(stateKey, "sent", { expirationTtl: 86400 * 7 });
        sent++;
      }
      console.log(`Checked ${keys.length} orders; sent ${sent} new-order notification(s).`);
    } catch (error) {
      console.error("FRESHLY scheduled notification run failed:", error);
      throw error;
    }
  },
};
