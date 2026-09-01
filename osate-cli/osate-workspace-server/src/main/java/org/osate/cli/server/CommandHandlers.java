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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.osate.cli.server.LineProtocol.Response;

/**
 * Dispatches one parsed request to the embedded language server and produces a Response.
 */
final class CommandHandlers {

	private final LspClient lsp;
	private final List<Path> roots;
	private final Path sessionFile;
	private volatile String boundClientId;

	CommandHandlers(LspClient lsp, List<Path> roots, String boundClientId, Path sessionFile) {
		this.lsp = lsp;
		// Mutable copy: add-project / remove-project rewrite the live root set.
		this.roots = new ArrayList<>(roots);
		this.boundClientId = boundClientId;
		this.sessionFile = sessionFile;
	}

	Response dispatch(LineProtocol.Request req) {
		try {
			return switch (req.command()) {
				case "ping" -> handlePing(req.args());
				case "exit" -> {
					var maybeStickyError = checkSticky(req.clientId());
					if (maybeStickyError != null) {
						yield maybeStickyError;
					}
					yield handleExit(req.args());
				}
				case "check" -> {
					var maybeStickyError = checkSticky(req.clientId());
					if (maybeStickyError != null) {
						yield maybeStickyError;
					}
					yield handleCheck(req.args());
				}
				case "update" -> {
					var maybeStickyError = checkSticky(req.clientId());
					if (maybeStickyError != null) {
						yield maybeStickyError;
					}
					yield handleUpdate(req.args());
				}
				case "instantiate" -> {
					var maybeStickyError = checkSticky(req.clientId());
					if (maybeStickyError != null) {
						yield maybeStickyError;
					}
					yield handleInstantiate(req.args());
				}
				case "analyze-latency" -> {
					var maybeStickyError = checkSticky(req.clientId());
					if (maybeStickyError != null) {
						yield maybeStickyError;
					}
					yield handleAnalyzeLatency(req.args());
				}
				case "analyze-bus-load" -> {
					var maybeStickyError = checkSticky(req.clientId());
					if (maybeStickyError != null) {
						yield maybeStickyError;
					}
					yield handleAnalyzeBusLoad(req.args());
				}
				case "analyze-modes" -> {
					var maybeStickyError = checkSticky(req.clientId());
					if (maybeStickyError != null) {
						yield maybeStickyError;
					}
					yield handleAnalyzeModes(req.args());
				}
				case "add-project" -> {
					var maybeStickyError = checkSticky(req.clientId());
					if (maybeStickyError != null) {
						yield maybeStickyError;
					}
					yield handleAddProject(req.args());
				}
				case "remove-project" -> {
					var maybeStickyError = checkSticky(req.clientId());
					if (maybeStickyError != null) {
						yield maybeStickyError;
					}
					yield handleRemoveProject(req.args());
				}
				case "list-projects" -> {
					var maybeStickyError = checkSticky(req.clientId());
					if (maybeStickyError != null) {
						yield maybeStickyError;
					}
					yield handleListProjects(req.args());
				}
				default -> Response.err("unknown command: " + req.command());
			};
		} catch (Exception e) {
			return Response.err(e.getClass().getSimpleName() + ": " + safeMsg(e));
		}
	}

	boolean isExit(LineProtocol.Request req) {
		return "exit".equals(req.command());
	}

	void shutdownLsp() {
		lsp.shutdown();
	}

	private Response checkSticky(String clientId) {
		if (boundClientId == null) {
			boundClientId = clientId;
			return null;
		}
		if (!boundClientId.equals(clientId)) {
			return Response.err("busy: another client is connected");
		}
		return null;
	}

	private Response handlePing(List<String> args) {
		if (!args.isEmpty()) {
			return Response.err("invalid args: usage: ping");
		}
		var bound = boundClientId;
		return Response.ok(List.of(bound == null ? "OK" : "OK " + bound));
	}

	private Response handleExit(List<String> args) {
		if (!args.isEmpty()) {
			return Response.err("invalid args: usage: exit");
		}
		return Response.ok();
	}

