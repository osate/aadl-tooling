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
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Atomic read/write and liveness probe for the {@code .osate-cli/server.json} marker.
 */
final class MarkerFile {

	private MarkerFile() {
	}

	/**
	 * {@code workspaceRoot} is the first root (the one the marker is keyed on); {@code roots}
	 * is the full ordered root set, used by {@code osate-cli init} to warn when a reuse request
	 * targets a different workspace than the live server.
	 */
	record MarkerData(int port, long pid, String workspaceRoot, List<String> roots) {
	}

	static Path markerPath(Path firstRoot) {
		return firstRoot.resolve(".osate-cli").resolve("server.json");
	}

	static void write(Path file, MarkerData data) throws IOException {
		Files.createDirectories(file.getParent());
		var tmp = file.resolveSibling(file.getFileName() + ".tmp");
		var json = new JsonObject();
		json.addProperty("port", data.port());
		json.addProperty("pid", data.pid());
		json.addProperty("workspaceRoot", data.workspaceRoot());
		var roots = new JsonArray();
		for (var r : data.roots()) {
			roots.add(r);
		}
		json.add("roots", roots);
		Files.writeString(tmp, json.toString(), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	static void rewriteRoots(Path file, List<Path> newRoots) throws IOException {
		var data = read(file);
		var rootStrings = new ArrayList<String>(newRoots.size());
		for (var root : newRoots) {
			rootStrings.add(root.toAbsolutePath().normalize().toString());
		}
		write(file, new MarkerData(data.port(), data.pid(), data.workspaceRoot(), List.copyOf(rootStrings)));
	}

	static MarkerData read(Path file) throws IOException {
		var text = Files.readString(file, StandardCharsets.UTF_8);
		var json = JsonParser.parseString(text).getAsJsonObject();
		var roots = new ArrayList<String>();
		if (json.has("roots")) {
			for (var el : json.getAsJsonArray("roots")) {
				roots.add(el.getAsString());
			}
		}
		return new MarkerData(
				json.get("port").getAsInt(),
				json.get("pid").getAsLong(),
				json.has("workspaceRoot") ? json.get("workspaceRoot").getAsString() : "",
				List.copyOf(roots));
	}

	static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
		}
	}

	static boolean isLive(MarkerData data) {
		var alive = ProcessHandle.of(data.pid()).map(ProcessHandle::isAlive).orElse(false);
		if (!alive) {
			return false;
		}
		try (var s = new Socket()) {
			s.connect(new InetSocketAddress("127.0.0.1", data.port()), 250);
			s.setSoTimeout(1000);
			var out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), false);
			out.print("probe ping\n");
			out.flush();
			var in = s.getInputStream();
			var buf = new byte[256];
			var sb = new StringBuilder();
			int n;
			while ((n = in.read(buf)) > 0) {
				sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
				if (sb.indexOf("OK") >= 0) {
					return true;
				}
			}
			return sb.toString().contains("OK");
		} catch (IOException e) {
			return false;
		}
	}
}
