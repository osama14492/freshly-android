# Freshly Order Push v2

This directory contains an isolated Cloudflare Worker for Freshly POS background order notifications.

## Runtime requirements

Configure these bindings on the Worker in Cloudflare:

- Secret `FIREBASE_SERVICE_ACCOUNT`: the complete Firebase service-account JSON (never commit it to GitHub).
- Secret `FCM_DEVICE_TOKEN`: the cashier device's current FCM registration token.
- KV namespace binding `ORDER_STATE`: use the existing namespace already used by `freshly-order-push` so both versions share idempotency keys during a controlled cutover.

The Worker polls `/pendingOrders.json` once per minute, uses a Google service-account OAuth access token for the Firebase Realtime Database REST API, and sends FCM HTTP v1 notifications. It stores `initialized` and `order:<id>` markers in KV.

## Safe rollout

1. Do not delete or disable the existing Worker before v2 is configured and tested.
2. Connect this directory to a separate Worker named `freshly-order-push-v2` using Cloudflare Workers Builds (root directory: `cloudflare/freshly-order-push-v2`).
3. Add the runtime secrets and KV binding listed above. Do not paste secrets into GitHub.
4. Check `/health` on the new Worker's workers.dev URL. It should report `configured: true`.
5. Confirm the Cron Trigger is set to every minute and inspect invocation logs for Firebase and FCM errors.
6. Only after a real test order generates exactly one notification should the old Worker be paused.

The first successful database poll initializes a baseline of existing order IDs to avoid sending a burst of old notifications. It does not modify existing order records.
