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
import java.io.InputStream;
import java.util.Properties;

/**
 * Reports the osate-cli release version and what it was built against.
 *
 * <p>The version is declared once, in the {@code <revision>} property of
 * {@code osate-cli/pom.xml}. Maven resource filtering writes it into
 * {@code org/osate/cli/version.properties}, and the packaging scripts read the same
 * resource back out of the built {@code osate-cli.jar} to version the tarball, deb,
 * rpm, and Homebrew packages. The version reported here therefore always matches the
 * package the CLI was installed from.
 *
 * <p>The same resource carries the bundled language server's version and commit and the
 * OSATE version and commit. None of those can be discovered at runtime: OSATE bundles
 * carry independent versions ({@code org.osate.aadl2} is 6.1.1, not 2.19.0) and no
 * bundle manifest records a commit, so {@code scripts/build-test-release} bakes them in.
 * A build that bypasses that script reports {@link #UNKNOWN_PROVENANCE} for them.
 *
 * <p>A properties resource is used rather than the jar manifest's
 * {@code Implementation-Version} because the resource is also present when running from
 * {@code target/classes} (tests and IDE launches), and because manifest values are line
 * wrapped at 72 bytes, which makes them awkward to read from shell scripts.
 */
public final class Version {

	/** Reported when the filtered resource is missing, e.g. from a hand-assembled classpath. */
	public static final String UNKNOWN = "0.0.0-dev";

	/** Reported for build provenance that the build did not supply. */
	public static final String UNKNOWN_PROVENANCE = "unknown";

	private static final String RESOURCE = "/org/osate/cli/version.properties";

	private static final Properties PROPS = load();

	private static final String VERSION = read("version", UNKNOWN);
	private static final String LS_VERSION = read("ls.version", UNKNOWN_PROVENANCE);
	private static final String LS_COMMIT = read("ls.commit", UNKNOWN_PROVENANCE);
	private static final String OSATE_VERSION = read("osate.version", UNKNOWN_PROVENANCE);
	private static final String OSATE_COMMIT = read("osate.commit", UNKNOWN_PROVENANCE);

	private Version() {
	}

	/** The release version, or {@link #UNKNOWN} if it cannot be determined. */
	public static String get() {
		return VERSION;
	}

	/** Bundle version of the language server this CLI ships, e.g. {@code 0.1.0.v20260902-1313}. */
	public static String languageServerVersion() {
		return LS_VERSION;
	}

	/** Commit of this repository that the bundled language server was built from. */
	public static String languageServerCommit() {
		return LS_COMMIT;
	}

	/** OSATE version the language server was built against, e.g. {@code 2.19.0.vfinal}. */
	public static String osateVersion() {
		return OSATE_VERSION;
	}

	/** The reviewed {@code osate2} gitlink the language server was built against. */
	public static String osateCommit() {
		return OSATE_COMMIT;
	}

	/** Whether every provenance value was supplied by the build. */
	public static boolean hasCompleteProvenance() {
		return !UNKNOWN.equals(VERSION) && !UNKNOWN_PROVENANCE.equals(LS_VERSION)
				&& !UNKNOWN_PROVENANCE.equals(LS_COMMIT) && !UNKNOWN_PROVENANCE.equals(OSATE_VERSION)
				&& !UNKNOWN_PROVENANCE.equals(OSATE_COMMIT);
	}

	/**
	 * Shortens a commit for display, keeping any trailing marker.
	 *
	 * <p>Only the leading hexadecimal run is shortened, so {@code <sha>-dirty} becomes
	 * {@code 7fbfec9-dirty} rather than losing the suffix that says the value cannot be
	 * trusted, and a non-commit placeholder like {@code unknown} is returned untouched
	 * instead of being truncated into something that looks like a commit.
	 */
	public static String abbreviate(String commit, int length) {
		if (commit == null) {
			return null;
		}
		var hex = 0;
		while (hex < commit.length() && Character.digit(commit.charAt(hex), 16) >= 0) {
			hex++;
		}
		if (hex <= length) {
			return commit;
		}
		return commit.substring(0, length) + commit.substring(hex);
	}

	private static Properties load() {
		var props = new Properties();
		try (InputStream in = Version.class.getResourceAsStream(RESOURCE)) {
			if (in != null) {
				props.load(in);
			}
		} catch (IOException e) {
			// Fall through to the empty properties; every value then reports its fallback.
		}
		return props;
	}

	private static String read(String key, String fallback) {
		var value = PROPS.getProperty(key, "").trim();
		// An unfiltered resource still holds the literal Maven expression.
		return value.isEmpty() || value.startsWith("${") ? fallback : value;
	}
}
