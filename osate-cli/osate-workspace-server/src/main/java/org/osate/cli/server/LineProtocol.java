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
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Line-delimited request/response framing used between osate-cli and osate-workspace-server.
 *
 * Request line: {@code <client-id> <command> [<arg> ...]\n}.
 * Whitespace-bearing tokens are double-quoted; inside quotes {@code \"} and {@code \\}
 * are the only escapes.
 *
 * Response: zero or more output lines, then a blank line, then either {@code OK} or
 * {@code ERR <message>}.
 */
final class LineProtocol {

	private LineProtocol() {
	}

	record Request(String clientId, String command, List<String> args) {
	}

	record Response(List<String> lines, String status) {
		static Response ok(List<String> lines) {
			return new Response(lines, "OK");
		}

		static Response ok() {
			return new Response(List.of(), "OK");
		}

		static Response err(String message) {
			return new Response(List.of(), "ERR " + message);
		}
	}

	static Request parse(String line) {
		var tokens = tokenize(line);
		if (tokens.size() < 2) {
			throw new IllegalArgumentException("expected at least <client-id> <command>");
		}
		return new Request(tokens.get(0), tokens.get(1), tokens.subList(2, tokens.size()));
	}

	private static List<String> tokenize(String line) {
		var out = new ArrayList<String>();
		var sb = new StringBuilder();
		boolean inQuotes = false;
		boolean haveToken = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (inQuotes) {
				if (c == '\\' && i + 1 < line.length()) {
					char next = line.charAt(i + 1);
					if (next == '"' || next == '\\') {
						sb.append(next);
						i++;
						continue;
					}
					sb.append(c);
				} else if (c == '"') {
					inQuotes = false;
				} else {
					sb.append(c);
				}
			} else {
				if (c == '"') {
					inQuotes = true;
					haveToken = true;
				} else if (Character.isWhitespace(c)) {
					if (haveToken) {
						out.add(sb.toString());
						sb.setLength(0);
						haveToken = false;
					}
				} else {
					sb.append(c);
					haveToken = true;
				}
			}
		}
		if (inQuotes) {
			throw new IllegalArgumentException("unterminated quoted argument");
		}
		if (haveToken) {
			out.add(sb.toString());
		}
		return out;
	}

	static void writeResponse(Writer w, Response resp) throws IOException {
		for (var l : resp.lines()) {
			w.write(l);
			w.write('\n');
		}
		w.write('\n');
		w.write(resp.status());
		w.write('\n');
		w.flush();
	}
}
