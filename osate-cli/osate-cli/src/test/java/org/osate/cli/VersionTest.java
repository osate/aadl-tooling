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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

class VersionTest {

	/**
	 * Guards the single-source-of-truth chain: Maven filters the version declared in the
	 * parent pom's {@code <revision>} property into the resource this reads, and the
	 * packaging scripts read the same resource out of the built jar.
	 */
	@Test
	void matchesVersionDeclaredInPom() {
		var expected = System.getProperty("expected.osate.cli.version");
		// Set by Surefire; an IDE run without it skips rather than fails.
		assumeTrue(expected != null, "expected.osate.cli.version is not set");
		assertEquals(expected, Version.get(),
				"version.properties disagrees with the pom; check resource filtering");
	}

	@Test
	void isResolvedNotFallback() {
		assertNotEquals(Version.UNKNOWN, Version.get());
	}

	/**
	 * The provenance keys must resolve to something, even in a standalone reactor build
	 * that supplies no values. A literal {@code ${...}} here would mean the keys were
	 * added to the resource but not declared as pom properties, which would otherwise
	 * surface as raw Maven expressions printed in {@code osate-cli help}.
	 */
	@Test
	void provenanceIsNeverAnUnfilteredExpression() {
		for (var value : new String[] { Version.languageServerVersion(), Version.languageServerCommit(),
				Version.osateVersion(), Version.osateCommit() }) {
			assertNotNull(value);
			assertFalse(value.isBlank(), "provenance value is blank");
			assertFalse(value.startsWith("${"), () -> "unfiltered provenance value: " + value);
		}
	}

	/**
	 * A standalone {@code mvn -f osate-cli/pom.xml verify} supplies no provenance, so the
	 * pom defaults must say so rather than report a real-looking value. The release path
	 * asserts the opposite; see {@code packaging/scripts/build-release-artifacts.sh}.
	 */
	@Test
	void provenanceReportsUnknownWhenTheBuildSuppliesNothing() {
		assumeTrue(Version.UNKNOWN_PROVENANCE.equals(Version.osateCommit()),
				"this build supplied provenance, so there is nothing to check here");
		assertFalse(Version.hasCompleteProvenance());
	}

	@Test
	void abbreviateShortensTheCommitAndKeepsAnyMarker() {
		assertEquals("4256148", Version.abbreviate("425614884eaf14312141fbdd3a393ba54ff34b23", 7));

		// The -dirty marker says the commit does not describe what was built, so it has
		// to survive; dropping it would turn an untrustworthy value into a trustworthy
		// looking one. Local builds are the common case here.
		assertEquals("7fbfec9-dirty",
				Version.abbreviate("7fbfec98e4f2d78fff9ebf8dca7c866cbad8029b-dirty", 7));

		// Not a commit at all: returning it untouched keeps it obviously not a commit.
		assertEquals("unknown", Version.abbreviate("unknown", 7));

		// Already short enough, even though every character happens to be hex.
		assertEquals("abc", Version.abbreviate("abc", 7));
		assertNull(Version.abbreviate(null, 7));
	}
}
