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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ServerLaunchModeTest {
	@Test
	void missingBlankAndWhitespaceAreAuto() throws Exception {
		assertEquals(ServerLaunchMode.AUTO, ServerLaunchMode.fromEnvironment(Map.of()));
		assertEquals(ServerLaunchMode.AUTO,
				ServerLaunchMode.fromEnvironment(Map.of(ServerLaunchMode.ENV_VAR, "")));
		assertEquals(ServerLaunchMode.AUTO,
				ServerLaunchMode.fromEnvironment(Map.of(ServerLaunchMode.ENV_VAR, " \t")));
	}

	@Test
	void valuesAreCaseInsensitive() throws Exception {
		assertEquals(ServerLaunchMode.AUTO,
				ServerLaunchMode.fromEnvironment(Map.of(ServerLaunchMode.ENV_VAR, " AuTo ")));
		assertEquals(ServerLaunchMode.MANAGED,
				ServerLaunchMode.fromEnvironment(Map.of(ServerLaunchMode.ENV_VAR, "MANAGED")));
		assertEquals(ServerLaunchMode.DIRECT,
				ServerLaunchMode.fromEnvironment(Map.of(ServerLaunchMode.ENV_VAR, "direct")));
	}

	@Test
	void invalidValueHasContractMessage() {
		var exception = assertThrows(IOException.class,
				() -> ServerLaunchMode.fromEnvironment(
						Map.of(ServerLaunchMode.ENV_VAR, "bogus")));
		assertEquals("OSATE_CLI_SERVER_LAUNCH must be one of auto, managed, direct: bogus",
				exception.getMessage());
	}

	@Test
	void unrelatedEnvironmentDoesNotMatter() throws Exception {
		assertEquals(ServerLaunchMode.AUTO,
				ServerLaunchMode.fromEnvironment(Map.of("OTHER", "managed")));
	}
}
