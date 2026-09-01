/*******************************************************************************
 * OSATE Command Line Interface
 *
 * Copyright 2026 Carnegie Mellon University.
 *
 * NO WARRANTY. THIS CARNEGIE MELLON UNIVERSITY AND SOFTWARE ENGINEERING INSTITUTE MATERIAL IS
 * FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON UNIVERSITY MAKES NO WARRANTIES OF ANY KIND,
 * EITHER EXPRESSED OR IMPLIED, AS TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF
 * FITNESS FOR PURPOSE OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE
 * MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT MAKE ANY WARRANTY OF ANY KIND WITH RESPECT TO
 * FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.
 *
 * Licensed under a BSD (SEI)-style license, please see LICENSE.txt
 * or contact permission@sei.cmu.edu for full terms.
 *
 * [DISTRIBUTION STATEMENT A] This material has been approved for public release and unlimited
 * distribution.  Please see Copyright notice for non-US Government use and distribution.
 *
 * This Software includes and/or makes use of Third-Party Software each subject to its own license.
 *
 * DM26-0838
 ******************************************************************************/
package org.osate.cli.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * JSON-RPC client speaking LSP Content-Length framing over a pair of in-memory pipes
 * to the embedded AADL language server.
 */
public final class LspClient {

	private final OutputStream toServer;
	private final InputStream fromServer;
	private final AtomicLong idGen = new AtomicLong();
	private final ConcurrentHashMap<String, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();
	private final Map<String, List<JsonObject>> diagnosticsByUri = new HashMap<>();
	private volatile boolean closed;

	public LspClient(OutputStream toServer, InputStream fromServer) {
		this.toServer = toServer;
		this.fromServer = fromServer;
		Thread.ofPlatform()
				.name("workspace-server-reader")
				.daemon(true)
				.start(this::pump);
	}

	public CompletableFuture<JsonElement> sendRequest(String method, JsonElement params) {
		var id = "ws-" + idGen.incrementAndGet();
		var msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.addProperty("id", id);
		msg.addProperty("method", method);
		if (params != null) {
			msg.add("params", params);
		}
		var future = new CompletableFuture<JsonElement>();
		if (closed) {
			future.completeExceptionally(new LsErrorException("language server connection closed"));
			return future;
		}
		pending.put(id, future);
		writeFrame(toServer, msg.toString().getBytes(StandardCharsets.UTF_8));
		// Guard the race where the reader failed all pending entries between our `closed`
		// check and the put above; re-drain so this future is not orphaned.
		if (closed) {
			var orphan = pending.remove(id);
			if (orphan != null) {
				orphan.completeExceptionally(new LsErrorException("language server connection closed"));
			}
		}
		return future;
	}

	public void sendNotification(String method, JsonElement params) {
		var msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.addProperty("method", method);
		if (params != null) {
			msg.add("params", params);
		}
		writeFrame(toServer, msg.toString().getBytes(StandardCharsets.UTF_8));
	}

	public JsonElement waitUntilFinished() throws InterruptedException, ExecutionException, TimeoutException {
		return sendRequest("aadlServer/waitUntilFinished", null).get(120, TimeUnit.SECONDS);
	}

	/**
	 * Block until {@code publishDiagnostics} has been received for every URI in {@code expectedUris},
	 * or until {@code timeoutMillis} elapses, or the LS connection closes. Returns the set of URIs
	 * still missing diagnostics when it returns (empty on full success).
	 *
	 * <p>Used as a startup barrier: the AADL LS publishes one diagnostics notification per
	 * workspace {@code .aadl} file on the initial build (including clean files, with an empty
	 * array), so awaiting their presence is a race-free signal that the build has settled — unlike
	 * {@code waitUntilFinished}, which keys off the next build edge and can miss the initial one.
	 */
	public List<String> awaitDiagnostics(List<String> expectedUris, long timeoutMillis)
			throws InterruptedException {
		var deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
		synchronized (diagnosticsByUri) {
			while (true) {
				var missing = new ArrayList<String>();
				for (var uri : expectedUris) {
					if (!diagnosticsByUri.containsKey(uri)) {
						missing.add(uri);
					}
				}
				if (missing.isEmpty() || closed) {
					return missing;
				}
				long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
				if (remainingMs <= 0) {
					return missing;
				}
				diagnosticsByUri.wait(Math.min(remainingMs, 250));
			}
		}
	}

	public List<JsonObject> diagnosticsFor(String uri) {
		synchronized (diagnosticsByUri) {
			var list = diagnosticsByUri.get(uri);
			return list == null ? List.of() : new ArrayList<>(list);
		}
	}

	public void pruneDiagnosticsUnder(Path root) {
		var normalizedRoot = root.toAbsolutePath().normalize();
		synchronized (diagnosticsByUri) {
			diagnosticsByUri.keySet().removeIf(uri -> isUnderRoot(uri, normalizedRoot));
		}
	}

	private static boolean isUnderRoot(String uriText, Path root) {
		try {
			var uri = URI.create(uriText);
			return "file".equalsIgnoreCase(uri.getScheme())
					&& Path.of(uri).toAbsolutePath().normalize().startsWith(root);
		} catch (RuntimeException e) {
			return false;
		}
	}

