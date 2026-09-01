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

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

/**
 * Formats LSP diagnostics in gcc style: {@code path:line:col: severity: message}.
 * LSP positions are 0-based; we emit 1-based. Severity is lower-cased.
 */
final class DiagnosticFormatter {

	private DiagnosticFormatter() {
	}

	static List<String> formatAll(Map<String, List<JsonObject>> byUri) {
		var rows = new ArrayList<Row>();
		for (var e : byUri.entrySet()) {
			var path = uriToPath(e.getKey());
			for (var d : e.getValue()) {
				rows.add(toRow(path, d));
			}
		}
		rows.sort(Comparator.comparing((Row r) -> r.path).thenComparingInt(r -> r.line).thenComparingInt(r -> r.col));
		var out = new ArrayList<String>(rows.size());
		for (var r : rows) {
			out.add(r.path + ":" + r.line + ":" + r.col + ": " + r.severity + ": " + r.message);
		}
		return out;
	}

	private static Row toRow(String path, JsonObject d) {
		var range = d.getAsJsonObject("range");
		var start = range.getAsJsonObject("start");
		int line = start.get("line").getAsInt() + 1;
		int col = start.get("character").getAsInt() + 1;
		var severity = severityFor(d.has("severity") ? d.get("severity").getAsInt() : 1);
		var message = d.has("message") ? d.get("message").getAsString() : "";
		message = message.replace('\r', ' ').replace('\n', ' ');
		return new Row(path, line, col, severity, message);
	}

	private static String severityFor(int s) {
		return switch (s) {
			case 1 -> "error";
			case 2 -> "warning";
			case 3 -> "info";
			case 4 -> "hint";
			default -> "info";
		};
	}

	private static String uriToPath(String uri) {
		try {
			var u = URI.create(uri);
			if ("file".equalsIgnoreCase(u.getScheme())) {
				return Path.of(u).toString();
			}
		} catch (RuntimeException ignored) {
		}
		return Paths.get(uri).toString();
	}

	private record Row(String path, int line, int col, String severity, String message) {
	}
}
