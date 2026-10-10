/* Beam vendored incremental SHA-256 (FIPS 180-4). Dependency-free on purpose:
 * no CDN, no build step, and it must run both on the main thread (fallback) and
 * inside hash-worker.js. Produces byte-identical output to crypto.subtle.digest.
 *
 * Usage: const h = new BeamSha256(); h.update(uint8Array); ...; const hex = h.hex();
 * Do NOT feed the same instance after hex()/digest() — create a new one.
 */
(function (global) {
    "use strict";

    var K = new Uint32Array([
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    ]);

    function rotr(x, n) {
        return (x >>> n) | (x << (32 - n));
    }

    function BeamSha256() {
        this.h0 = 0x6a09e667; this.h1 = 0xbb67ae85;
        this.h2 = 0x3c6ef372; this.h3 = 0xa54ff53a;
        this.h4 = 0x510e527f; this.h5 = 0x9b05688c;
        this.h6 = 0x1f83d9ab; this.h7 = 0x5be0cd19;
        this.buf = new Uint8Array(64);
        this.bufLen = 0;
        this.lo = 0; // total message length in bytes, low 32 bits
        this.hi = 0; // high 32 bits
        this.done = false;
    }

    BeamSha256.prototype.update = function (data) {
        if (this.done) throw new Error("digest already finalized");
        var off = 0;
        // Track length with carry (files here are far below 2^32, but be exact anyway).
        var len = data.length;
        var lo = (this.lo + len) | 0;
        if (lo < this.lo) this.hi = (this.hi + 1) | 0;
        this.lo = lo;

        if (this.bufLen > 0) {
            var take = Math.min(64 - this.bufLen, len);
            this.buf.set(data.subarray(0, take), this.bufLen);
            this.bufLen += take;
            off = take;
            if (this.bufLen === 64) {
                compress(this, this.buf, 0);
                this.bufLen = 0;
            }
        }
        while (off + 64 <= len) {
            compress(this, data, off);
            off += 64;
        }
        if (off < len) {
            this.buf.set(data.subarray(off), 0);
            this.bufLen = len - off;
        }
        return this;
    };

    BeamSha256.prototype.digest = function () {
        if (this.done) throw new Error("digest already finalized");
        this.done = true;
        // Bit length as 64-bit big-endian.
        var bitLo = (this.lo << 3) | 0;
        var bitHi = ((this.hi << 3) | (this.lo >>> 29)) | 0;
        this.buf[this.bufLen++] = 0x80;
        if (this.bufLen > 56) {
            while (this.bufLen < 64) this.buf[this.bufLen++] = 0;
            compress(this, this.buf, 0);
            this.bufLen = 0;
        }
        while (this.bufLen < 56) this.buf[this.bufLen++] = 0;
        this.buf[56] = (bitHi >>> 24) & 0xff;
        this.buf[57] = (bitHi >>> 16) & 0xff;
        this.buf[58] = (bitHi >>> 8) & 0xff;
        this.buf[59] = bitHi & 0xff;
        this.buf[60] = (bitLo >>> 24) & 0xff;
        this.buf[61] = (bitLo >>> 16) & 0xff;
        this.buf[62] = (bitLo >>> 8) & 0xff;
        this.buf[63] = bitLo & 0xff;
        compress(this, this.buf, 0);
        var out = new Uint8Array(32);
        var hs = [this.h0, this.h1, this.h2, this.h3, this.h4, this.h5, this.h6, this.h7];
        for (var i = 0; i < 8; i++) {
            out[i * 4] = (hs[i] >>> 24) & 0xff;
            out[i * 4 + 1] = (hs[i] >>> 16) & 0xff;
            out[i * 4 + 2] = (hs[i] >>> 8) & 0xff;
            out[i * 4 + 3] = hs[i] & 0xff;
        }
        return out;
    };

    BeamSha256.prototype.hex = function () {
        var bytes = this.digest();
        var s = "";
        for (var i = 0; i < bytes.length; i++) {
            s += (bytes[i] < 16 ? "0" : "") + bytes[i].toString(16);
        }
        return s;
    };

    function compress(st, d, o) {
        var w = new Uint32Array(64);
        for (var i = 0; i < 16; i++) {
            w[i] = ((d[o + i * 4] << 24) | (d[o + i * 4 + 1] << 16)
                | (d[o + i * 4 + 2] << 8) | d[o + i * 4 + 3]) | 0;
        }
        for (i = 16; i < 64; i++) {
            var s0 = (rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)) | 0;
            var s1 = (rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)) | 0;
            w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
        }
        var a = st.h0, b = st.h1, c = st.h2, d0 = st.h3;
        var e = st.h4, f = st.h5, g = st.h6, h = st.h7;
        for (i = 0; i < 64; i++) {
            var S1 = (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) | 0;
            var ch = ((e & f) ^ (~e & g)) | 0;
            var t1 = (h + S1 + ch + K[i] + w[i]) | 0;
            var S0 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) | 0;
            var maj = ((a & b) ^ (a & c) ^ (b & c)) | 0;
            var t2 = (S0 + maj) | 0;
            h = g; g = f; f = e; e = (d0 + t1) | 0;
            d0 = c; c = b; b = a; a = (t1 + t2) | 0;
        }
        st.h0 = (st.h0 + a) | 0; st.h1 = (st.h1 + b) | 0;
        st.h2 = (st.h2 + c) | 0; st.h3 = (st.h3 + d0) | 0;
        st.h4 = (st.h4 + e) | 0; st.h5 = (st.h5 + f) | 0;
        st.h6 = (st.h6 + g) | 0; st.h7 = (st.h7 + h) | 0;
    }

    global.BeamSha256 = BeamSha256;
})(typeof self !== "undefined" ? self : this);
