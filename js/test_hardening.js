const vm = require('vm'), fs = require('fs'), crypto = require('crypto');
const script = fs.readFileSync(__dirname + '/privacy_hardening.js', 'utf8');
let pass = 0, fail = 0;
const ok = (name, cond, extra) => { cond ? pass++ : fail++; console.log((cond ? '  PASS  ' : '  FAIL  ') + name + (extra ? '   ' + extra : '')); };

function makeEnv(opts) {
    opts = opts || {};
    const sb = {}; sb.window = sb; sb.console = console;
    class Navigator {}
    Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', { get() { return opts.cores || 8; }, configurable: true });
    Object.defineProperty(Navigator.prototype, 'deviceMemory', { get() { return opts.mem || 2; }, configurable: true });
    Object.defineProperty(Navigator.prototype, 'doNotTrack', { get() { return null; }, configurable: true });
    sb.Navigator = Navigator; sb.navigator = new Navigator();
    sb.location = { origin: 'https://' + (opts.host || 'example.com'), hostname: opts.host || 'example.com' };
    sb.crypto = { getRandomValues(a) { a[0] = opts.seed === undefined ? 12345 : opts.seed; return a; } };
    sb.document = { hidden: false, createElement() { return new sb.HTMLCanvasElement(); }, querySelector() { return null; } };
    class HTMLCanvasElement {
        constructor() { this.width = 64; this.height = 48; this.pixels = new Uint8ClampedArray(64 * 48 * 4); this._ctx = null; this.contexts = 0; }
        getContext(t) { if (t !== '2d') return null; if (!this._ctx) { this._ctx = new CanvasRenderingContext2D(this); this.contexts++; } return this._ctx; }
        toDataURL() { return 'data:' + crypto.createHash('sha1').update(Buffer.from(this.pixels)).digest('hex'); }
        toBlob(cb) { cb && cb(this.toDataURL()); }
    }
    class CanvasRenderingContext2D {
        constructor(c) { this.canvas = c; }
        drawImage(src) { this.canvas.pixels.set(src.pixels); }
        getImageData(sx, sy, sw, sh) {
            const w = Math.abs(sw), h = Math.abs(sh), x0 = Math.min(sx, sx + sw), y0 = Math.min(sy, sy + sh);
            const out = new Uint8ClampedArray(w * h * 4);
            for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) for (let c = 0; c < 4; c++)
                out[(y * w + x) * 4 + c] = this.canvas.pixels[((y0 + y) * this.canvas.width + x0 + x) * 4 + c];
            return { data: out, width: w, height: h };
        }
        putImageData(d, x, y) { this.canvas.pixels.set(d.data); }
    }
    class AudioBuffer { constructor() { this.buf = new Float32Array(1000); for (let i = 0; i < 1000; i++) this.buf[i] = Math.sin(i / 7); }
        getChannelData() { return this.buf; } }
    class GL { getParameter(p) { return 'REAL-' + p; } }
    class GL2 { getParameter(p) { return 'REAL2-' + p; } }
    Object.assign(sb, { HTMLCanvasElement, CanvasRenderingContext2D, AudioBuffer, WebGLRenderingContext: GL, WebGL2RenderingContext: GL2 });
    sb.timers = []; sb.setInterval = (fn, ms) => { sb.timers.push({ fn, ms }); return sb.timers.length; };
    vm.createContext(sb);
    return sb;
}
const run = (sb) => vm.runInContext(script, sb);
const fillRandom = (c) => { for (let i = 0; i < c.pixels.length; i++) c.pixels[i] = (i * 2654435761 >>> 24) & 255; };

console.log('--- 1. WebRTC feature detection ---');
let sb = makeEnv(); run(sb);
const t = (code) => { try { return vm.runInContext(code, sb); } catch (e) { return 'THROWS: ' + e.message; } };
ok('window.RTCPeerConnection reads as undefined (no throw)', t('window.RTCPeerConnection') === undefined);
ok('typeof RTCPeerConnection === "undefined"', t('typeof RTCPeerConnection') === 'undefined');
ok('if (window.RTCPeerConnection) is falsy', t('window.RTCPeerConnection ? "truthy" : "falsy"') === 'falsy');
ok('a || b fallback chain works', t('window.RTCPeerConnection || window.webkitRTCPeerConnection || "fallback"') === 'fallback');
ok('strict-mode assignment (polyfills) does not throw', t('(function(){"use strict"; window.RTCPeerConnection = function(){}; return "ok";})()') === 'ok');
ok('still undefined after polyfill attempt', t('window.RTCPeerConnection') === undefined);

