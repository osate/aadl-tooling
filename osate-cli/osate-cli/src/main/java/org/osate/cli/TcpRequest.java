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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends one line-delimited request to a workspace server and reads the response until EOF.
 * The response is split on the first blank line: lines before are the payload, lines after
 * (specifically, the next line) are the status — {@code OK} or {@code ERR <message>}.
 */
public final class TcpRequest {

	/**
	 * Read timeout for the response. Set above the server-side 120s command ceilings
	 * (instantiate / analyze-latency / analyze-bus-load / analyze-modes) so legitimate long commands are not cut off,
	 * while still bounding a server that accepts the connection then stalls.
	 */
	private static final int READ_TIMEOUT_MS = 130_000;

	private TcpRequest() {
	}

	public record Result(List<String> lines, String status) {
		public boolean ok() {
			return "OK".equals(status);
		}
	}

	public static Result send(int port, String requestLine) throws IOException {
		try (var sock = new Socket()) {
			sock.connect(new InetSocketAddress("127.0.0.1", port), 5000);
			sock.setSoTimeout(READ_TIMEOUT_MS);
			try (var out = new PrintWriter(
					new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), false);
					var in = new BufferedReader(
							new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
				out.print(requestLine);
				out.print('\n');
				out.flush();
				sock.shutdownOutput();
				var lines = new ArrayList<String>();
				String status = "";
				boolean inStatus = false;
				String line;
				while ((line = in.readLine()) != null) {
					if (!inStatus) {
						if (line.isEmpty()) {
							inStatus = true;
						} else {
							lines.add(line);
						}
					} else if (status.isEmpty()) {
						status = line;
					}
				}
				return new Result(lines, status);
			}
		}
	}

	public static String quote(String s) {
		boolean needsQuote = s.isEmpty();
		for (int i = 0; !needsQuote && i < s.length(); i++) {
			var c = s.charAt(i);
			if (Character.isWhitespace(c) || c == '"' || c == '\\') {
				needsQuote = true;
			}
		}
		if (!needsQuote) {
			return s;
		}
		var sb = new StringBuilder(s.length() + 2);
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			var c = s.charAt(i);
			if (c == '"' || c == '\\') {
				sb.append('\\');
			}
			sb.append(c);
		}
		sb.append('"');
		return sb.toString();
	}
}