	private Response handleCheck(List<String> args)
			throws InterruptedException, ExecutionException, TimeoutException {
		if (args.size() > 1) {
			return Response.err("invalid args: usage: check [<file>]");
		}
		if (args.size() == 1) {
			var path = resolveUnderRoot(args.get(0));
			if (path == null) {
				return Response.err("invalid args: file not in workspace");
			}
			if (!Files.isRegularFile(path)) {
				return Response.err("invalid args: no such file: " + path);
			}
			var uri = path.toUri().toString();
			var ev = new JsonObject();
			ev.addProperty("uri", uri);
			ev.addProperty("type", 2);
			var changes = new JsonArray();
			changes.add(ev);
			lsp.sendNotification("workspace/didChangeWatchedFiles", LspClient.params("changes", changes));
			lsp.waitUntilFinished();
			var diags = new HashMap<String, List<JsonObject>>();
			diags.put(uri, lsp.diagnosticsFor(uri));
			return Response.ok(DiagnosticFormatter.formatAll(diags));
		}
		return Response.ok(DiagnosticFormatter.formatAll(lsp.allDiagnostics()));
	}

	private Response handleUpdate(List<String> args)
				throws InterruptedException, ExecutionException, TimeoutException, IOException {
		if (!args.isEmpty()) {
			return Response.err("invalid args: usage: update");
		}
		var changes = new JsonArray();
		for (var root : roots) {
			try (Stream<Path> stream = Files.walk(root)) {
				stream.filter(Files::isRegularFile)
						.filter(p -> p.getFileName().toString().endsWith(".aadl"))
						.forEach(p -> {
							var ev = new JsonObject();
							ev.addProperty("uri", p.toUri().toString());
							ev.addProperty("type", 2);
							changes.add(ev);
						});
			}
		}
		if (changes.isEmpty()) {
			return Response.ok(DiagnosticFormatter.formatAll(lsp.allDiagnostics()));
		}
		lsp.sendNotification("workspace/didChangeWatchedFiles", LspClient.params("changes", changes));
		lsp.waitUntilFinished();
		return Response.ok(DiagnosticFormatter.formatAll(lsp.allDiagnostics()));
	}

	private Response handleInstantiate(List<String> args)
			throws InterruptedException, ExecutionException, TimeoutException {
		if (args.size() != 2) {
			return Response.err("invalid args: usage: instantiate <file> <impl-name>");
		}
		var path = resolveUnderRoot(args.get(0));
		if (path == null) {
			return Response.err("invalid args: file not in workspace");
		}
		if (!Files.isRegularFile(path)) {
			return Response.err("invalid args: no such file: " + path);
		}
		var uri = path.toUri().toString();
		var cmdArgs = new JsonArray();
		cmdArgs.add(uri);
		cmdArgs.add(args.get(1));
		var params = new JsonObject();
		params.addProperty("command", "aadl.instantiate");
		params.add("arguments", cmdArgs);
		var result = lsp.sendRequest("workspace/executeCommand", params).get(120, TimeUnit.SECONDS);
		var lines = new ArrayList<String>();
		if (result != null && !result.isJsonNull()) {
			lines.add(result.isJsonPrimitive() ? result.getAsString() : result.toString());
		}
		return Response.ok(lines);
	}

	private Response handleAnalyzeLatency(List<String> args)
			throws InterruptedException, ExecutionException, TimeoutException {
		if (args.size() != 6) {
			return Response.err("invalid args: usage: analyze-latency <file> [flags]");
		}
		var path = resolveUnderRoot(args.get(0));
		if (path == null) {
			return Response.err("invalid args: file not in workspace");
		}
		if (!path.getFileName().toString().endsWith(".aaxl2")) {
			return Response.err("invalid args: not an instance file (.aaxl2): " + path);
		}
		if (!Files.isRegularFile(path)) {
			return Response.err("invalid args: no such file: " + path);
		}
		var cmdArgs = new JsonArray();
		cmdArgs.add(path.toUri().toString());
		for (int i = 1; i < args.size(); i++) {
			cmdArgs.add(Boolean.parseBoolean(args.get(i)));
		}
		var params = new JsonObject();
		params.addProperty("command", "aadl.analyze.latency");
		params.add("arguments", cmdArgs);
		var result = lsp.sendRequest("workspace/executeCommand", params).get(120, TimeUnit.SECONDS);
		return Response.ok(formatAnalysisResult(result));
	}

