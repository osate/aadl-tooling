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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.osate.aadl2.NamedElement;
import org.osate.aadl2.instance.InstanceObject;
import org.osate.aadl.ls.commands.AnalysisCommandResult.AnalysisDiagnostic;
import org.osate.aadl.ls.commands.AnalysisCommandResult.Report;
import org.osate.result.AnalysisResult;
import org.osate.result.Diagnostic;
import org.osate.result.Result;
import org.osate.result.ResultType;

import com.google.gson.JsonPrimitive;

final class CommandUtil {

	private CommandUtil() {
	}

	static boolean optBool(List<Object> args, int index, boolean defaultValue) {
		if (args == null || index >= args.size()) {
			return defaultValue;
		}
		var arg = args.get(index);
		if (arg instanceof JsonPrimitive p && p.isBoolean()) {
			return p.getAsBoolean();
		}
		return defaultValue;
	}

	static Path toPath(URI uri) {
		if (!uri.isFile()) {
			throw new IllegalArgumentException("Expected file URI: " + uri);
		}
		return Path.of(java.net.URI.create(uri.toString()));
	}

	static URI requiredFileUri(ExecuteCommandParams params) {
		var args = params.getArguments();
		if (args == null || args.isEmpty() || !(args.get(0) instanceof JsonPrimitive p) || !p.isString()) {
			throw responseError(ResponseErrorCode.InvalidParams, "A file URI is required");
		}
		try {
			var uri = URI.createURI(p.getAsString());
			if (!uri.isFile()) {
				throw responseError(ResponseErrorCode.InvalidParams, "A file URI is required: " + p.getAsString());
			}
			return uri;
		} catch (IllegalArgumentException e) {
			throw responseError(ResponseErrorCode.InvalidParams, "Invalid file URI: " + p.getAsString());
		}
	}

	static ResponseErrorException requestFailed(String message) {
		return responseError(ResponseErrorCode.RequestFailed, message);
	}

	static ResponseErrorException interrupted(String operation) {
		Thread.currentThread().interrupt();
		return responseError(ResponseErrorCode.RequestCancelled, operation + " was cancelled");
	}

	static RuntimeException executionFailed(String operation, Throwable cause) {
		if (cause instanceof ResponseErrorException ree) {
			return ree;
		}
		var detail = cause == null || cause.getMessage() == null ? "unknown error" : cause.getMessage();
		return requestFailed(operation + " failed: " + detail);
	}

	static AnalysisCommandResult result(AnalysisResult analysisResult, String successSummary, String instanceUri,
			List<Report> reports) {
		var diagnostics = new LinkedHashSet<AnalysisDiagnostic>();
		collectDiagnostics(analysisResult.getModelElement(), analysisResult.getDiagnostics(), instanceUri, diagnostics);
		if (isError(analysisResult.getResultType()) && analysisResult.getMessage() != null) {
			diagnostics.add(new AnalysisDiagnostic("error", instanceUri,
					elementPath(analysisResult.getModelElement(), "<unknown>"), singleLine(analysisResult.getMessage())));
		}
		for (var result : analysisResult.getResults()) {
			collectResultDiagnostics(result, instanceUri, diagnostics);
		}

		var sorted = new ArrayList<>(diagnostics);
		sorted.sort(Comparator.comparing(AnalysisDiagnostic::uri)
				.thenComparing(AnalysisDiagnostic::elementPath)
				.thenComparing(AnalysisDiagnostic::severity)
				.thenComparing(AnalysisDiagnostic::message));
		var status = sorted.stream().anyMatch(d -> "error".equals(d.severity())) ? "error"
				: sorted.stream().anyMatch(d -> "warning".equals(d.severity())) ? "warning" : "info";
		var summary = "info".equals(status) ? successSummary : sorted.stream()
				.filter(d -> d.severity().equals(status))
				.map(AnalysisDiagnostic::message)
				.findFirst()
				.orElse(successSummary);
		return new AnalysisCommandResult(status, summary, reports, sorted);
	}

	static String formatInstanceDiagnostic(String instancePath, String elementPath, String severity, String message) {
		return instancePath + ":" + elementPath + ": " + severity.toLowerCase(Locale.ROOT) + ": "
				+ singleLine(message);
	}

	static void appendSortedDiagnosticLines(StringBuilder output, List<String> diagnosticLines) {
		Collections.sort(diagnosticLines);
		for (var line : diagnosticLines) {
			output.append(line).append('\n');
		}
	}

	private static void collectResultDiagnostics(Result result, String instanceUri,
			LinkedHashSet<AnalysisDiagnostic> diagnostics) {
		var fallback = elementPath(result.getModelElement(), "<unknown>");
		collectDiagnostics(result.getModelElement(), result.getDiagnostics(), instanceUri, diagnostics);
		if (isError(result.getResultType()) && result.getMessage() != null) {
			diagnostics.add(new AnalysisDiagnostic("error", instanceUri, fallback, singleLine(result.getMessage())));
		}
		for (var subResult : result.getSubResults()) {
			collectResultDiagnostics(subResult, instanceUri, diagnostics);
		}
	}

	private static void collectDiagnostics(EObject modelElement, Iterable<Diagnostic> source, String instanceUri,
			LinkedHashSet<AnalysisDiagnostic> diagnostics) {
		var fallback = elementPath(modelElement, "<unknown>");
		for (var diagnostic : source) {
			var path = elementPath(diagnostic.getModelElement(), fallback);
			diagnostics.add(new AnalysisDiagnostic(
					diagnostic.getDiagnosticType().getName().toLowerCase(Locale.ROOT), instanceUri, path,
					singleLine(diagnostic.getMessage())));
		}
	}

	private static boolean isError(ResultType type) {
		return type == ResultType.ERROR || type == ResultType.FAILURE;
	}

	private static String singleLine(String message) {
		return String.valueOf(message).replace('\r', ' ').replace('\n', ' ');
	}

	private static ResponseErrorException responseError(ResponseErrorCode code, String message) {
		return new ResponseErrorException(new ResponseError(code, message, null));
	}

	private static String elementPath(EObject element, String fallback) {
		if (element instanceof InstanceObject io) {
			return io.getComponentInstancePath();
		}
		if (element instanceof NamedElement ne && ne.getName() != null) {
			return ne.getName();
		}
		return fallback;
	}
}
