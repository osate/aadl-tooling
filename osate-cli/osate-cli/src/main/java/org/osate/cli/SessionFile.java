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
package org.osate.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Per-port session file at {@code <home>/.osate-cli/sessions/<port>.json}, holding workspace
 * server ownership and native-supervisor metadata used for lifecycle cleanup.
 * The base directory is {@code System.getProperty("osate.cli.home", System.getProperty("user.home"))}.
 */
public final class SessionFile {

	private SessionFile() {
	}

	public record Session(int port, List<Path> roots, String clientId, int serverTimeoutSec,
			long pid, long startedAtEpochMs, String supervisorKind, String supervisorId) {
		public Session {
			roots = List.copyOf(roots);
			if (roots.isEmpty()) {
				throw new IllegalArgumentException("session roots must not be empty");
			}
			clientId = Objects.requireNonNull(clientId, "clientId");
			supervisorKind = Objects.requireNonNull(supervisorKind, "supervisorKind");
			supervisorId = Objects.requireNonNull(supervisorId, "supervisorId");
			if (!"direct".equals(supervisorKind) && !"launchd".equals(supervisorKind)
					&& !"systemd".equals(supervisorKind)) {
				throw new IllegalArgumentException("unknown supervisor kind: " + supervisorKind);
			}
			if (!"direct".equals(supervisorKind) && supervisorId.isBlank()) {
				throw new IllegalArgumentException("managed supervisor ID must not be blank");
			}
			if ("direct".equals(supervisorKind) && !supervisorId.isEmpty()) {
				throw new IllegalArgumentException("direct supervisor ID must be empty");
			}
		}

		public Session(int port, List<Path> roots, String clientId, int serverTimeoutSec,
				long pid, long startedAtEpochMs) {
			this(port, roots, clientId, serverTimeoutSec, pid, startedAtEpochMs, "direct", "");
		}
	}

	public static Path baseDir() {
		var home = System.getProperty("osate.cli.home", System.getProperty("user.home"));
		return Path.of(home).resolve(".osate-cli").resolve("sessions");
	}

	public static Path path(int port) {
		return baseDir().resolve(port + ".json");
	}

	public static void write(Session s) throws IOException {
		var file = path(s.port());
		Files.createDirectories(file.getParent());
		var tmp = file.resolveSibling(file.getFileName() + ".tmp");
		var json = new JsonObject();
		json.addProperty("port", s.port());
		json.addProperty("clientId", s.clientId());
		json.addProperty("serverTimeoutSec", s.serverTimeoutSec());
		json.addProperty("pid", s.pid());
		json.addProperty("startedAtEpochMs", s.startedAtEpochMs());
		json.addProperty("supervisorKind", s.supervisorKind());
		json.addProperty("supervisorId", s.supervisorId());
		var roots = new JsonArray();
		for (var r : s.roots()) {
			roots.add(r.toAbsolutePath().toString());
		}
		json.add("roots", roots);
		Files.writeString(tmp, json.toString(), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	public static Optional<Session> read(int port) {
		var file = path(port);
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			var json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
					.getAsJsonObject();
			var roots = new ArrayList<Path>();
			for (var e : json.getAsJsonArray("roots")) {
				roots.add(Path.of(e.getAsString()));
			}
			var kind = json.has("supervisorKind") ? json.get("supervisorKind").getAsString() : "direct";
			var id = json.has("supervisorId") ? json.get("supervisorId").getAsString() : "";
			return Optional.of(new Session(
					json.get("port").getAsInt(),
					List.copyOf(roots),
					json.get("clientId").getAsString(),
					json.get("serverTimeoutSec").getAsInt(),
					json.get("pid").getAsLong(),
					json.get("startedAtEpochMs").getAsLong(), kind, id));
		} catch (RuntimeException | IOException e) {
			return Optional.empty();
		}
	}

	public static void delete(int port) {
		try {
			Files.deleteIfExists(path(port));
		} catch (IOException ignored) {
		}
	}
}
