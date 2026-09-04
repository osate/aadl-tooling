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

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import org.osate.cli.server.MarkerFile.MarkerData;

/**
 * Bootstrap for the long-lived workspace server. Embeds the AADL language server on an isolated
 * URLClassLoader, drives it over in-memory pipes, and exposes a line-delimited TCP protocol on
 * 127.0.0.1.
 */
public final class WorkspaceServerMain {

	private static final String SERVER_MAIN = "org.osate.aadl.ls.RunAadl2Server";
	private static final int PIPE_BUFFER = 1 << 16;

	private WorkspaceServerMain() {
	}

	private record Args(int serverTimeoutSec, Path logFile, String clientId, List<Path> roots,
			int requestedPort, Path sessionBase) {
	}

	public static void main(String[] argv) throws Exception {
		// Capture original stdout BEFORE anything else writes to it. The port handshake line
		// is the only thing that ever lands on this stream.
		var portOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
		var startupErr = System.err;

		var args = parseArgs(argv);

		// Replace process-level stdout/stderr with the log file so the embedded LS's chatter
		// doesn't pollute the handshake stream.
		Files.createDirectories(args.logFile().getParent());
		var logStream = new PrintStream(new FileOutputStream(args.logFile().toFile(), true), true,
				StandardCharsets.UTF_8);
		System.setErr(logStream);
		System.setOut(logStream);
		startupErr.flush();
		startupErr.close();

		// Pipes between us and the embedded LS.
		var proxyToServer = new PipedOutputStream();
		var serverStdin = new PipedInputStream(proxyToServer, PIPE_BUFFER);
		var serverStdout = new PipedOutputStream();
		var proxyFromServer = new PipedInputStream(serverStdout, PIPE_BUFFER);

		// The LS reads/writes via System.in/System.out. The PrintStream wrapping serverStdout
		// must NOT be auto-flush, otherwise every byte triggers a flush and breaks framing.
		System.setIn(serverStdin);
		System.setOut(new PrintStream(serverStdout, false, StandardCharsets.UTF_8));

		startEmbeddedServer();

		var lsp = new LspClient(proxyToServer, proxyFromServer);

		initializeServer(lsp, args.roots());

		var ss = new ServerSocket();
		ss.setReuseAddress(true);
		try {
			ss.bind(new java.net.InetSocketAddress("127.0.0.1", args.requestedPort()));
		} catch (java.net.BindException e) {
			System.err.println("[osate-workspace-server] cannot bind requested port "
					+ args.requestedPort() + ": " + e.getMessage());
			System.exit(1);
		}
		int port = ss.getLocalPort();

		var firstRoot = args.roots().get(0);
		var marker = MarkerFile.markerPath(firstRoot);
		var rootStrings = new ArrayList<String>(args.roots().size());
		for (var r : args.roots()) {
			rootStrings.add(r.toAbsolutePath().toString());
		}
		MarkerFile.write(marker, new MarkerData(port, ProcessHandle.current().pid(),
				firstRoot.toAbsolutePath().toString(), List.copyOf(rootStrings)));
		final Path sessionFile = args.sessionBase() == null
				? null
				: args.sessionBase().resolve(port + ".json");
		if (sessionFile != null) {
			Files.createDirectories(sessionFile.getParent());
		}
		// The marker file is short-lived (workspace-keyed; just helps `init` reuse a server).
		Runtime.getRuntime().addShutdownHook(new Thread(() -> MarkerFile.delete(marker),
				"ws-marker-cleanup"));

		// Hand the port back to the parent, then close that stream — we will not write to it again.
		portOut.println(port);
		portOut.close();

		var handlers = new CommandHandlers(lsp, args.roots(), args.clientId(), sessionFile);
		var lastRequest = new long[] { System.nanoTime() };

		startWatchdog(args.serverTimeoutSec(), lastRequest, lsp, sessionFile);

		System.err.println("[osate-workspace-server] listening on 127.0.0.1:" + port + " roots="
				+ args.roots() + " timeout=" + args.serverTimeoutSec() + "s");

		acceptLoop(ss, handlers, lastRequest, sessionFile);
	}

