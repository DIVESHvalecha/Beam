/* Beam hashing worker: streams a File in slices off the main thread so preparing a
 * large file never blocks the page or the signaling WebSocket. Computes both the
 * full-file SHA-256 (for FILE_COMPLETE) and the fileId (SHA-256 of name|size|first
 * 64 KiB) with the vendored BeamSha256 — byte-identical to crypto.subtle.digest.
 *
 * Protocol (classic worker, no modules so it works from any static host):
 *   main -> worker: { name, size, file }   (File/Blob is structured-cloneable)
 *   worker -> main: { type: "progress", done, total }
 *   worker -> main: { type: "done", fileId, checksum }
 *   worker -> main: { type: "error", message }
 */
importScripts("sha256.js");

var SLICE_BYTES = 1024 * 1024; // 1 MiB
var FILE_ID_SAMPLE_BYTES = 64 * 1024;

self.onmessage = function (event) {
    var data = event.data || {};
    try {
        var file = data.file;
        var size = data.size;
        var name = data.name;

        var fileHash = new BeamSha256();
        var reader = new FileReaderSync();
        var processed = 0;
        var head = null; // first FILE_ID_SAMPLE_BYTES for the fileId

        while (processed < size) {
            var end = Math.min(processed + SLICE_BYTES, size);
            var buf = reader.readAsArrayBuffer(file.slice(processed, end));
            var bytes = new Uint8Array(buf);
            fileHash.update(bytes);
            if (head === null) {
                head = bytes.slice(0, Math.min(bytes.length, FILE_ID_SAMPLE_BYTES));
            }
            processed = end;
            self.postMessage({ type: "progress", done: processed, total: size });
        }

        var enc = new TextEncoder();
        var prefix = enc.encode(name + "|" + size + "|");
        var idHash = new BeamSha256();
        idHash.update(prefix);
        idHash.update(head || new Uint8Array(0));

        self.postMessage({ type: "done", fileId: idHash.hex(), checksum: fileHash.hex() });
    } catch (err) {
        self.postMessage({ type: "error", message: String((err && err.message) || err) });
    }
};
