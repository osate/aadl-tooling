/*******************************************************************************
 * Copyright (c) 2004-2026 Carnegie Mellon University and others. (see Contributors file).
 * All Rights Reserved.
 *
 * NO WARRANTY. ALL MATERIAL IS FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON UNIVERSITY MAKES NO WARRANTIES OF ANY
 * KIND, EITHER EXPRESSED OR IMPLIED, AS TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF FITNESS FOR PURPOSE
 * OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT
 * MAKE ANY WARRANTY OF ANY KIND WITH RESPECT TO FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created, in part, with funding and support from the United States Government. (see Acknowledgments file).
 *
 * This program includes and/or can make use of certain third party source code, object code, documentation and other
 * files ("Third Party Software"). The Third Party Software that is used by this program is dependent upon your system
 * configuration. By using this program, You agree to comply with any and all relevant Third Party Software terms and
 * conditions contained in any such Third Party Software or separate license file distributed with such Third Party
 * Software. The parties who own the Third Party Software ("Third Party Licensors") are intended third party beneficiaries
 * to this license with respect to the terms applicable to their Third Party Software. Third Party Software licenses
 * only apply to the Third Party Software and not any other portion of this program or this program as a whole.
 *******************************************************************************/
package org.osate.aadl.ls.tests.lsp;

import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.eclipse.lsp4j.Location;
import org.junit.Assert;
import org.junit.Test;
import org.osate.aadl.ls.services.ContributedAadlContentService;

public class PredeclaredPropertyDefinitionLspTest extends AbstractAadlLanguageServerTest {

	@Test
	public void definitionTargetsBundledPropertySet() {
		testDefinition(configuration -> {
			configuration.setFilePath("uses-predeclared.aadl");
			configuration.setModel("""
					package UsesPredeclared
					public
					    thread t
					        properties
					            Period => 10 ms;
					    end t;
					end UsesPredeclared;
					""");
			configuration.setLine(4);
			configuration.setColumn(14);
			configuration.setAssertDefinitions(definitions -> {
				Assert.assertEquals(1, definitions.size());
				Location definition = definitions.get(0);
				assertTrue(definition.getUri().startsWith("platform:/plugin/"));
				assertTrue(definition.getUri().endsWith("/Timing_Properties.aadl"));
				assertRangeStartsOnPeriodDeclaration(definition);
			});
		});
	}

	private static void assertRangeStartsOnPeriodDeclaration(Location definition) {
		try {
			String contents = new ContributedAadlContentService().read(definition.getUri());
			String line = contents.lines().skip(definition.getRange().getStart().getLine()).findFirst().orElseThrow();
			assertTrue(line.trim().startsWith("Period:"));
		} catch (IOException exception) {
			throw new AssertionError("Unable to read definition target", exception);
		}
	}
}
