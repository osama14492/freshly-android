const DB_URL = "https://freshly-pos-default-rtdb.firebaseio.com";
const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.database https://www.googleapis.com/auth/firebase.messaging";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return Response.json({
        ok: true,
        worker: "freshly-order-push-v2",
        configured: Boolean(env.FIREBASE_SERVICE_ACCOUNT && env.FCM_DEVICE_TOKEN && env.ORDER_STATE)
      });
    }

    return new Response("Not found", { status: 404 });
  },

  async scheduled(_event, env, ctx) {
    ctx.waitUntil(checkAndNotify(env));
  }
};

function base64url(value) {
  const bytes = value instanceof Uint8Array ? value : new TextEncoder().encode(value);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
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

async function getAccessToken(serviceAccount) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = base64url(JSON.stringify({
    iss: serviceAccount.client_email,
    scope: FCM_SCOPE,
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600
  }));
  const unsigned = header + "." + claim;

  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(serviceAccount.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned)
  );
  const assertion = unsigned + "." + base64url(new Uint8Array(signature));

  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion
    })
  });

  const result = await response.json();
  if (!response.ok || !result.access_token) {
    throw new Error("Google OAuth token failed (" + response.status + "): " + JSON.stringify(result));
  }
  return result.access_token;
}

async function checkAndNotify(env) {
  if (!env.FIREBASE_SERVICE_ACCOUNT) throw new Error("Missing FIREBASE_SERVICE_ACCOUNT secret");
  if (!env.FCM_DEVICE_TOKEN) throw new Error("Missing FCM_DEVICE_TOKEN secret");
  if (!env.ORDER_STATE) throw new Error("Missing ORDER_STATE KV binding");

  let serviceAccount;
  try {
    serviceAccount = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT);
  } catch {
    throw new Error("FIREBASE_SERVICE_ACCOUNT must contain the complete service-account JSON");
  }
  if (!serviceAccount.client_email || !serviceAccount.private_key || !serviceAccount.project_id) {
    throw new Error("Service-account JSON is missing client_email, private_key, or project_id");
  }

  const accessToken = await getAccessToken(serviceAccount);
  const dbResponse = await fetch(DB_URL + "/pendingOrders.json?access_token=" + encodeURIComponent(accessToken), {
    headers: { "Accept": "application/json" }
  });
  const raw = await dbResponse.text();
  if (!dbResponse.ok) {
    throw new Error("Firebase read failed (" + dbResponse.status + "): " + raw.slice(0, 700));
  }

  let orders;
  try {
    orders = raw === "null" ? null : JSON.parse(raw);
  } catch {
    throw new Error("Firebase returned invalid JSON: " + raw.slice(0, 300));
  }
  if (!orders || typeof orders !== "object") return;

  const entries = Object.entries(orders);
  const initialized = await env.ORDER_STATE.get("initialized");

  // First successful read establishes a baseline and prevents old orders
  // from generating a burst of notifications after deployment.
  if (!initialized) {
    for (const [id] of entries) {
      await env.ORDER_STATE.put("order:" + id, "seen");
    }
    await env.ORDER_STATE.put("initialized", "true");
    return;
  }

  for (const [id, order] of entries) {
    if (!order || typeof order !== "object") continue;

    const key = "order:" + id;
    if (await env.ORDER_STATE.get(key)) continue;

    const title = "طلب جديد من Freshly";
    const orderNumber = String(order.orderNumber || order.invoiceNumber || order.number || id);
    const customer = String(order.customerName || order.name || order.customer || "عميل جديد");
    const body = "طلب رقم " + orderNumber + " - " + customer;

    const message = {
      message: {
        token: env.FCM_DEVICE_TOKEN,
        notification: { title, body },
        data: {
          type: "new_order",
          orderId: String(id),
          orderNumber,
          customerName: customer
        },
        android: {
          priority: "HIGH",
          notification: {
            channel_id: "freshly_orders",
            sound: "default"
          }
        }
      }
    };

    const sendResponse = await fetch(
      "https://fcm.googleapis.com/v1/projects/" + encodeURIComponent(serviceAccount.project_id) + "/messages:send",
      {
        method: "POST",
        headers: {
          Authorization: "Bearer " + accessToken,
          "Content-Type": "application/json"
        },
        body: JSON.stringify(message)
      }
    );

    const sendText = await sendResponse.text();
    if (!sendResponse.ok) {
      throw new Error("FCM send failed (" + sendResponse.status + "): " + sendText.slice(0, 700));
    }

    await env.ORDER_STATE.put(key, "sent");
  }
}
