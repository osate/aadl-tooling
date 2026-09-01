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
package org.osate.aadl.ls.commands;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.xtext.ide.server.ILanguageServerAccess;
import org.eclipse.xtext.util.CancelIndicator;
import org.osate.aadl2.instance.SystemInstance;
import org.osate.aadl.ls.commands.AnalysisCommandResult.Report;
import org.osate.analysis.flows.FlowLatencyAnalysisSwitch;

final class AnalyzeLatencyCommand implements Command {

	static final String NAME = "aadl.analyze.latency";

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Object execute(ExecuteCommandParams params, ILanguageServerAccess access, CancelIndicator cancelIndicator) {
		var uri = CommandUtil.requiredFileUri(params);
		var iuri = uri.toString();
		var args = params.getArguments();
		boolean asynchronousSystem = CommandUtil.optBool(args, 1, true);
		boolean majorFrameDelay = CommandUtil.optBool(args, 2, true);
		boolean worstCaseDeadline = CommandUtil.optBool(args, 3, true);
		boolean bestCaseEmptyQueue = CommandUtil.optBool(args, 4, true);
		boolean disableQueuingLatency = CommandUtil.optBool(args, 5, false);

		var instance = loadInstance(uri, iuri);
		var componentImplementation = instance.getComponentImplementation();
		var duri = componentImplementation == null || componentImplementation.eResource() == null
				? uri
				: componentImplementation.eResource().getURI();
		try {
			return access.doRead(duri.toString(), ctx -> runAnalysis(uri, iuri, asynchronousSystem, majorFrameDelay,
					worstCaseDeadline, bestCaseEmptyQueue, disableQueuingLatency)).get();
		} catch (InterruptedException e) {
			throw CommandUtil.interrupted("Latency analysis");
		} catch (ExecutionException e) {
			throw CommandUtil.executionFailed("Latency analysis", e.getCause());
		}
	}

	private static AnalysisCommandResult runAnalysis(URI uri, String iuri, boolean asynchronousSystem,
			boolean majorFrameDelay,
			boolean worstCaseDeadline, boolean bestCaseEmptyQueue, boolean disableQueuingLatency) {
		var resource = new ResourceSetImpl().getResource(uri, true);
		if (resource.getContents().isEmpty() || !(resource.getContents().get(0) instanceof SystemInstance inst)) {
			throw CommandUtil.requestFailed(iuri + " does not contain a system instance");
		}
		var checker = new FlowLatencyAnalysisSwitch(inst);
		var analysisResult = checker.invokeAndSaveResult(inst, null, asynchronousSystem, majorFrameDelay,
				worstCaseDeadline, bestCaseEmptyQueue, disableQueuingLatency);

		var resultURI = analysisResult.eResource().getURI();
		var csvURI = resultURI.trimFileExtension().appendFileExtension("csv");
		var converter = analysisResult.eResource().getResourceSet().getURIConverter();
		if (!converter.exists(resultURI, null)) {
			throw CommandUtil.requestFailed("Latency analysis did not write its result report");
		}
		var reports = new ArrayList<Report>();
		reports.add(new Report("result", resultURI.toString()));
		if (converter.exists(csvURI, null)) {
			reports.add(new Report("csv", csvURI.toString()));
		}
		return CommandUtil.result(analysisResult, "Latency analysis completed", iuri, reports);
	}

	private static SystemInstance loadInstance(URI uri, String iuri) {
		try {
			var resource = new ResourceSetImpl().getResource(uri, true);
			if (resource.getContents().isEmpty() || !(resource.getContents().get(0) instanceof SystemInstance instance)) {
				throw CommandUtil.requestFailed(iuri + " does not contain a system instance");
			}
			return instance;
		} catch (RuntimeException e) {
			if (e instanceof org.eclipse.lsp4j.jsonrpc.ResponseErrorException) {
				throw e;
			}
			throw CommandUtil.executionFailed("Loading instance model", e);
		}
	}
}
