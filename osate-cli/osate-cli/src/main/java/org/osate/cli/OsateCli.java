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
import java.net.ConnectException;
import java.net.SocketException;

/**
 * Entry point for the {@code osate-cli} client. Runs local project-management commands
 * directly, delegates {@code init} to {@link ServerSpawner}, and sends language operations
 * through {@link TcpRequest}. If the workspace server is unavailable, the client cleans stale
 * session state when it can safely do so and requires the user to run {@code init} again.
 */
public final class OsateCli {

	private OsateCli() {
	}

	public static void main(String[] argv) {
		ArgParser.Args args;
		try {
			args = ArgParser.parse(argv);
		} catch (RuntimeException e) {
			System.err.println(e.getMessage());
			System.exit(2);
			return;
		}
		try {
			if ("help".equals(args.command())) {
				System.out.println(ArgParser.help());
				return;
			}
				if ("usage".equals(args.command())) {
					System.out.println(ArgParser.usage());
					return;
				}
				if ("version".equals(args.command())) {
					System.out.println(ArgParser.versionLine());
					return;
				}
				if ("project".equals(args.command())) {
					var exitCode = ProjectCommands.run(args.commandArgs());
					if (exitCode != 0) {
						System.exit(exitCode);
					}
					return;
				}
				if ("init".equals(args.command())) {
				int port = ServerSpawner.spawn(args);
				System.out.println(port);
				return;
			}
			var requestLine = buildRequestLine(args);
			var exitSession = "exit".equals(args.command())
					? SessionFile.read(args.port())
					: java.util.Optional.<SessionFile.Session>empty();
			TcpRequest.Result result;
			try {
				result = TcpRequest.send(args.port(), requestLine);
			} catch (SocketException failure) {
				cleanupAfterUnavailable(args.port());
				if (failure instanceof ConnectException) {
					System.err.println("ERR no workspace server is running on port "
							+ args.port() + "; run init again");
				} else {
					System.err.println("ERR connection to workspace server on port "
							+ args.port() + " was lost; command outcome is unknown; run init again");
				}
				System.exit(1);
				return;
			}
			for (var l : result.lines()) {
				System.out.println(l);
			}
			if (!result.ok()) {
				System.err.println(result.status().isEmpty()
						? "ERR truncated response from server"
						: result.status());
				System.exit(1);
			}
			if ("exit".equals(args.command()) && result.ok() && exitSession.isPresent()) {
				try {
					ServerSpawner.cleanupAfterExit(exitSession.get());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					System.err.println("WARN workspace server exited, but supervisor cleanup failed: " + e.getMessage());
				} catch (IOException e) {
					System.err.println("WARN workspace server exited, but supervisor cleanup failed: " + e.getMessage());
				}
			}
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			System.err.println("ERR " + e.getClass().getSimpleName() + ": " + e.getMessage());
			System.exit(1);
		}
	}

	private static void cleanupAfterUnavailable(int port) {
		try {
			ServerSpawner.cleanupUnavailableSession(port);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			System.err.println("WARN workspace server is unavailable, but stale session cleanup was interrupted");
		} catch (IOException e) {
			System.err.println("WARN workspace server is unavailable, but stale session cleanup failed: "
					+ e.getMessage());
		}
	}

	private static String buildRequestLine(ArgParser.Args args) {
		var sb = new StringBuilder();
		sb.append(TcpRequest.quote(args.clientId()));
		sb.append(' ');
		sb.append(args.command());
		for (var a : args.commandArgs()) {
			sb.append(' ');
			sb.append(TcpRequest.quote(a));
		}
		return sb.toString();
	}
}