	private static Args parseArgs(String[] argv) {
		int serverTimeout = 300;
		Path log = null;
		String clientId = null;
		int requestedPort = 0;
		Path sessionBase = null;
		var roots = new ArrayList<Path>();
		for (int i = 0; i < argv.length; i++) {
			switch (argv[i]) {
				case "--server-timeout" -> {
					if (i + 1 >= argv.length) {
						throw new IllegalArgumentException("--server-timeout requires a value");
					}
					serverTimeout = Integer.parseInt(argv[++i]);
				}
				case "--log" -> {
					if (i + 1 >= argv.length) {
						throw new IllegalArgumentException("--log requires a value");
					}
					log = Path.of(argv[++i]).toAbsolutePath();
				}
				case "--client-id" -> {
					if (i + 1 >= argv.length) {
						throw new IllegalArgumentException("--client-id requires a value");
					}
					clientId = argv[++i];
				}
				case "--port" -> {
					if (i + 1 >= argv.length) {
						throw new IllegalArgumentException("--port requires a value");
					}
					requestedPort = Integer.parseInt(argv[++i]);
				}
				case "--session-base" -> {
					if (i + 1 >= argv.length) {
						throw new IllegalArgumentException("--session-base requires a value");
					}
					sessionBase = Path.of(argv[++i]).toAbsolutePath();
				}
				default -> {
					var p = Path.of(argv[i]).toAbsolutePath().normalize();
					if (!Files.isDirectory(p)) {
						throw new IllegalArgumentException("workspace root is not a directory: " + p);
					}
					roots.add(p);
				}
			}
		}
		if (roots.isEmpty()) {
			throw new IllegalArgumentException("at least one workspace root is required");
		}
		if (log == null) {
			log = roots.get(0).resolve(".osate-cli")
					.resolve("server-" + ProcessHandle.current().pid() + ".log");
		}
		return new Args(serverTimeout, log, clientId, List.copyOf(roots), requestedPort, sessionBase);
	}

	private static void startEmbeddedServer() throws IOException {
		var cl = buildServerClassLoader();
		Thread.ofPlatform()
				.name("aadl-language-server")
				.daemon(false)
				.start(() -> {
					Thread.currentThread().setContextClassLoader(cl);
					try {
						var main = Class.forName(SERVER_MAIN, true, cl);
						main.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
					} catch (Throwable e) {
						System.err.println("[osate-workspace-server] embedded server thread failed; exiting");
						e.printStackTrace(System.err);
						System.exit(1);
					}
				});
	}

	private static ClassLoader buildServerClassLoader() throws IOException {
		var pluginsDir = locatePluginsDir();
		var selfJar = selfJarPath();
		var urls = new ArrayList<URL>();
		try (var stream = Files.newDirectoryStream(pluginsDir, "*.jar")) {
			for (var jar : stream) {
				// Don't expose our own classes (and our private Gson copy) to the LS classloader.
				if (jar.equals(selfJar) || !isServerRuntimeJar(jar)) {
					continue;
				}
				urls.add(jar.toUri().toURL());
			}
		}
		if (urls.isEmpty()) {
			throw new IllegalStateException("No AADL language server plugin jars found in " + pluginsDir);
		}
		System.err.println("[osate-workspace-server] loaded " + urls.size() + " plugin jars from " + pluginsDir);
		return new URLClassLoader("aadl-server", urls.toArray(URL[]::new),
				ClassLoader.getPlatformClassLoader());
	}

	static boolean isServerRuntimeJar(Path jar) {
		return !jar.getFileName().toString().startsWith("org.antlr.antlr4-runtime_");
	}

	/**
	 * Locate the directory containing the AADL LS plugin jars. Honors the
	 * {@code aadl.plugins.dir} system property (used by the failsafe IT to point at the Tycho
	 * output directly). Otherwise falls back to the directory containing this jar — which in
	 * the assembled {@code dist/} layout is {@code lib/}, alongside our own jar.
	 */
	private static Path locatePluginsDir() {
		var override = System.getProperty("aadl.plugins.dir");
		if (override != null && !override.isBlank()) {
			var p = Path.of(override).toAbsolutePath();
			if (!Files.isDirectory(p)) {
				throw new IllegalStateException("aadl.plugins.dir does not exist: " + p);
			}
			return p;
		}
		var dir = selfJarPath().getParent();
		if (dir == null || !Files.isDirectory(dir)) {
			throw new IllegalStateException("Cannot locate plugins directory next to " + selfJarPath());
		}
		return dir;
	}

	private static Path selfJarPath() {
		var src = WorkspaceServerMain.class.getProtectionDomain().getCodeSource();
		if (src != null && src.getLocation() != null) {
			try {
				var p = Path.of(src.getLocation().toURI());
				if (Files.isRegularFile(p)) {
					return p;
				}
			} catch (java.net.URISyntaxException ignored) {
			}
		}
		throw new IllegalStateException("Cannot locate our own jar via CodeSource: " + src);
	}