	public Map<String, List<JsonObject>> allDiagnostics() {
		synchronized (diagnosticsByUri) {
			var copy = new HashMap<String, List<JsonObject>>();
			for (var e : diagnosticsByUri.entrySet()) {
				copy.put(e.getKey(), new ArrayList<>(e.getValue()));
			}
			return copy;
		}
	}

	public void shutdown() {
		try {
			sendRequest("shutdown", null).get(5, TimeUnit.SECONDS);
		} catch (Exception ignored) {
		}
		try {
			sendNotification("exit", null);
		} catch (Exception ignored) {
		}
		closed = true;
	}

	private void pump() {
		try {
			byte[] payload;
			while ((payload = readFrame(fromServer)) != null) {
				handleFrame(payload);
			}
			// Clean EOF: the LS closed its output. Any in-flight request will never get a reply.
			failAllPending();
		} catch (IOException e) {
			if (!closed) {
				System.err.println("[workspace-server] LS reader error: " + e.getMessage());
			}
			failAllPending();
		}
	}

	/**
	 * Complete every outstanding request exceptionally so callers don't block until their
	 * {@code .get(timeout)} expires when the LS connection drops.
	 */
	private void failAllPending() {
		closed = true;
		for (var id : pending.keySet()) {
			var future = pending.remove(id);
			if (future != null) {
				future.completeExceptionally(new LsErrorException("language server connection closed"));
			}
		}
	}

	private void handleFrame(byte[] payload) {
		JsonObject msg;
		try {
			msg = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (RuntimeException e) {
			System.err.println("[workspace-server] bad JSON from LS: " + e.getMessage());
			return;
		}
		if (msg.has("method")) {
			handleServerMessage(msg);
		} else if (msg.has("id")) {
			var idEl = msg.get("id");
			if (idEl.isJsonPrimitive() && idEl.getAsJsonPrimitive().isString()) {
				var future = pending.remove(idEl.getAsString());
				if (future != null) {
					if (msg.has("error")) {
						future.completeExceptionally(new LsErrorException(msg.get("error").toString()));
					} else {
						future.complete(msg.get("result"));
					}
				}
			}
		}
	}

	private void handleServerMessage(JsonObject msg) {
		var method = msg.get("method").getAsString();
		if ("textDocument/publishDiagnostics".equals(method)) {
			var params = msg.getAsJsonObject("params");
			var uri = params.get("uri").getAsString();
			var arr = params.getAsJsonArray("diagnostics");
			var list = new ArrayList<JsonObject>();
			for (JsonElement el : arr) {
				list.add(el.getAsJsonObject());
			}
			synchronized (diagnosticsByUri) {
				diagnosticsByUri.put(uri, list);
				// Wake any thread blocked in awaitDiagnostics waiting for this URI to land.
				diagnosticsByUri.notifyAll();
			}
		}
		// Other server-initiated traffic (window/logMessage, etc.) is ignored.
		// Server-initiated requests requiring a response would be answered here, but
		// the AADL LS does not currently issue any.
	}

	// --- LSP Content-Length framing ---

	private static void writeFrame(OutputStream out, byte[] payload) {
		var header = ("Content-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
		try {
			synchronized (out) {
				out.write(header);
				out.write(payload);
				out.flush();
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static byte[] readFrame(InputStream in) throws IOException {
		int contentLength = -1;
		while (true) {
			var line = readHeaderLine(in);
			if (line == null) {
				return null;
			}
			if (line.isEmpty()) {
				break;
			}
			var colon = line.indexOf(':');
			if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
				contentLength = Integer.parseInt(line.substring(colon + 1).trim());
			}
		}
		if (contentLength < 0) {
			throw new IOException("Missing Content-Length header");
		}
		var buf = new byte[contentLength];
		int off = 0;
		while (off < contentLength) {
			int n = in.read(buf, off, contentLength - off);
			if (n < 0) {
				throw new IOException("Truncated LSP payload: expected " + contentLength
						+ " bytes, got " + off);
			}
			off += n;
		}
		return buf;
	}

	private static String readHeaderLine(InputStream in) throws IOException {
		var baos = new ByteArrayOutputStream();
		int prev = -1;
		while (true) {
			int b = in.read();
			if (b < 0) {
				if (baos.size() == 0 && prev == -1) {
					return null;
				}
				throw new IOException("Unexpected EOF while reading LSP header line");
			}
			if (prev == '\r' && b == '\n') {
				var bytes = baos.toByteArray();
				return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
			}
			baos.write(b);
			prev = b;
		}
	}

	public static final class LsErrorException extends RuntimeException {
		public LsErrorException(String message) {
			super(message);
		}
	}

	public static JsonObject params(String name, JsonElement value, Object... rest) {
		var o = new JsonObject();
		o.add(name, value);
		for (int i = 0; i < rest.length; i += 2) {
			var k = (String) rest[i];
			var v = rest[i + 1];
			if (v instanceof JsonElement je) {
				o.add(k, je);
			} else if (v instanceof String s) {
				o.addProperty(k, s);
			} else if (v instanceof Number n) {
				o.addProperty(k, n);
			} else if (v instanceof Boolean b) {
				o.addProperty(k, b);
			}
		}
		return o;
	}

	public static JsonArray array(JsonElement... els) {
		var a = new JsonArray();
		for (var el : els) {
			a.add(el);
		}
		return a;
	}
}
