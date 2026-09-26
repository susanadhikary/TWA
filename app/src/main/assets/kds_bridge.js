/*
 * Injected into the KDS page at document start. Fills in browser APIs that the
 * Android System WebView lacks (Notification, print, share, blob downloads,
 * wake lock) by forwarding them to the native app over window.KdsBridge.
 */
(function () {
  'use strict';
  if (window.__kdsShim || window.top !== window) return;
  var bridge = window.KdsBridge;
  if (!bridge) return;
  window.__kdsShim = true;

  var permission = '__KDS_NOTIFICATION_PERMISSION__';
  var pending = {};
  var seq = 0;
  var active = {};

  function send(msg) {
    try { bridge.postMessage(JSON.stringify(msg)); } catch (e) { /* bridge unavailable */ }
  }

  function call(type, payload) {
    return new Promise(function (resolve) {
      var id = ++seq;
      pending[id] = resolve;
      payload = payload || {};
      payload.type = type;
      payload.id = id;
      send(payload);
    });
  }

  function makeEvent(type) {
    try { return new Event(type); } catch (e) {
      var ev = document.createEvent('Event');
      ev.initEvent(type, false, false);
      return ev;
    }
  }

  function fire(target, type, ev) {
    ev = ev || makeEvent(type);
    var handlers = [];
    if (typeof target['on' + type] === 'function') handlers.push(target['on' + type]);
    handlers = handlers.concat((target._listeners[type] || []).slice());
    handlers.forEach(function (h) {
      try {
        if (typeof h === 'function') h.call(target, ev);
        else if (h && typeof h.handleEvent === 'function') h.handleEvent(ev);
      } catch (err) {
        setTimeout(function () { throw err; }, 0);
      }
    });
  }

  // Messages from the native app.
  window.__kdsOnNative = function (raw) {
    var msg = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (msg.type === 'reply' && pending[msg.id]) {
      var resolve = pending[msg.id];
      delete pending[msg.id];
      resolve(msg.value);
    } else if (msg.type === 'permission') {
      permission = msg.value;
    } else if (msg.type === 'notificationclick') {
      var n = active[msg.tag];
      if (n) fire(n, 'click');
    }
  };

  /* ---------- Notifications ---------- */

  function KdsNotification(title, options) {
    if (!(this instanceof KdsNotification)) {
      throw new TypeError("Failed to construct 'Notification': Please use the 'new' operator.");
    }
    options = options || {};
    this.title = String(title);
    this.body = options.body ? String(options.body) : '';
    this.tag = options.tag ? String(options.tag) : 'kds-' + (++seq);
    this.data = options.data === undefined ? null : options.data;
    this.icon = options.icon || '';
    this.silent = !!options.silent;
    this.requireInteraction = !!options.requireInteraction;
    this.onclick = this.onshow = this.onclose = this.onerror = null;
    this._listeners = {};

    var self = this;
    if (permission !== 'granted') {
      setTimeout(function () { fire(self, 'error'); }, 0);
      return;
    }
    active[this.tag] = this;
    send({ type: 'notify', tag: this.tag, title: this.title, body: this.body, silent: this.silent });
    setTimeout(function () { fire(self, 'show'); }, 0);
  }

  KdsNotification.prototype.close = function () {
    if (active[this.tag] !== this) return;
    delete active[this.tag];
    send({ type: 'closeNotification', tag: this.tag });
    fire(this, 'close');
  };
  KdsNotification.prototype.addEventListener = function (type, fn) {
    (this._listeners[type] = this._listeners[type] || []).push(fn);
  };
  KdsNotification.prototype.removeEventListener = function (type, fn) {
    var list = this._listeners[type];
    if (!list) return;
    var i = list.indexOf(fn);
    if (i >= 0) list.splice(i, 1);
  };
  KdsNotification.prototype.dispatchEvent = function (ev) {
    fire(this, ev.type, ev);
    return true;
  };
  Object.defineProperty(KdsNotification, 'permission', { get: function () { return permission; } });
  KdsNotification.maxActions = 0;
  KdsNotification.requestPermission = function (callback) {
    return call('requestNotificationPermission').then(function (value) {
      permission = value;
      if (typeof callback === 'function') callback(value);
      return value;
    });
  };

  try {
    Object.defineProperty(window, 'Notification', { value: KdsNotification, writable: true, configurable: true });
  } catch (e) {
    window.Notification = KdsNotification;
  }

  if (window.ServiceWorkerRegistration) {
    ServiceWorkerRegistration.prototype.showNotification = function (title, options) {
      if (permission !== 'granted') return Promise.reject(new TypeError('No notification permission has been granted for this origin.'));
      new KdsNotification(title, options);
      return Promise.resolve();
    };
    ServiceWorkerRegistration.prototype.getNotifications = function () {
      return Promise.resolve(Object.keys(active).map(function (k) { return active[k]; }));
    };
  }

  if (navigator.permissions && navigator.permissions.query) {
    var originalQuery = navigator.permissions.query.bind(navigator.permissions);
    navigator.permissions.query = function (descriptor) {
      if (descriptor && descriptor.name === 'notifications') {
        return Promise.resolve({
          name: 'notifications',
          state: permission === 'default' ? 'prompt' : permission,
          onchange: null,
          addEventListener: function () {},
          removeEventListener: function () {}
        });
      }
      return originalQuery(descriptor);
    };
  }

  /* ---------- Printing ---------- */

  window.print = function () { send({ type: 'print' }); };

  /* ---------- Web Share ---------- */

  navigator.share = function (data) {
    data = data || {};
    return call('share', {
      title: data.title ? String(data.title) : '',
      text: data.text ? String(data.text) : '',
      url: data.url ? String(data.url) : ''
    }).then(function (ok) {
      if (!ok) throw new DOMException('Share is not available on this device', 'AbortError');
    });
  };
  navigator.canShare = function (data) { return !!data && !data.files; };

  /* ---------- Screen wake lock (the app already keeps the screen on) ---------- */

  if (!('wakeLock' in navigator)) {
    try {
      Object.defineProperty(navigator, 'wakeLock', {
        value: {
          request: function () {
            return Promise.resolve({
              released: false,
              type: 'screen',
              onrelease: null,
              release: function () { this.released = true; return Promise.resolve(); },
              addEventListener: function () {},
              removeEventListener: function () {}
            });
          }
        }
      });
    } catch (e) { /* ignore */ }
  }

  /* ---------- Blob / data: downloads ---------- */

  window.__kdsDownloadBlob = function (url, filename) {
    fetch(url).then(function (r) { return r.blob(); }).then(function (blob) {
      var reader = new FileReader();
      reader.onloadend = function () {
        send({ type: 'download', data: reader.result, filename: filename || 'download', mime: blob.type });
      };
      reader.readAsDataURL(blob);
    }).catch(function () { /* blob no longer available */ });
  };

  document.addEventListener('click', function (e) {
    var el = e.target;
    while (el && el.tagName !== 'A') el = el.parentElement;
    if (!el || !el.hasAttribute('download')) return;
    var href = el.href || '';
    if (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0) {
      e.preventDefault();
      window.__kdsDownloadBlob(href, el.getAttribute('download') || 'download');
    }
  }, true);
})();
