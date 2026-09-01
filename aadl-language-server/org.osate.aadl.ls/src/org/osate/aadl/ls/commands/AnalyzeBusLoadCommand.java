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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.xtext.ide.server.ILanguageServerAccess;
import org.eclipse.xtext.util.CancelIndicator;
import org.osate.aadl2.instance.SystemInstance;
import org.osate.aadl.ls.commands.AnalysisCommandResult.Report;
import org.osate.analysis.resource.budgets.busload.NewBusLoadAnalysis;

final class AnalyzeBusLoadCommand implements Command {

	static final String NAME = "aadl.analyze.busLoad";
	private static final String REPORTS_DIR = "reports";
	private static final String ANALYSIS_DIR = "BusLoad";
	private static final String REPORT_NAME_TAIL = "__BusLoad.csv";

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Object execute(ExecuteCommandParams params, ILanguageServerAccess access, CancelIndicator cancelIndicator) {
		var uri = CommandUtil.requiredFileUri(params);
		var iuri = uri.toString();
		Resource res = new ResourceSetImpl().getResource(uri, true);
		if (res.getContents().isEmpty() || !(res.getContents().get(0) instanceof SystemInstance instance)) {
			throw CommandUtil.requestFailed(iuri + " does not contain a system instance");
		}
		var componentImplementation = instance.getComponentImplementation();
		var duri = componentImplementation == null || componentImplementation.eResource() == null
				? uri
				: componentImplementation.eResource().getURI();
		try {
			return access.doRead(duri.toString(), ctx -> runAnalysis(uri, iuri)).get();
		} catch (InterruptedException e) {
			throw CommandUtil.interrupted("Bus load analysis");
		} catch (ExecutionException e) {
			throw CommandUtil.executionFailed("Bus load analysis", e.getCause());
		}
	}

	private static AnalysisCommandResult runAnalysis(URI uri, String iuri) {
		var resource = new ResourceSetImpl().getResource(uri, true);
		if (resource.getContents().isEmpty() || !(resource.getContents().get(0) instanceof SystemInstance inst)) {
			throw CommandUtil.requestFailed(iuri + " does not contain a system instance");
		}

		try {
			createReportDirectory(uri);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		var analysisResult = new NewBusLoadAnalysis().invoke(null, inst);
		var csvURI = uri.trimSegments(1)
				.appendSegment(REPORTS_DIR)
				.appendSegment(ANALYSIS_DIR)
				.appendSegment(uri.trimFileExtension().lastSegment() + REPORT_NAME_TAIL);
		if (analysisResult == null) {
			throw CommandUtil.requestFailed("Bus load analysis did not return a result");
		}
		if (!inst.eResource().getResourceSet().getURIConverter().exists(csvURI, null)) {
			throw CommandUtil.requestFailed("Bus load analysis did not write its CSV report");
		}
		return CommandUtil.result(analysisResult, "Bus load analysis completed", iuri,
				List.of(new Report("csv", csvURI.toString())));
	}

	private static void createReportDirectory(URI instanceURI) throws IOException {
		var reportURI = instanceURI.trimSegments(1).appendSegment(REPORTS_DIR).appendSegment(ANALYSIS_DIR);
		if (reportURI.isFile()) {
			Files.createDirectories(CommandUtil.toPath(reportURI));
		}
	}
}
