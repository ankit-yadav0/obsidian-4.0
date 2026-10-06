(function () {
    'use strict';
    // Idempotent: the script may be injected twice (document-start API + onPageStarted fallback).
    try {
        if (window.__obsidianHardened) return;
        Object.defineProperty(window, '__obsidianHardened', { value: true });
    } catch (e) { return; }

    var noop = function () {};

    // ---- 1. WebRTC: reading these globals must NOT throw (feature detection has to degrade
    //         gracefully), it just reports "not supported". Stops the STUN-based IP leak.
    ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel'].forEach(function (name) {
        try {
            Object.defineProperty(window, name, {
                get: function () { return undefined; },
                set: noop,
                configurable: false
            });
        } catch (e) {}
    });

    // ---- 2. Global Privacy Control / Do Not Track as JS properties. The HTTP headers are only
    //         attached to navigations the app starts itself, the JS signals cover everything else.
    try { Object.defineProperty(Navigator.prototype, 'globalPrivacyControl', { get: function () { return true; }, configurable: true }); } catch (e) {}
    try { Object.defineProperty(Navigator.prototype, 'doNotTrack', { get: function () { return '1'; }, configurable: true }); } catch (e) {}

    // ---- 3. Canvas / audio / WebGL "farbling": tiny, deterministic noise. Same input gives the same
    //         output for the whole page (so it cannot be averaged away by reading repeatedly),
    //         but it differs per page load and per origin.
    var seed = 0;
    try { var rnd = new Uint32Array(1); crypto.getRandomValues(rnd); seed = rnd[0] >>> 0; }
    catch (e) { seed = (Date.now() ^ Math.floor(Math.random() * 4294967296)) >>> 0; }
    var origin = '';
    try { origin = String(location.origin || location.hostname || ''); } catch (e) {}
    for (var i = 0; i < origin.length; i++) { seed = Math.imul(seed ^ origin.charCodeAt(i), 16777619) >>> 0; }

    function mix(n) {
        n = (n ^ seed) >>> 0;
        n = Math.imul(n ^ (n >>> 16), 2246822507) >>> 0;
        n = Math.imul(n ^ (n >>> 13), 3266489909) >>> 0;
        return (n ^ (n >>> 16)) >>> 0;
    }

    // Adds +-1 or +-2 to one colour channel in roughly one pixel out of eight. The change is ADDITIVE on purpose:
    // an XOR-style change cancels itself when a page pipes already-noised pixels through a second
    // read (getImageData -> putImageData -> toDataURL), which would hand the tracker the clean canvas.
    function farble(data, x0, y0, w) {
        if (!w) return;
        for (var p = 0, len = data.length; p < len; p += 4) {
            var idx = p >>> 2;
            var px = x0 + (idx % w), py = y0 + ((idx / w) | 0);
            var h = mix((Math.imul(py, 73856093) ^ Math.imul(px, 19349663)) >>> 0);
            if ((h & 7) === 0) {
                var c = (h >>> 3) % 3;
                var delta = 1 + ((h >>> 9) & 1);
                data[p + c] = data[p + c] + (((h >>> 8) & 1) ? delta : -delta);   // Uint8ClampedArray clamps to 0..255
            }
        }
    }

    try {
        var origGetContext = HTMLCanvasElement.prototype.getContext;
        var origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
        var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
        var origToBlob = HTMLCanvasElement.prototype.toBlob;

        // Noise is applied to a COPY, so the visible canvas is never modified and no 2D context
        // is created on a canvas the page wants to use for WebGL.
        var copyWithNoise = function (src) {
            try {
                var w = src.width, h = src.height;
                if (!w || !h || w * h > 16777216) return null;
                var c = document.createElement('canvas');
                c.width = w; c.height = h;
                var g = origGetContext.call(c, '2d');
                if (!g) return null;
                g.drawImage(src, 0, 0);
                var d = origGetImageData.call(g, 0, 0, w, h);
                farble(d.data, 0, 0, w);
                g.putImageData(d, 0, 0);
                return c;
            } catch (e) { return null; }
        };

        HTMLCanvasElement.prototype.toDataURL = function () {
            var c = copyWithNoise(this);
            return origToDataURL.apply(c || this, arguments);
        };
        if (origToBlob) {
            HTMLCanvasElement.prototype.toBlob = function () {
                var c = copyWithNoise(this);
                return origToBlob.apply(c || this, arguments);
            };
        }
        CanvasRenderingContext2D.prototype.getImageData = function (sx, sy, sw, sh) {
            var d = origGetImageData.apply(this, arguments);
            try { farble(d.data, Math.min(sx, sx + sw), Math.min(sy, sy + sh), d.width); } catch (e) {}
            return d;
        };
    } catch (e) {}

    try {
        if (typeof OffscreenCanvas !== 'undefined' && OffscreenCanvas.prototype.convertToBlob) {
            var origConvert = OffscreenCanvas.prototype.convertToBlob;
            OffscreenCanvas.prototype.convertToBlob = function () {
                try {
                    var w = this.width, h = this.height;
                    if (w && h && w * h <= 16777216) {
                        var oc = new OffscreenCanvas(w, h);
                        var g = oc.getContext('2d');
                        g.drawImage(this, 0, 0);
                        var d = g.getImageData(0, 0, w, h);
                        farble(d.data, 0, 0, w);
                        g.putImageData(d, 0, 0);
                        return origConvert.apply(oc, arguments);
                    }
                } catch (e) {}
                return origConvert.apply(this, arguments);
            };
        }
    } catch (e) {}

    try {
        var origGetChannelData = AudioBuffer.prototype.getChannelData;
        var perturbed = (typeof WeakSet === 'function') ? new WeakSet() : null;
        AudioBuffer.prototype.getChannelData = function () {
            var data = origGetChannelData.apply(this, arguments);
            // Once per underlying buffer: repeated calls must not stack noise on noise.
            if (perturbed && !perturbed.has(data.buffer)) {
                perturbed.add(data.buffer);
                for (var k = 0; k < data.length; k += 100) {
                    data[k] = data[k] + ((mix(k) / 4294967296) - 0.5) * 2e-7;
                }
            }
            return data;
        };
    } catch (e) {}

    // WebGL1 and WebGL2 both leak the real GPU through the debug-renderer-info parameters.
    var patchGL = function (proto) {
        try {
            var origGetParameter = proto.getParameter;
            proto.getParameter = function (param) {
                if (param === 37445) return 'Generic GPU Vendor';
                if (param === 37446) return 'Generic GPU Renderer';
                return origGetParameter.apply(this, arguments);
            };
        } catch (e) {}
    };
    if (typeof WebGLRenderingContext !== 'undefined') patchGL(WebGLRenderingContext.prototype);
    if (typeof WebGL2RenderingContext !== 'undefined') patchGL(WebGL2RenderingContext.prototype);

    // Capping (never raising) keeps these coarse and avoids telling a 2 GB phone's sites it has more memory.
    try {
        var hc = navigator.hardwareConcurrency;
        if (hc) Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', { get: function () { return Math.min(hc, 4); }, configurable: true });
    } catch (e) {}
    try {
        var dm = navigator.deviceMemory;
        if (dm) Object.defineProperty(Navigator.prototype, 'deviceMemory', { get: function () { return Math.min(dm, 4); }, configurable: true });
    } catch (e) {}

    // ---- 4. YouTube ad skipping. YouTube serves video ads from the same hosts as the video, so no
    //         host blocklist can catch them; the only thing that works is watching the player state.
    //         This is an arms race: YouTube can change these class names at any time.
    try {
        var host = location.hostname;
        if (host === 'youtube.com' || host.slice(-12) === '.youtube.com' || host === 'youtu.be') {
            var mutedByUs = false;
            setInterval(function () {
                try {
                    if (document.hidden) return;
                    var player = document.querySelector('.html5-video-player');
                    var isAd = !!player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'));
                    var video = document.querySelector('video');
                    if (isAd) {
                        if (video) {
                            if (!video.muted) { video.muted = true; mutedByUs = true; }
                            if (isFinite(video.duration) && video.duration > 0) video.currentTime = video.duration;
                        }
                        var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                        if (skipBtn) skipBtn.click();
                        var overlayClose = document.querySelector('.ytp-ad-overlay-close-button');
                        if (overlayClose) overlayClose.click();
                    } else if (mutedByUs && video) {
                        video.muted = false;   // we only undo OUR mute, never the user's own
                        mutedByUs = false;
                    }
                } catch (e) {}
            }, 300);
        }
    } catch (e) {}
})();
