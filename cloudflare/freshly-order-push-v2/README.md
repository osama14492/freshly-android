# Freshly Order Push Worker

This directory contains the replacement code for the existing Cloudflare Worker `freshly-order-push`. It is intentionally isolated in its own repository folder and branch so the current Worker is not changed until Cloudflare connects and deploys this branch.

## Runtime secrets

The Worker reads these existing secrets:

- `FIREBASE_SERVICE_ACCOUNT`: complete Firebase service-account JSON.
- `FCM_DEVICE_TOKEN`: current cashier-device FCM registration token.

Do not put either secret in GitHub.

## What it does

- Every minute, reads `/pendingOrders.json` from the existing Firebase Realtime Database using a Google service-account OAuth token.
- Sends FCM HTTP v1 notifications to the cashier device.
- Records deduplication markers under the additive RTDB path `/_cloudflareOrderPushState`. It does not edit, delete, or replace existing order records.
- On first successful poll, establishes a baseline of existing order IDs to avoid sending old orders as new notifications.
- Exposes `/health` to confirm that the two required secrets are present.

## Connect safely

1. Keep the existing Worker named `freshly-order-push`; do not delete it or create a second Worker.
2. Open that existing Worker > Settings > Builds > Connect.
3. Connect repository `osama14492/freshly-android`, branch `cloudflare-order-push-v2`, root directory `cloudflare/freshly-order-push-v2`.
4. Confirm the Worker name remains `freshly-order-push`.
5. Keep the existing secrets. Wrangler does not delete encrypted secrets during deploy unless explicitly told to delete them.
6. After deployment, check `/health`, then inspect Cron invocation logs. Only accept the new version after Firebase reads and FCM sends succeed.

The previous deployed Worker version remains available in Cloudflare Deployments for rollback. Do not delete old versions or Firebase data.

<!-- Build trigger: Cloudflare root directory configuration saved. -->
