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

import java.util.List;

import org.eclipse.lsp4j.DocumentSymbol;
import org.junit.Assert;
import org.junit.Test;

public class DocumentSymbolLspTest extends AbstractAadlLanguageServerTest {

	@Test
	public void symbolTreeUsesCustomNameProvider() {
		testDocumentSymbol(cfg -> {
			cfg.setFilePath("symbols.aadl");
			cfg.setModel("""
					package symbols_pkg
					public
						system sys
						end sys;

						system implementation sys.impl
						end sys.impl;
					end symbols_pkg;
					""");
			cfg.setAssertSymbols(either -> {
				Assert.assertEquals("Expected single top-level symbol", 1, either.size());
				DocumentSymbol pkg = either.get(0).getRight();
				Assert.assertNotNull("Hierarchical symbols expected", pkg);
				Assert.assertEquals("symbols_pkg", pkg.getName());

				List<DocumentSymbol> children = pkg.getChildren();
				Assert.assertNotNull(children);
				Assert.assertTrue("Expected at least one child symbol",
						children.stream().anyMatch(s -> "public".equals(s.getName())));

				DocumentSymbol pub = children.stream()
						.filter(s -> "public".equals(s.getName()))
						.findFirst()
						.orElseThrow();
				List<DocumentSymbol> classifiers = pub.getChildren();
				Assert.assertNotNull(classifiers);
				Assert.assertTrue("Expected classifier 'sys' as simple name",
						classifiers.stream().anyMatch(s -> "sys".equals(s.getName())));
				Assert.assertTrue("Expected classifier 'sys.impl' as simple name",
						classifiers.stream().anyMatch(s -> "sys.impl".equals(s.getName())));
			});
			cfg.setInitializer(params -> {
				if (params.getCapabilities() == null) {
					params.setCapabilities(new org.eclipse.lsp4j.ClientCapabilities());
				}
				if (params.getCapabilities().getTextDocument() == null) {
					params.getCapabilities()
							.setTextDocument(new org.eclipse.lsp4j.TextDocumentClientCapabilities());
				}
				org.eclipse.lsp4j.DocumentSymbolCapabilities symCaps = new org.eclipse.lsp4j.DocumentSymbolCapabilities();
				symCaps.setHierarchicalDocumentSymbolSupport(true);
				params.getCapabilities().getTextDocument().setDocumentSymbol(symCaps);
			});
		});
	}
}
