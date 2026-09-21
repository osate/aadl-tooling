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
package org.osate.aadl.ls.services;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.Assignment;
import org.eclipse.xtext.ParserRule;
import org.eclipse.xtext.ide.server.Document;
import org.eclipse.xtext.ide.server.contentassist.ContentAssistService;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.resource.XtextResourceSet;
import org.eclipse.xtext.util.CancelIndicator;
import org.osate.aadl2.DefaultAnnexSubclause;
import org.osate.aadl.ls.setup.BehaviorAnnexLsSetup;
import org.osate.annexsupport.AnnexUtil;
import org.osate.annexsupport.ParseResultHolder;
import org.osate.xtext.aadl2.ba.behaviorAnnex.BehaviorAnnex;
import org.osate.xtext.aadl2.ba.services.BehaviorAnnexReferenceProposalService;

import com.google.inject.Inject;
import com.google.inject.Injector;

/**
 * Delegates completion requests inside embedded Behavior Annex text to the BA IDE injector while leaving every other
 * AADL position on the normal AADL content-assist path.
 */
public final class EmbeddedBehaviorAnnexContentAssistService extends ContentAssistService {
	private final Injector behaviorAnnexInjector;
	private final ContentAssistService behaviorAnnexContentAssist;
	private final BehaviorAnnexReferenceProposalService proposals;

	@Inject
	public EmbeddedBehaviorAnnexContentAssistService(final BehaviorAnnexReferenceProposalService proposals) {
		this.proposals = proposals;
		behaviorAnnexInjector = new BehaviorAnnexLsSetup().createInjectorAndDoEMFRegistration();
		behaviorAnnexContentAssist = behaviorAnnexInjector.getInstance(ContentAssistService.class);
	}

	@Override
	public CompletionList createCompletionList(final Document document, final XtextResource resource,
			final CompletionParams params, final CancelIndicator cancelIndicator) {
		try {
			var offset = document.getOffSet(params.getPosition());
			var annexLeaf = AnnexUtil.findAnnexLeafNode(resource, offset);
			if (annexLeaf == null) {
				return super.createCompletionList(document, resource, params, cancelIndicator);
			}

			EObject semantic = NodeModelUtils.findActualSemanticObjectFor(annexLeaf);
			var defaultAnnex = semantic instanceof DefaultAnnexSubclause subclause ? subclause
					: EcoreUtil2.getContainerOfType(semantic, DefaultAnnexSubclause.class);
			if (defaultAnnex == null || !(defaultAnnex.getParsedAnnexSubclause() instanceof BehaviorAnnex annex)) {
				return super.createCompletionList(document, resource, params, cancelIndicator);
			}

			var annexNode = NodeModelUtils.getNode(annex);
			if (annexNode == null || offset < annexNode.getOffset() || offset > annexNode.getEndOffset()) {
				return super.createCompletionList(document, resource, params, cancelIndicator);
			}

			var referenceCompletions = createReferenceCompletions(document, annex, offset, params);
			if (referenceCompletions != null) {
				return referenceCompletions;
			}

			var syntheticText = " ".repeat(annexNode.getOffset()) + annexNode.getText();
			var resourceSet = behaviorAnnexInjector.getInstance(XtextResourceSet.class);
			var syntheticResource = (XtextResource) resourceSet
					.createResource(URI.createURI("memory:/embedded-" + System.nanoTime() + ".baxtext"));
			syntheticResource.load(new ByteArrayInputStream(syntheticText.getBytes(StandardCharsets.UTF_8)), null);

			var result = behaviorAnnexContentAssist.createCompletionList(document, syntheticResource, params,
					cancelIndicator);
			var template = result.getItems()
					.stream()
					.filter(item -> item.getKind() == CompletionItemKind.Reference)
					.findFirst()
					.orElse(null);
			if (template != null) {
				addContainingClassifierProposals(result, template, annex, params);
			}
			return result;
		} catch (Exception exception) {
			return super.createCompletionList(document, resource, params, cancelIndicator);
		}
	}

	private CompletionList createReferenceCompletions(final Document document, final BehaviorAnnex annex,
			final int offset, final CompletionParams params) {
		var parseResult = ParseResultHolder.Factory.INSTANCE.adapt(annex).getParseResult();
		if (parseResult == null || parseResult.getRootNode() == null || offset <= 0) {
			return null;
		}
		var leaf = NodeModelUtils.findLeafNodeAtOffset(parseResult.getRootNode(), offset - 1);
		var assignment = EcoreUtil2.getContainerOfType(leaf.getGrammarElement(), Assignment.class);
		var rule = assignment == null ? null : EcoreUtil2.getContainerOfType(assignment, ParserRule.class);
		if (assignment == null || rule == null || !"name".equals(assignment.getFeature())
				|| (!"ReferenceSegment".equals(rule.getName())
						&& !"UnindexedReferenceSegment".equals(rule.getName()))) {
			return null;
		}

		var prefixStart = leaf.getOffset();
		var prefix = document.getContents().substring(prefixStart, Math.min(offset, leaf.getEndOffset()));
		var range = new Range(document.getPosition(prefixStart), params.getPosition());
		var result = new CompletionList();
		result.setIsIncomplete(false);
		for (var proposal : proposals.getRootProposals(annex)) {
			if (!proposal.regionMatches(true, 0, prefix, 0, prefix.length())) {
				continue;
			}
			var item = new CompletionItem(proposal);
			item.setKind(CompletionItemKind.Reference);
			item.setTextEdit(Either.forLeft(new TextEdit(range, proposal)));
			item.setSortText(String.format("%05d", result.getItems().size()));
			result.getItems().add(item);
		}
		return result;
	}

	private void addContainingClassifierProposals(final CompletionList result, final CompletionItem template,
			final BehaviorAnnex annex, final CompletionParams params) {
		var labels = new HashSet<String>();
		result.getItems().stream().map(CompletionItem::getLabel).forEach(labels::add);
		for (var proposal : proposals.getRootProposals(annex)) {
			if (!labels.add(proposal)) {
				continue;
			}
			var item = new CompletionItem(proposal);
			item.setKind(CompletionItemKind.Reference);
			item.setTextEdit(Either.forLeft(new TextEdit(replacementRange(template, params), proposal)));
			item.setSortText(String.format("%05d", result.getItems().size()));
			result.getItems().add(item);
		}
	}

	private static Range replacementRange(final CompletionItem template, final CompletionParams params) {
		var textEdit = template.getTextEdit();
		if (textEdit != null && textEdit.isLeft()) {
			return textEdit.getLeft().getRange();
		}
		return new Range(params.getPosition(), params.getPosition());
	}
}