console.log('--- 2. privacy signals / hardware caps ---');
ok('navigator.globalPrivacyControl === true', sb.navigator.globalPrivacyControl === true);
ok('navigator.doNotTrack === "1"', sb.navigator.doNotTrack === '1');
ok('hardwareConcurrency 8 -> capped to 4', sb.navigator.hardwareConcurrency === 4);
ok('deviceMemory 2 stays 2 (never raised)', sb.navigator.deviceMemory === 2);
const hi = makeEnv({ mem: 8, cores: 2 }); run(hi);
ok('deviceMemory 8 -> capped to 4', hi.navigator.deviceMemory === 4);
ok('hardwareConcurrency 2 stays 2', hi.navigator.hardwareConcurrency === 2);

console.log('--- 3. canvas farbling ---');
sb = makeEnv(); const proto = sb.HTMLCanvasElement.prototype;
const clean = new (vm.runInContext('HTMLCanvasElement', makeEnv()))(); // pristine class from a different env, only for "clean" output
const orig = makeEnv(); const origToData = orig.HTMLCanvasElement.prototype.toDataURL;   // un-hardened toDataURL
run(sb);
const cv = new sb.HTMLCanvasElement(); fillRandom(cv);
const cleanHash = origToData.call(cv);
const a1 = cv.toDataURL(), a2 = cv.toDataURL();
ok('toDataURL is stable within a page (cannot be averaged away)', a1 === a2);
ok('toDataURL differs from the un-noised canvas', a1 !== cleanHash);
ok('visible canvas is never modified', cleanHash === origToData.call(cv));
ok('toDataURL does not create a 2D context on the source canvas', cv.contexts === 0);
const sb2 = makeEnv({ seed: 999 }); run(sb2); const cv2 = new sb2.HTMLCanvasElement(); fillRandom(cv2);
ok('different page load (seed) -> different output', cv2.toDataURL() !== a1);
const sb3 = makeEnv({ host: 'other.org' }); run(sb3); const cv3 = new sb3.HTMLCanvasElement(); fillRandom(cv3);
ok('different origin -> different output', cv3.toDataURL() !== a1);
let blobOut; cv.toBlob(v => blobOut = v);
ok('toBlob is protected too', blobOut !== undefined && blobOut !== cleanHash);

const g = cv.getContext('2d');
const r1 = g.getImageData(0, 0, 10, 10), r2 = g.getImageData(0, 0, 10, 10);
ok('getImageData is stable across repeated reads', Buffer.compare(Buffer.from(r1.data), Buffer.from(r2.data)) === 0);
const big = g.getImageData(0, 0, 64, 48), part = g.getImageData(5, 5, 8, 8);
let consistent = true;
for (let y = 0; y < 8; y++) for (let x = 0; x < 8; x++) for (let c = 0; c < 4; c++)
    if (big.data[((y + 5) * 64 + x + 5) * 4 + c] !== part.data[(y * 8 + x) * 4 + c]) consistent = false;
ok('overlapping reads at different offsets agree pixel-for-pixel', consistent);
let changed = 0, maxDiff = 0, total = 64 * 48;
for (let p = 0; p < total * 4; p += 4) { let ch = false; for (let c = 0; c < 3; c++) { const d = Math.abs(big.data[p + c] - cv.pixels[p + c]); if (d) ch = true; if (d > maxDiff) maxDiff = d; } if (ch) changed++; }
ok('noise touches ~1 in 8 pixels', changed / total > 0.07 && changed / total < 0.2, '(' + (100 * changed / total).toFixed(1) + '%)');
ok('noise magnitude <= 2 levels (invisible)', maxDiff <= 2 && maxDiff >= 1, '(max ' + maxDiff + ')');
let maskedDiff = 0; for (let p = 0; p < total * 4; p++) if ((big.data[p] & 0xFC) !== (cv.pixels[p] & 0xFC)) maskedDiff++;
console.log('  INFO  known limitation: values that still differ after masking the 2 low bits =', maskedDiff, '(a tracker that quantizes can see through any imperceptible noise)');
const off = makeEnv(); const oc = new off.HTMLCanvasElement(); fillRandom(oc);
const alphaOk = (() => { for (let p = 3; p < total * 4; p += 4) if (big.data[p] !== cv.pixels[p]) return false; return true; })();
ok('alpha channel is never touched', alphaOk);

