/*******************************************************************************
 * VSCode extension for AADL
 *
 * Copyright 2026 Carnegie Mellon University.
 *
 * NO WARRANTY. THIS CARNEGIE MELLON UNIVERSITY AND SOFTWARE ENGINEERING INSTITUTE MATERIAL IS
 * FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON UNIVERSITY MAKES NO WARRANTIES OF ANY KIND,
 * EITHER EXPRESSED OR IMPLIED, AS TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF
 * FITNESS FOR PURPOSE OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE
 * MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT MAKE ANY WARRANTY OF ANY KIND WITH RESPECT TO
 * FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.
 *
 * Licensed under a BSD (SEI)-style license, please see LICENSE.txt
 * or contact permission@sei.cmu.edu for full terms.
 *
 * [DISTRIBUTION STATEMENT A] This material has been approved for public release and unlimited
 * distribution.  Please see Copyright notice for non-US Government use and distribution.
 *
 * This Software includes and/or makes use of Third-Party Software each subject to its own license.
 *
 * DM26-0821
 ******************************************************************************/
export type AnalysisStatus = 'info' | 'warning' | 'error';

export interface AnalysisReport {
	kind: string;
	uri: string;
}

export interface AnalysisDiagnostic {
	severity: string;
	uri: string;
	elementPath: string;
	message: string;
}

export interface AnalysisCommandResult {
	status: AnalysisStatus;
	summary: string;
	reports: AnalysisReport[];
	diagnostics: AnalysisDiagnostic[];
}

export interface AnalysisResultPresenter {
	showInformation(message: string): void;
	showWarning(message: string): void;
	showError(message: string): void;
	appendOutput(line: string): void;
	displayUri(uri: string): string;
}

export function presentAnalysisResult(result: AnalysisCommandResult, presenter: AnalysisResultPresenter): void {
	presenter.appendOutput(result.summary);
	for (const report of result.reports) {
		presenter.appendOutput(`${report.kind}: ${presenter.displayUri(report.uri)}`);
	}
	for (const diagnostic of result.diagnostics) {
		presenter.appendOutput(
			`${presenter.displayUri(diagnostic.uri)}:${diagnostic.elementPath}: `
			+ `${diagnostic.severity}: ${diagnostic.message}`
		);
	}

	switch (result.status) {
		case 'error':
			presenter.showError(result.summary);
			break;
		case 'warning':
			presenter.showWarning(result.summary);
			break;
		default:
			presenter.showInformation(result.summary);
	}
}
