/* ===== pc_remote-app.js ===== */

;(function () {
  'use strict';

  /* ---------- DOM refs ---------- */
  const video     = document.getElementById('v');
  const st        = document.getElementById('st');
  const statusEl  = document.getElementById('status');
  const badge     = document.getElementById('conn-badge');
  const countdown = document.getElementById('retry-countdown');
  const kbEl      = document.getElementById('kb');
  const kbHandle  = document.getElementById('kb-handle');
  const kbInput   = document.getElementById('kbi');

  /* ---------- State ---------- */
  let pc = null, dc = null, attempt = 0, connectTimer = null;
  const MAX_RETRY = 3;

  // ICE servers (unchanged)
  const ICE = [
    { urls: 'stun:stun.cloudflare.com:3478' },
    { urls: 'stun:stun.chat.bilibili.com:3478' },
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun.nextcloud.com:3478' },
  ];

  /* ========== Connection quality badge ========== */
  function updateBadge(state) {
    badge.className = ''; // clear previous state classes
    if (state === 'connected') {
      badge.classList.add('connected');
      st.textContent = '\u25cf P2P \u5df2\u8fde';
    } else if (state === 'checking' || state === 'new') {
      badge.classList.add('checking');
      st.textContent = '\u25cb ' + state;
    } else if (state === 'disconnected' || state === 'failed') {
      badge.classList.add('failed');
      st.textContent = '\u25cb ' + state;
    } else {
      st.textContent = '\u25cb ' + state;
    }
  }

  /* ========== Exponential backoff retry ========== */
  function backoffDelay(attemptNum) {
    return Math.min(1500 * attemptNum, 8000);
  }

  let countdownTimer = null;

  function showCountdown(seconds) {
    countdown.style.display = 'block';
    let remaining = seconds;
    countdown.textContent = remaining + 's \u540e\u91cd\u8bd5\u2026';
    clearInterval(countdownTimer);
    countdownTimer = setInterval(function () {
      remaining--;
      if (remaining <= 0) {
        clearInterval(countdownTimer);
        countdownTimer = null;
        countdown.style.display = 'none';
        return;
      }
      countdown.textContent = remaining + 's \u540e\u91cd\u8bd5\u2026';
    }, 1000);
  }

  function hideCountdown() {
    countdown.style.display = 'none';
    if (countdownTimer) { clearInterval(countdownTimer); countdownTimer = null; }
  }

  /* ========== Core connection logic ========== */
  function start() {
    attempt++;
    hideCountdown();
    pc = new RTCPeerConnection({ iceServers: ICE });

    pc.ontrack = function (e) {
      video.srcObject = e.streams[0];
      statusEl.style.display = 'none';
    };

    pc.ondatachannel = function (e) {
      dc = e.channel;
    };

    pc.onconnectionstatechange = function () {
      var s = pc.connectionState;
      updateBadge(s);
      if (s === 'connected') {
        if (connectTimer) clearTimeout(connectTimer);
        attempt = 0;
        statusEl.style.display = 'none';
      } else if (s === 'failed') {
        retry('\u8fde\u63a5\u5931\u8d25');
      }
    };

    // ICE connection state for badge
    pc.oniceconnectionstatechange = function () {
      var s = pc.iceConnectionState;
      // Map ICE states to badge classes
      var map = { completed: 'connected', connected: 'connected',
                  checking: 'checking', new: 'new',
                  disconnected: 'disconnected', failed: 'failed', closed: 'failed' };
      var badgeState = map[s] || '';
      if (badgeState) {
        badge.className = '';
        badge.classList.add(badgeState);
      }
    };

    Android.request();
    if (connectTimer) clearTimeout(connectTimer);
    connectTimer = setTimeout(function () {
      if (!pc || pc.connectionState !== 'connected') retry('\u8d85\u65f6');
    }, 15000);
  }

  function retry(reason) {
    if (connectTimer) { clearTimeout(connectTimer); connectTimer = null; }
    try { if (pc) pc.close(); } catch (e) {}
    pc = null; dc = null;

    if (attempt >= MAX_RETRY) { showGuide(reason); return; }

    var delay = backoffDelay(attempt);
    var delaySec = Math.ceil(delay / 1000);
    statusEl.style.display = 'block';
    statusEl.textContent = '\u91cd\u8fde\u4e2d(' + reason + ')\u2026 \u7b2c ' + attempt + ' \u6b21(\u5207\u6362 STUN \u91cd\u8bd5)';
    showCountdown(delaySec);
    setTimeout(start, delay);
  }

  function retryNow() {
    document.getElementById('guide').style.display = 'none';
    statusEl.style.display = 'block';
    statusEl.textContent = '\u91cd\u8fde\u4e2d\u2026';
    hideCountdown();
    attempt = 0;
    try { Android.reconnect && Android.reconnect(); } catch (e) {}
    setTimeout(start, 1500);
  }

  function showGuide(reason) {
    var g = document.getElementById('guide');
    g.innerHTML =
      '<h3>连不上主机（' + reason + '，已重试 ' + attempt + ' 次）— 按下面设置</h3>' +
      '<div class="step"><b>① 主机在跑吗？</b><br>Mac 上(同机/回环即可)：<br><code>.venv/bin/python scripts/pc_remote_webrtc.py</code></div>' +
      '<div class="step"><b>② 同一局域网</b>(手机和 Mac 同 WiFi)：<br>主机绑全网卡 + 设密码：<br><code>OCTOPUS_TENTACLE_HOST=0.0.0.0 OCTOPUS_TENTACLE_TOKEN=你的密码 .venv/bin/python scripts/pc_remote_webrtc.py</code><br>手机 设置→主机连接：地址 <code>ws://Mac内网IP:8765</code>，Auth Token 填同一密码</div>' +
      '<div class="step"><b>③ 跨网（推荐 Tailscale，免费 / 零 TURN）</b>：<br>· 手机装 Tailscale，登和 Mac 同一账号<br>· 主机同 ② 那样 <code>0.0.0.0</code> + token 起<br>· 手机 设置→主机连接：地址 <code>ws://100.106.228.62:8765</code>（Mac 的 Tailscale IP；Mac 上 <code>tailscale ip -4</code> 可查），token 同上</div>' +
      '<div class="step"><b>④ 仍连不上 = 对称 NAT</b>(STUN 打洞失败)：<br>用 ③ 的 Tailscale(自带免费中继)；或在两端 iceServers 加 <code>turn:</code>(coturn / 托管，按流量计费)</div>' +
      '<button type="button" id="guide-retry">重试</button>';
    g.style.display = 'block';
    statusEl.style.display = 'none';
    var retryBtn = g.querySelector('#guide-retry');
    if (retryBtn) retryBtn.addEventListener('click', retryNow);
  }

  /* ========== Offer callback (called by native) ========== */
  window.onOffer = async function (sdp) {
    if (!pc) return;
    try {
      await pc.setRemoteDescription({ type: 'offer', sdp: sdp });
      var ans = await pc.createAnswer();
      await pc.setLocalDescription(ans);
      await new Promise(function (r) {
        if (pc.iceGatheringState === 'complete') return r();
        pc.addEventListener('icegatheringstatechange', function () {
          if (pc.iceGatheringState === 'complete') r();
        });
        setTimeout(r, 5000);
      });
      Android.answer(pc.localDescription.sdp);
      st.textContent = '\u25cb \u5df2\u5e94\u7b54';
    } catch (err) {
      statusEl.textContent = '\u5e94\u7b54\u5931\u8d25: ' + err;
    }
  };

  /* ========== Data channel helpers ========== */
  function send(o) { if (dc && dc.readyState === 'open') dc.send(JSON.stringify(o)); }
  function key(k) { send({ action: 'key', text: k }); }

  window.toggleKb = function () {
    kbEl.style.display = (kbEl.style.display === 'block') ? 'none' : 'block';
  };

  window.sendType = function () {
    if (kbInput.value) send({ action: 'type', text: kbInput.value });
    kbInput.value = '';
  };

  /* ========== Draggable keyboard overlay ========== */
  ;(function () {
    var dragging = false, startX = 0, startY = 0, origLeft = 0, origBottom = 0;

    kbHandle.addEventListener('touchstart', function (e) {
      if (e.touches.length !== 1) return;
      dragging = true;
      var t = e.touches[0];
      startX = t.clientX;
      startY = t.clientY;
      var rect = kbEl.getBoundingClientRect();
      origLeft = rect.left;
      origBottom = window.innerHeight - rect.bottom;
      e.preventDefault();
    }, { passive: false });

    kbHandle.addEventListener('touchmove', function (e) {
      if (!dragging || e.touches.length !== 1) return;
      var t = e.touches[0];
      var dx = t.clientX - startX;
      var dy = t.clientY - startY;
      var newLeft = Math.max(0, Math.min(window.innerWidth - kbEl.offsetWidth, origLeft + dx));
      var newBottom = Math.max(0, Math.min(window.innerHeight - kbEl.offsetHeight, origBottom - dy));
      kbEl.style.left = newLeft + 'px';
      kbEl.style.right = 'auto';
      kbEl.style.bottom = newBottom + 'px';
      kbEl.style.top = 'auto';
      e.preventDefault();
    }, { passive: false });

    kbHandle.addEventListener('touchend', function () { dragging = false; });
    kbHandle.addEventListener('touchcancel', function () { dragging = false; });
  })();

  /* ========== Touch: tap / long-press / drag / two-finger scroll ========== */
  function norm(t) {
    var r = video.getBoundingClientRect();
    return {
      x: Math.min(1, Math.max(0, (t.clientX - r.left) / r.width)),
      y: Math.min(1, Math.max(0, (t.clientY - r.top) / r.height))
    };
  }

  var downN = null, lastN = null, dragging = false, moved = false;
  var twoF = false, lastCy = 0, lpTimer = null;

  video.addEventListener('touchstart', function (e) {
    if (e.touches.length >= 2) {
      twoF = true;
      lastCy = (e.touches[0].clientY + e.touches[1].clientY) / 2;
      if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; }
      return;
    }
    twoF = false;
    downN = norm(e.touches[0]);
    lastN = downN;
    moved = false;
    dragging = false;
    lpTimer = setTimeout(function () {
      if (!moved && downN) send({ action: 'rightclick', x: downN.x, y: downN.y });
      lpTimer = null;
    }, 550);
  }, { passive: true });

  video.addEventListener('touchmove', function (e) {
    if (twoF && e.touches.length >= 2) {
      var cy = (e.touches[0].clientY + e.touches[1].clientY) / 2;
      var dy = cy - lastCy;
      lastCy = cy;
      var r = video.getBoundingClientRect();
      send({ action: 'scroll', x: 0, y: dy / r.height });
      return;
    }
    var n = norm(e.touches[0]);
    lastN = n;
    if (!dragging && (Math.abs(n.x - downN.x) > 0.02 || Math.abs(n.y - downN.y) > 0.02)) {
      dragging = true;
      moved = true;
      if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; }
      send({ action: 'down', x: downN.x, y: downN.y });
    }
    if (dragging) send({ action: 'move', x: n.x, y: n.y });
  }, { passive: true });

  video.addEventListener('touchend', function (e) {
    if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; }
    if (twoF) { twoF = false; return; }
    if (dragging) {
      send({ action: 'up', x: (lastN || downN).x, y: (lastN || downN).y });
    } else if (downN && !moved) {
      send({ action: 'tap', x: downN.x, y: downN.y });
    }
    downN = null;
    dragging = false;
  }, { passive: true });

  /* ========== Pinch-to-zoom ========== */
  ;(function () {
    var initialDist = 0, initialScale = 1, currentScale = 1;
    var pinching = false;

    function distance(t1, t2) {
      var dx = t1.clientX - t2.clientX;
      var dy = t1.clientY - t2.clientY;
      return Math.sqrt(dx * dx + dy * dy);
    }

    video.addEventListener('touchstart', function (e) {
      if (e.touches.length === 2) {
        initialDist = distance(e.touches[0], e.touches[1]);
        initialScale = currentScale;
        pinching = true;
      }
    }, { passive: true });

    video.addEventListener('touchmove', function (e) {
      if (!pinching || e.touches.length !== 2) return;
      var d = distance(e.touches[0], e.touches[1]);
      if (initialDist > 0) {
        currentScale = Math.max(1, Math.min(5, initialScale * (d / initialDist)));
        video.style.transform = 'scale(' + currentScale + ')';
        video.style.transformOrigin = 'center center';
      }
    }, { passive: true });

    video.addEventListener('touchend', function (e) {
      // If only one finger remains, stop pinch but keep zoom
      if (e.touches.length < 2) {
        pinching = false;
      }
    }, { passive: true });

    // Double-tap to reset zoom
    var lastTapTime = 0;
    video.addEventListener('touchend', function (e) {
      if (e.touches.length > 0) return; // ignore if fingers still on screen
      var now = Date.now();
      if (now - lastTapTime < 300 && !dragging && !moved) {
        currentScale = 1;
        video.style.transform = 'scale(1)';
      }
      lastTapTime = now;
    }, { passive: true });
  })();

  /* ========== Top bar / keyboard button bindings ========== */
  function bind(id, handler) {
    var el = document.getElementById(id);
    if (el) el.addEventListener('click', handler);
  }
  bind('btn-back',      function () { Android.back(); });
  bind('btn-backspace', function () { key('backspace'); });
  bind('btn-esc',       function () { key('esc'); });
  bind('btn-kb',        function () { window.toggleKb(); });
  bind('btn-send-type', function () { window.sendType(); });
  bind('btn-enter',     function () { key('enter'); });
  bind('btn-help',      function () {
    var g = document.getElementById('guide');
    g.style.display = (g.style.display === 'block') ? 'none' : 'block';
  });

  /* ========== Boot ========== */
  start();

})();
