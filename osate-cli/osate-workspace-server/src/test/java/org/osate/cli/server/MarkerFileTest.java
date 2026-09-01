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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkerFileTest {

	@Test
	void rewriteRootsPreservesServerIdentity(@TempDir Path workspace) throws Exception {
		var marker = MarkerFile.markerPath(workspace);
		var original = new MarkerFile.MarkerData(54321, 1234, workspace.toString(), List.of(workspace.toString()));
		MarkerFile.write(marker, original);
		var secondRoot = workspace.resolveSibling("second-root");

		MarkerFile.rewriteRoots(marker, List.of(workspace, secondRoot));

		var updated = MarkerFile.read(marker);
		assertEquals(54321, updated.port());
		assertEquals(1234, updated.pid());
		assertEquals(workspace.toString(), updated.workspaceRoot());
		assertEquals(List.of(workspace.toAbsolutePath().normalize().toString(),
				secondRoot.toAbsolutePath().normalize().toString()), updated.roots());
	}
}