// the cancellation attack that XOR noise was vulnerable to: pipe noised pixels through a second noised read
const cvB = new sb.HTMLCanvasElement(); cvB.getContext('2d').putImageData(g.getImageData(0, 0, 64, 48), 0, 0);
ok('noise does NOT cancel when pixels are re-read through a second canvas', cvB.toDataURL() !== cleanHash);
console.log('--- 4. audio ---');
const au = new sb.AudioBuffer(); const before = Float32Array.from(au.buf);
const d1 = Float32Array.from(au.getChannelData()), d2 = Float32Array.from(au.getChannelData());
ok('getChannelData perturbs once per buffer (no noise stacking)', Buffer.compare(Buffer.from(d1.buffer), Buffer.from(d2.buffer)) === 0);
let adiff = 0, amax = 0; for (let i = 0; i < 1000; i++) { const d = Math.abs(d1[i] - before[i]); if (d > 0) adiff++; if (d > amax) amax = d; }
ok('audio noise is tiny (<= 1e-7) and only on every 100th sample', amax <= 1.01e-7 && adiff <= 10 && adiff > 0, '(changed ' + adiff + ' samples, max ' + amax.toExponential(1) + ')');

console.log('--- 5. WebGL ---');
ok('WebGL1 vendor masked', new sb.WebGLRenderingContext().getParameter(37445) === 'Generic GPU Vendor');
ok('WebGL2 renderer masked (was a leak before)', new sb.WebGL2RenderingContext().getParameter(37446) === 'Generic GPU Renderer');
ok('other WebGL params pass through', new sb.WebGL2RenderingContext().getParameter(7938) === 'REAL2-7938');

console.log('--- 6. YouTube ad skipper ---');
ok('non-YouTube page: no timer started', sb.timers.length === 0);
const yt = makeEnv({ host: 'm.youtube.com' }); run(yt);
ok('m.youtube.com: exactly one interval', yt.timers.length === 1);
const video = { muted: false, duration: 30, currentTime: 0 }, player = { classList: { contains: c => player.ad && c === 'ad-showing' } };
yt.document.querySelector = (q) => q === '.html5-video-player' ? player : q === 'video' ? video : null;
player.ad = true; yt.timers[0].fn();
ok('ad: muted and fast-forwarded to the end', video.muted === true && video.currentTime === 30);
player.ad = false; yt.timers[0].fn();
ok('after the ad: audio is un-muted again (was stuck muted before)', video.muted === false);
video.muted = true; player.ad = true; yt.timers[0].fn(); player.ad = false; yt.timers[0].fn();
ok("user's own mute is respected (not undone)", video.muted === true);
const notyt = makeEnv({ host: 'notyoutube.com' }); run(notyt);
ok('lookalike host notyoutube.com is not treated as YouTube', notyt.timers.length === 0);
const live = makeEnv({ host: 'www.youtube.com' }); run(live); const lv = { muted: false, duration: Infinity, currentTime: 0 };
live.document.querySelector = (q) => q === '.html5-video-player' ? { classList: { contains: () => true } } : q === 'video' ? lv : null;
let threw = false; try { live.timers[0].fn(); } catch (e) { threw = true; }
ok('live stream (Infinity duration) does not throw', !threw && lv.currentTime === 0);

console.log('--- 7. idempotency ---');
const wrapped = sb.HTMLCanvasElement.prototype.toDataURL; run(sb);
ok('second injection is a no-op (no double wrapping)', sb.HTMLCanvasElement.prototype.toDataURL === wrapped);

console.log('\n' + pass + ' passed, ' + fail + ' failed'); process.exit(fail ? 1 : 0);
