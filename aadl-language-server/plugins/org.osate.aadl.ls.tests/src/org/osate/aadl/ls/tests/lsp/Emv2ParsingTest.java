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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.Assert;
import org.junit.Test;

public class Emv2ParsingTest extends AbstractAadlLanguageServerTest {

	@Test
	public void packageWithEmv2AnnexParsesWithoutErrors() {
		initialize();
		String source = """
				package errors
				public

					abstract A
						features
							f: feature;
						annex emv2 {**
							use types ErrorLibrary;
							error propagations
								f: in propagation {ValueError};
							end propagations;
						**};
					end A;

				end errors;
				""";
		String uri = writeFile("errors.aadl", source);
		open(uri, source);

		Map<String, List<Diagnostic>> diagnostics = getDiagnostics();
		List<Diagnostic> forFile = diagnostics.getOrDefault(uri, List.of());
		boolean hasError = forFile.stream()
				.anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
		assertFalse("Expected EMV2 annex to parse without errors, got: " + forFile, hasError);
	}

	@Test
	public void errorTypeDefinitionInsideEmv2Annex() {
		testDefinition(configuration -> {
			configuration.setFilePath("emv2-definition.aadl");
			configuration.setModel("""
					package error_types
					public
					    annex EMV2 {**
					        error types
					            ServiceError: type;
					        end types;
					    **};

					    abstract A
					        features
					            f: feature;
					        annex EMV2 {**
					            use types error_types;
					            error propagations
					                f: in propagation {ServiceError};
					            end propagations;
					        **};
					    end A;
					end error_types;
					""");
			configuration.setLine(14);
			configuration.setColumn(37);
			configuration.setAssertDefinitions(definitions -> {
				Assert.assertEquals(1, definitions.size());
				var definition = definitions.get(0);
				assertTrue(definition.getUri().endsWith("/emv2-definition.aadl"));
				Assert.assertEquals(new Range(new Position(4, 12), new Position(4, 24)), definition.getRange());
			});
		});
	}
}
