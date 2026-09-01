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
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.xtext.ide.server.ILanguageServerAccess;
import org.eclipse.xtext.util.CancelIndicator;
import org.osate.aadl2.AadlPackage;
import org.osate.aadl2.ComponentImplementation;
import org.osate.aadl2.PackageSection;
import org.osate.aadl2.instance.InstanceObject;
import org.osate.aadl2.instantiation.InstantiateModel;
import org.osate.aadl2.modelsupport.errorreporting.AnalysisErrorReporterManager;
import org.osate.aadl2.modelsupport.errorreporting.QueuingAnalysisErrorReporter;

import com.google.common.collect.Iterables;
import com.google.gson.JsonPrimitive;

final class InstantiateCommand implements Command {

	static final String NAME = "aadl.instantiate";

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Object execute(ExecuteCommandParams params, ILanguageServerAccess access, CancelIndicator cancelIndicator) {
		var arg1 = (JsonPrimitive) Iterables.getFirst(params.getArguments(), null);
		var arg2 = (JsonPrimitive) Iterables.get(params.getArguments(), 1, null);
		String uri = arg1.getAsString();
		String name = arg2.getAsString();
		if (uri == null) {
			return "Param Uri Missing";
		}
		try {
			return access.doRead(uri, ctx -> instantiate(ctx, uri, name)).get();
		} catch (InterruptedException | ExecutionException e) {
			return e.getMessage();
		}
	}

	private static String instantiate(ILanguageServerAccess.Context ctx, String uri, String name) {
		var contents = ctx.getResource().getContents();
		if (contents.isEmpty() || !(contents.get(0) instanceof AadlPackage pkg)) {
			return "Error: " + uri + " does not contain an AADL package";
		}
		int sep = name.lastIndexOf("::");
		String simpleName = sep < 0 ? name : name.substring(sep + 2);
		String prefix = sep < 0 ? null : name.substring(0, sep);
		if (prefix != null && !prefix.equalsIgnoreCase(pkg.getName())) {
			return "Error: component implementation " + name + " not found.";
		}
		ComponentImplementation ci = findImplementation(pkg, simpleName);
		if (ci == null) {
			return "Error: component implementation " + name + " not found.";
		}

		var result = new StringBuilder();
		try {
			var errorManager = new AnalysisErrorReporterManager(QueuingAnalysisErrorReporter.factory);
			var si = InstantiateModel.instantiate(ci, errorManager);
			si.eResource().save(null);
			result.append("Instantiated " + name + " as " + si.getName() + "\n");

			var diags = ((QueuingAnalysisErrorReporter) errorManager.getReporter(si.eResource())).getErrors();
			var diagLines = new ArrayList<String>();
			for (var d : diags) {
				var e = d.where;
				while (Objects.nonNull(e) && !(e instanceof InstanceObject)) {
					e = e.getOwner();
				}
				var io = (InstanceObject) e;
				diagLines.add(CommandUtil.formatInstanceDiagnostic(CommandUtil.toPath(si.eResource().getURI()).toString(),
						io.getComponentInstancePath(), d.kind, d.message));
			}
			CommandUtil.appendSortedDiagnosticLines(result, diagLines);
		} catch (Exception e) {
			result.append("Exception: " + e.getMessage());
			e.printStackTrace();
		}
		return result.toString();
	}

	private static ComponentImplementation findImplementation(AadlPackage pkg, String simpleName) {
		var sections = new ArrayList<PackageSection>();
		if (pkg.getOwnedPublicSection() != null) {
			sections.add(pkg.getOwnedPublicSection());
		}
		if (pkg.getOwnedPrivateSection() != null) {
			sections.add(pkg.getOwnedPrivateSection());
		}
		for (var section : sections) {
			for (var cls : section.getOwnedClassifiers()) {
				if (cls instanceof ComponentImplementation ci && simpleName.equalsIgnoreCase(cls.getName())) {
					return ci;
				}
			}
		}
		return null;
	}
}
