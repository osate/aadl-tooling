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
 * Reports the osate-cli release version.
 *
 * <p>The version is declared once, in the {@code <revision>} property of
 * {@code osate-cli/pom.xml}. Maven resource filtering writes it into
 * {@code org/osate/cli/version.properties}, and the packaging scripts read the same
 * resource back out of the built {@code osate-cli.jar} to version the tarball, deb,
 * rpm, and Homebrew packages. The version reported here therefore always matches the
 * package the CLI was installed from.
 *
 * <p>A properties resource is used rather than the jar manifest's
 * {@code Implementation-Version} because the resource is also present when running from
 * {@code target/classes} (tests and IDE launches), and because manifest values are line
 * wrapped at 72 bytes, which makes them awkward to read from shell scripts.
 */
public final class Version {

	/** Reported when the filtered resource is missing, e.g. from a hand-assembled classpath. */
	public static final String UNKNOWN = "0.0.0-dev";

	private static final String RESOURCE = "/org/osate/cli/version.properties";

	private static final String VERSION = load();

	private Version() {
	}

	/** The release version, or {@link #UNKNOWN} if it cannot be determined. */
	public static String get() {
		return VERSION;
	}

	private static String load() {
		try (InputStream in = Version.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				return UNKNOWN;
			}
			var props = new Properties();
			props.load(in);
			var version = props.getProperty("version", "").trim();
			// An unfiltered resource still holds the literal Maven expression.
			return version.isEmpty() || version.startsWith("${") ? UNKNOWN : version;
		} catch (IOException e) {
			return UNKNOWN;
		}
	}
}
