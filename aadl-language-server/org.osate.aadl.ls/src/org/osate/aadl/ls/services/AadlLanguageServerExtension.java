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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.xtext.ide.server.ILanguageServerAccess;
import org.eclipse.xtext.ide.server.ILanguageServerExtension;
import org.eclipse.xtext.resource.IResourceDescription;

import com.google.inject.Inject;

/**
 * Custom JSON-RPC endpoints for the AADL language server.
 *
 * <p>{@code aadlServer/waitUntilFinished} is used by the OSATE CLI workspace server to block
 * until Xtext's next background build completes. Each request returns a fresh promise;
 * {@link #afterBuild} resolves every promise queued at that moment. Since LSP4J dispatches
 * inbound JSON-RPC messages serially on the reader thread, any {@code didOpen} /
 * {@code didChangeWatchedFiles} arriving before this request has already submitted its
 * {@code runBuildable} to the request manager by the time our handler runs, so the next
 * {@code afterBuild} is the one we want. The caller MUST only send this request after a
 * notification that triggers a build; otherwise the promise will not resolve.
 *
 * <p>{@code aadlServer/readContributedAadl} returns the source text of a registered AADL
 * resource contributed by an OSATE plugin. Editors use it to display read-only definitions
 * whose locations use {@code platform:/plugin} URIs.
 */
public class AadlLanguageServerExtension implements ILanguageServerExtension, ILanguageServerAccess.IBuildListener {

	private final List<CompletableFuture<Boolean>> pendingPromises = new ArrayList<>();
	private final ContributedAadlContentService contributedAadlContentService;

	@Inject
	public AadlLanguageServerExtension(ContributedAadlContentService contributedAadlContentService) {
		this.contributedAadlContentService = contributedAadlContentService;
	}

	@Override
	public void initialize(ILanguageServerAccess access) {
		access.addBuildListener(this);
	}

	@JsonRequest("aadlServer/waitUntilFinished")
	public synchronized CompletableFuture<Boolean> waitUntilFinished() {
		var promise = new CompletableFuture<Boolean>();
		pendingPromises.add(promise);
		return promise;
	}

	@JsonRequest("aadlServer/readContributedAadl")
	public CompletableFuture<String> readContributedAadl(ReadContributedAadlParams params) {
		if (params == null) {
			return failedFuture(ResponseErrorCode.InvalidParams, "A contributed AADL URI is required");
		}

		try {
			return CompletableFuture.completedFuture(contributedAadlContentService.read(params.getUri()));
		} catch (IllegalArgumentException exception) {
			return failedFuture(ResponseErrorCode.InvalidParams, exception.getMessage());
		} catch (IOException exception) {
			return failedFuture(ResponseErrorCode.RequestFailed, "Unable to read the contributed AADL resource");
		}
	}

	private static <T> CompletableFuture<T> failedFuture(ResponseErrorCode code, String message) {
		return CompletableFuture.failedFuture(
				new ResponseErrorException(new ResponseError(code, message, null)));
	}

	@Override
	public synchronized void afterBuild(List<IResourceDescription.Delta> deltas) {
		for (var promise : pendingPromises) {
			if (!promise.isDone()) {
				promise.complete(Boolean.TRUE);
			}
		}
		pendingPromises.clear();
	}
}