	private Response handleAnalyzeBusLoad(List<String> args)
			throws InterruptedException, ExecutionException, TimeoutException {
		if (args.size() != 1) {
			return Response.err("invalid args: usage: analyze-bus-load <file>");
		}
		var path = resolveUnderRoot(args.get(0));
		if (path == null) {
			return Response.err("invalid args: file not in workspace");
		}
		if (!path.getFileName().toString().endsWith(".aaxl2")) {
			return Response.err("invalid args: not an instance file (.aaxl2): " + path);
		}
		if (!Files.isRegularFile(path)) {
			return Response.err("invalid args: no such file: " + path);
		}
		var cmdArgs = new JsonArray();
		cmdArgs.add(path.toUri().toString());
		var params = new JsonObject();
		params.addProperty("command", "aadl.analyze.busLoad");
		params.add("arguments", cmdArgs);
		var result = lsp.sendRequest("workspace/executeCommand", params).get(120, TimeUnit.SECONDS);
		return Response.ok(formatAnalysisResult(result));
	}

	private Response handleAnalyzeModes(List<String> args)
			throws InterruptedException, ExecutionException, TimeoutException {
		if (args.size() != 4) {
			return Response.err("invalid args: usage: analyze-modes <file> [--dot] [--html] [--smv]");
		}
		var path = resolveUnderRoot(args.get(0));
		if (path == null) {
			return Response.err("invalid args: file not in workspace");
		}
		if (!path.getFileName().toString().endsWith(".aaxl2")) {
			return Response.err("invalid args: not an instance file (.aaxl2): " + path);
		}
		if (!Files.isRegularFile(path)) {
			return Response.err("invalid args: no such file: " + path);
		}
		var cmdArgs = new JsonArray();
		cmdArgs.add(path.toUri().toString());
		for (int i = 1; i < args.size(); i++) {
			cmdArgs.add(Boolean.parseBoolean(args.get(i)));
		}
		var params = new JsonObject();
		params.addProperty("command", "aadl.analyze.reachability");
		params.add("arguments", cmdArgs);
		var result = lsp.sendRequest("workspace/executeCommand", params).get(120, TimeUnit.SECONDS);
		return Response.ok(formatAnalysisResult(result));
	}

	static List<String> formatAnalysisResult(JsonElement result) {
		var lines = new ArrayList<String>();
		if (result == null || result.isJsonNull()) {
			throw new IllegalArgumentException("analysis command returned no result");
		}
		if (!result.isJsonObject()) {
			throw new IllegalArgumentException("analysis command returned an invalid result");
		}

		var object = result.getAsJsonObject();
		var summary = stringProperty(object, "summary");
		if (summary != null && !summary.isBlank()) {
			lines.add(summary);
		}
		var reports = object.getAsJsonArray("reports");
		if (reports != null) {
			for (var reportElement : reports) {
				if (reportElement.isJsonObject()) {
					var uri = stringProperty(reportElement.getAsJsonObject(), "uri");
					if (uri != null) {
						lines.add(displayPath(uri));
					}
				}
			}
		}
		var diagnostics = object.getAsJsonArray("diagnostics");
		if (diagnostics != null) {
			for (var diagnosticElement : diagnostics) {
				if (!diagnosticElement.isJsonObject()) {
					continue;
				}
				var diagnostic = diagnosticElement.getAsJsonObject();
				var uri = stringProperty(diagnostic, "uri");
				var elementPath = stringProperty(diagnostic, "elementPath");
				var severity = stringProperty(diagnostic, "severity");
				var message = stringProperty(diagnostic, "message");
				if (uri != null && elementPath != null && severity != null && message != null) {
					lines.add(displayPath(uri) + ":" + elementPath + ": " + severity + ": " + message);
				}
			}
		}
		return lines;
	}

