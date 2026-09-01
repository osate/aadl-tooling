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
package org.osate.aadl.ls;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.xtext.ide.server.Document;
import org.eclipse.xtext.ide.server.LanguageServerImpl;
import org.eclipse.xtext.validation.Issue;

/**
 * AADL-specific language server behavior.
 */
public class AadlLanguageServer extends LanguageServerImpl {
	private static final Pattern OFFENDING_INPUT = Pattern.compile("\\binput\\s+'([^'\\r\\n]+)'");

	private final Map<String, Document> openDocuments = new ConcurrentHashMap<>();

	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		var textDocument = params.getTextDocument();
		openDocuments.put(textDocument.getUri(), new Document(textDocument.getVersion(), textDocument.getText()));
		super.didOpen(params);
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		String uri = params.getTextDocument().getUri();
		openDocuments.computeIfPresent(uri, (key, document) -> {
			Document changed = document.applyTextDocumentChanges(params.getContentChanges());
			return new Document(params.getTextDocument().getVersion(), changed.getContents());
		});
		super.didChange(params);
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		super.didClose(params);
		openDocuments.remove(params.getTextDocument().getUri());
	}

	@Override
	protected Diagnostic toDiagnostic(Issue issue) {
		Diagnostic diagnostic = super.toDiagnostic(issue);
		if (!hasMalformedRange(issue, diagnostic.getRange())) {
			return diagnostic;
		}

		var matcher = OFFENDING_INPUT.matcher(issue.getMessage());
		if (!matcher.find() || issue.getLineNumber() == null) {
			return diagnostic;
		}

		String token = matcher.group(1);
		int line = Math.max(0, issue.getLineNumber() - 1);
		try {
			String lineContents = getLineContents(issue, line, token);
			int startCharacter = lineContents.indexOf(token);
			if (startCharacter >= 0) {
				diagnostic.setRange(new Range(new Position(line, startCharacter),
						new Position(line, startCharacter + token.length())));
			}
		} catch (RuntimeException e) {
			// Keep Xtext's original range if the document is no longer available.
		}
		return diagnostic;
	}

	private String getLineContents(Issue issue, int line, String token) {
		if (issue.getUriToProblem() != null) {
			var uri = issue.getUriToProblem().trimFragment();
			Document openDocument = openDocuments.get(uri.toString());
			if (openDocument != null) {
				return openDocument.getLineContent(line);
			}
			String lineContents = getWorkspaceManager().doRead(uri,
					(document, resource) -> document == null ? null : document.getLineContent(line));
			if (lineContents != null) {
				return lineContents;
			}
		}

		for (Document document : openDocuments.values()) {
			try {
				String lineContents = document.getLineContent(line);
				if (lineContents.contains(token) && isIssueOffsetOnLine(issue, document, line)) {
					return lineContents;
				}
			} catch (IndexOutOfBoundsException e) {
				// Try the next open document.
			}
		}
		return "";
	}

	private static boolean isIssueOffsetOnLine(Issue issue, Document document, int line) {
		if (issue.getOffset() == null) {
			return true;
		}
		try {
			return document.getPosition(issue.getOffset()).getLine() == line;
		} catch (IndexOutOfBoundsException e) {
			return false;
		}
	}

	private static boolean hasMalformedRange(Issue issue, Range range) {
		if (issue.getLineNumberEnd() == null || issue.getLineNumberEnd() <= 0) {
			return true;
		}
		Position start = range.getStart();
		Position end = range.getEnd();
		return end.getLine() < start.getLine()
				|| end.getLine() == start.getLine() && end.getCharacter() < start.getCharacter();
	}
}
