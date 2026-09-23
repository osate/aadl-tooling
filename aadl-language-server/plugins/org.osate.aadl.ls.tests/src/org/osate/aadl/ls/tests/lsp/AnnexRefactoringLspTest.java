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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameCapabilities;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.xtext.ide.server.Document;
import org.eclipse.xtext.ide.server.ILanguageServerExtension;
import org.eclipse.xtext.ide.server.rename.IRenameService2;
import org.junit.Assert;
import org.junit.Test;
import org.osate.xtext.aadl2.errormodel.ide.refactoring.EmbeddedErrorModelResourceServiceProvider;

/**
 * Verifies that the final server injector preserves annex rename and navigation
 * through public LSP requests.
 */
public class AnnexRefactoringLspTest extends AbstractAadlLanguageServerTest {
	@Test
	public void finalProviderRetainsRenameAndServerExtension() {
		var provider = resourceServerProviderRegistry.getResourceServiceProvider(URI.createURI("model.aadl"));
		assertTrue(provider instanceof EmbeddedErrorModelResourceServiceProvider);
		Assert.assertEquals("Aadl2RenameService", provider.get(IRenameService2.class).getClass().getSimpleName());
		assertNotNull(provider.get(ILanguageServerExtension.class));
	}

	@Test
	public void preparesAndRenamesBehaviorVariable() throws Exception {
		assertRename("Behavior.aadl", "counter :=", "counter", "renamed_counter");
	}

	@Test
	public void renamesCorePortAndBehaviorUses() throws Exception {
		assertRename("Behavior.aadl", "input: in", "input", "renamed_input");
	}

	@Test
	public void renamesFeatureFromQualifiedErrorFlow() throws Exception {
		assertRename("Qualified.aadl", "source left.value", "left", "renamed_left");
	}

	@Test
	public void renamesPropagationPointAndDependentFlows() throws Exception {
		assertRename("PropagationPoints.aadl", "up1: propagation point", "up1", "renamed_up1");
	}

	@Test
	public void highlightsAndFindsOnlyQualifiedPathSegment() throws Exception {
		initialize();
		String source = fixture("Qualified.aadl");
		String uri = writeFile("Qualified.aadl", source);
		open(uri, source);
		var doc = new Document(1, source);
		var position = doc.getPosition(source.indexOf("source left.value") + "source ".length() + 1);
		var id = new TextDocumentIdentifier(uri);
		var highlights = languageServer.documentHighlight(new DocumentHighlightParams(id, position)).get();
		Assert.assertEquals(3, highlights.size());
		for (var highlight : highlights)
			Assert.assertEquals("left", slice(doc, highlight.getRange()));
		var references = languageServer.references(new ReferenceParams(id, position, new ReferenceContext(false)))
				.get();
		Assert.assertEquals(2, references.size());
		for (var reference : references)
			Assert.assertEquals("left", slice(doc, reference.getRange()));
	}

	@Test
	public void renameUsesUnsavedBehaviorText() throws Exception {
		assertRename("Behavior.aadl", "counter :=", "counter", "renamed_counter", true);
	}

	private void assertRename(String file, String selectedText, String name, String replacement) throws Exception {
		assertRename(file, selectedText, name, replacement, false);
	}

	private void assertRename(String file, String selectedText, String name, String replacement, boolean dirty)
			throws Exception {
		initialize(params -> {
			var textDocument = new TextDocumentClientCapabilities();
			var rename = new RenameCapabilities();
			rename.setPrepareSupport(true);
			textDocument.setRename(rename);
			params.getCapabilities().setTextDocument(textDocument);
		});
		String source = fixture(file);
		String uri = writeFile(file, source);
		if (dirty) {
			source = source.replace("counter := input;", "counter := input;\n\t\t\t\t\tcounter := input;");
		}
		open(uri, source);
		assertTrue(getDiagnostics().toString(), getDiagnostics().getOrDefault(uri, List.of()).stream()
				.noneMatch(d -> d.getSeverity() == DiagnosticSeverity.Error));
		var doc = new Document(1, source);
		int start = source.indexOf(selectedText) + selectedText.indexOf(name);
		assertTrue(start >= 0);
		var id = new TextDocumentIdentifier(uri);
		var position = doc.getPosition(start + 1);
		var prepared = languageServer.prepareRename(new PrepareRenameParams(id, position)).get();
		assertNotNull(prepared);
		Assert.assertEquals(name, slice(doc, prepared.getFirst()));
		var edit = languageServer.rename(new RenameParams(id, position, replacement)).get();
		assertNotNull(edit);
		var edits = edit.getChanges().get(uri);
		assertNotNull(edit.getChanges().toString(), edits);
		var result = new StringBuilder(source);
		for (var e : edits.stream()
				.sorted(Comparator.comparingInt((TextEdit e) -> doc.getOffSet(e.getRange().getStart())).reversed())
				.toList()) {
			result.replace(doc.getOffSet(e.getRange().getStart()), doc.getOffSet(e.getRange().getEnd()),
					e.getNewText());
		}
		Assert.assertEquals(source.replaceAll("(?<![\\w])" + name + "(?![\\w])", replacement), result.toString());
	}

	private static String fixture(String name) throws Exception {
		return Files.readString(Path.of("test-models", "lsp-refactoring", name));
	}

	private static String slice(Document document, Range range) {
		return document.getContents().substring(document.getOffSet(range.getStart()),
				document.getOffSet(range.getEnd()));
	}
}