	private static String stringProperty(JsonObject object, String name) {
		var value = object.get(name);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
				? value.getAsString()
				: null;
	}

	private static String displayPath(String uriText) {
		try {
			var uri = java.net.URI.create(uriText);
			return "file".equalsIgnoreCase(uri.getScheme()) ? Path.of(uri).toString() : uriText;
		} catch (IllegalArgumentException e) {
			return uriText;
		}
	}

	private Response handleAddProject(List<String> args)
			throws InterruptedException, ExecutionException, TimeoutException, IOException {
		if (args.size() != 1) {
			return Response.err("invalid args: usage: add-project <root>");
		}
		var root = Path.of(args.get(0)).toAbsolutePath().normalize();
		if (!Files.isDirectory(root)) {
			return Response.err("invalid args: workspace root is not a directory: " + root);
		}
		for (var existing : roots) {
			if (existing.toAbsolutePath().normalize().equals(root)) {
				return Response.err("invalid args: root already in workspace");
			}
		}
		var event = new JsonObject();
		var added = new JsonArray();
		added.add(WorkspaceServerMain.folderJson(root));
		event.add("added", added);
		event.add("removed", new JsonArray());
		lsp.sendNotification("workspace/didChangeWorkspaceFolders",
				LspClient.params("event", event));
		lsp.waitUntilFinished();
		roots.add(root);
		rewritePersistedRoots();
		return Response.ok(DiagnosticFormatter.formatAll(lsp.allDiagnostics()));
	}

	private Response handleRemoveProject(List<String> args)
			throws InterruptedException, ExecutionException, TimeoutException, IOException {
		if (args.size() != 1) {
			return Response.err("invalid args: usage: remove-project <root>");
		}
		var root = Path.of(args.get(0)).toAbsolutePath().normalize();
		if (root.equals(roots.get(0).toAbsolutePath().normalize())) {
			return Response.err("invalid args: cannot remove first root");
		}
		Path match = null;
		for (var existing : roots) {
			if (existing.toAbsolutePath().normalize().equals(root)) {
				match = existing;
				break;
			}
		}
		if (match == null) {
			return Response.err("invalid args: root not in workspace");
		}
		var event = new JsonObject();
		var removed = new JsonArray();
		removed.add(WorkspaceServerMain.folderJson(match));
		event.add("added", new JsonArray());
		event.add("removed", removed);
		lsp.sendNotification("workspace/didChangeWorkspaceFolders",
				LspClient.params("event", event));
		lsp.waitUntilFinished();
		roots.remove(match);
		lsp.pruneDiagnosticsUnder(match);
		rewritePersistedRoots();
		return Response.ok(DiagnosticFormatter.formatAll(lsp.allDiagnostics()));
	}

	private Response handleListProjects(List<String> args) {
		if (!args.isEmpty()) {
			return Response.err("invalid args: usage: list-projects");
		}
		var lines = new ArrayList<String>(roots.size());
		for (var r : roots) {
			lines.add(r.toAbsolutePath().toString());
		}
		return Response.ok(lines);
	}

	private void rewritePersistedRoots() throws IOException {
		SessionWriter.rewriteRoots(sessionFile, roots);
		MarkerFile.rewriteRoots(MarkerFile.markerPath(roots.get(0)), roots);
	}

	private Path resolveUnderRoot(String pathArg) {
		var p = Path.of(pathArg).toAbsolutePath().normalize();
		for (var root : roots) {
			var r = root.toAbsolutePath().normalize();
			if (p.startsWith(r)) {
				return p;
			}
		}
		return null;
	}

	private static String safeMsg(Throwable t) {
		var m = t.getMessage();
		if (m == null) {
			return t.toString();
		}
		return m.replace('\r', ' ').replace('\n', ' ');
	}
}