	private static void initializeServer(LspClient lsp, List<Path> roots) throws Exception {
		var folders = new JsonArray();
		for (var root : roots) {
			folders.add(folderJson(root));
		}
		var initParams = new JsonObject();
		initParams.add("processId", JsonNull.INSTANCE);
		initParams.addProperty("rootUri", roots.get(0).toUri().toString());
		initParams.add("workspaceFolders", folders);
		initParams.add("capabilities", clientCapabilities());

		lsp.sendRequest("initialize", initParams).get(60, java.util.concurrent.TimeUnit.SECONDS);
		lsp.sendNotification("initialized", new JsonObject());

		// Barrier: block until the initial workspace build has published diagnostics for every
		// .aadl file. The accept loop (and the port handshake) only starts after this returns, so
		// the first client command sees a settled build instead of empty/stale diagnostics.
		var expectedUris = aadlFileUris(roots);
		if (!expectedUris.isEmpty()) {
			var missing = lsp.awaitDiagnostics(expectedUris, INITIAL_BUILD_TIMEOUT_MILLIS);
			if (!missing.isEmpty()) {
				System.err.println("[osate-workspace-server] initial build barrier timed out; "
						+ missing.size() + " of " + expectedUris.size()
						+ " files have no diagnostics yet (proceeding anyway)");
			}
		}
	}

	private static final long INITIAL_BUILD_TIMEOUT_MILLIS = 120_000L;

	/** Collect {@code file://} URIs for every {@code .aadl} source file under the given roots. */
	private static List<String> aadlFileUris(List<Path> roots) throws IOException {
		var uris = new ArrayList<String>();
		for (var root : roots) {
			if (!Files.isDirectory(root)) {
				continue;
			}
			try (var stream = Files.walk(root)) {
				stream.filter(Files::isRegularFile)
						.filter(p -> p.getFileName().toString().endsWith(".aadl"))
						.forEach(p -> uris.add(p.toUri().toString()));
			}
		}
		return uris;
	}

	static JsonObject folderJson(Path root) {
		var f = new JsonObject();
		f.addProperty("uri", root.toUri().toString());
		f.addProperty("name", root.getFileName() == null ? root.toString() : root.getFileName().toString());
		return f;
	}

	private static JsonObject clientCapabilities() {
		var caps = new JsonObject();
		var workspace = new JsonObject();
		workspace.addProperty("workspaceFolders", true);
		var watched = new JsonObject();
		watched.addProperty("dynamicRegistration", true);
		workspace.add("didChangeWatchedFiles", watched);
		var executeCommand = new JsonObject();
		executeCommand.addProperty("dynamicRegistration", true);
		workspace.add("executeCommand", executeCommand);
		caps.add("workspace", workspace);
		var textDocument = new JsonObject();
		var publishDiagnostics = new JsonObject();
		publishDiagnostics.addProperty("relatedInformation", false);
		textDocument.add("publishDiagnostics", publishDiagnostics);
		caps.add("textDocument", textDocument);
		return caps;
	}

	private static void startWatchdog(int serverTimeoutSec, long[] lastRequest, LspClient lsp,
			Path sessionFile) {
		Thread.ofPlatform().name("ws-watchdog").daemon(true).start(() -> {
			long timeoutNs = serverTimeoutSec * 1_000_000_000L;
			while (true) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					return;
				}
				long idle = System.nanoTime() - lastRequest[0];
				if (idle > timeoutNs) {
					System.err.println("[osate-workspace-server] idle timeout reached; shutting down");
					try {
						lsp.shutdown();
					} catch (Exception ignored) {
					}
					SessionWriter.delete(sessionFile);
					System.exit(0);
				}
			}
		});
	}

	private static void acceptLoop(ServerSocket ss, CommandHandlers handlers, long[] lastRequest,
			Path sessionFile) {
		while (true) {
			Socket client;
			try {
				client = ss.accept();
			} catch (IOException e) {
				System.err.println("[osate-workspace-server] accept failed: " + e.getMessage());
				return;
			}
			handleClient(client, handlers, lastRequest, sessionFile);
		}
	}

	private static void handleClient(Socket client, CommandHandlers handlers, long[] lastRequest,
			Path sessionFile) {
		try (client;
				var reader = new BufferedReader(
						new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
				var writer = new PrintWriter(
						new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), false)) {
			var line = reader.readLine();
			if (line == null) {
				return;
			}
			LineProtocol.Request req;
			try {
				req = LineProtocol.parse(line);
			} catch (RuntimeException e) {
				LineProtocol.writeResponse(writer, LineProtocol.Response.err("invalid args: " + e.getMessage()));
				return;
			}
			lastRequest[0] = System.nanoTime();
			var resp = handlers.dispatch(req);
			LineProtocol.writeResponse(writer, resp);
			lastRequest[0] = System.nanoTime();
			if (handlers.isExit(req) && "OK".equals(resp.status())) {
				writer.flush();
				try {
					client.close();
				} catch (IOException ignored) {
				}
				System.err.println("[osate-workspace-server] exit requested; shutting down");
				SessionWriter.delete(sessionFile);
				try {
					handlers.shutdownLsp();
				} catch (Exception ignored) {
				}
				System.exit(0);
			}
		} catch (IOException e) {
			System.err.println("[osate-workspace-server] client error: " + e.getMessage());
		}
	}

}
