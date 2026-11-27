/* FRESHLY Local Sound & Notification Listener for pendingOrders */
(function () {
  'use strict';

  const NOTIFIED_KEY = 'freshly_notified_orders_v1';
  let notifiedIds = new Set();

  try {
    const saved = JSON.parse(localStorage.getItem(NOTIFIED_KEY) || '[]');
    if (Array.isArray(saved)) {
      notifiedIds = new Set(saved.map(String));
    }
  } catch (_) {}

  function saveNotified() {
    try {
      localStorage.setItem(NOTIFIED_KEY, JSON.stringify(Array.from(notifiedIds).slice(-300)));
    } catch (_) {}
  }

  function playFallbackBeep() {
    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (!AudioCtx) return;
      const ctx = new AudioCtx();
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(880, ctx.currentTime);
      gain.gain.setValueAtTime(0.15, ctx.currentTime);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start();
      osc.stop(ctx.currentTime + 0.25);
    } catch (_) {}
  }

  function triggerOrderSoundAlert(orderKey, data) {
    if (!orderKey) return;
    const keyStr = String(orderKey);
    if (notifiedIds.has(keyStr)) return;

    const status = data && data.status ? String(data.status).toLowerCase() : 'pending';
    if (status && status !== 'pending' && status !== 'new') return;

    notifiedIds.add(keyStr);
    saveNotified();

    const customer = (data && (data.customerName || data['customer name'] || data.customer)) || 'عميل';
    const total = (data && data.total != null && data.total !== '') ? ` — الإجمالي: ${data.total}` : '';
    const title = 'طلب جديد في FRESHLY';
    const body = `طلب من ${customer}${total}`;

    if (window.FreshlyNative && typeof window.FreshlyNative.showLocalOrderAlert === 'function') {
      try {
        window.FreshlyNative.showLocalOrderAlert(keyStr, title, body);
        return;
      } catch (_) {}
    }

    playFallbackBeep();
    if ('Notification' in window && Notification.permission === 'granted') {
      try {
        new Notification(title, { body });
      } catch (_) {}
    }
  }

  window.freshlyNotifyNewOrder = triggerOrderSoundAlert;

  let initialSnapshotDone = false;
  let attached = false;

  function bindPendingOrdersListener() {
    if (attached) return;
    if (!window.firebase || typeof window.firebase.database !== 'function') return;

    try {
      const ref = window.firebase.database().ref('pendingOrders');
      attached = true;

      ref.once('value', snap => {
        const val = snap && typeof snap.val === 'function' ? snap.val() : null;
        if (val && typeof val === 'object') {
          Object.keys(val).forEach(k => notifiedIds.add(String(k)));
          saveNotified();
        }
        initialSnapshotDone = true;
      }).catch(() => {
        initialSnapshotDone = true;
      });

      ref.on('child_added', snap => {
        if (!snap || !snap.key) return;
        const val = typeof snap.val === 'function' ? snap.val() : null;
        if (!initialSnapshotDone) {
          notifiedIds.add(String(snap.key));
          saveNotified();
          return;
        }
        triggerOrderSoundAlert(snap.key, val);
      });
    } catch (_) {
      attached = false;
    }
  }

  window.addEventListener('load', () => {
    bindPendingOrdersListener();
    setInterval(bindPendingOrdersListener, 3000);
  });
})();
