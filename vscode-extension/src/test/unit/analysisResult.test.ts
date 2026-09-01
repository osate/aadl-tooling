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
import * as assert from 'assert';
import {
	AnalysisCommandResult,
	AnalysisResultPresenter,
	presentAnalysisResult
} from '../../analysisResult';

function present(status: AnalysisCommandResult['status']) {
	const shown: string[] = [];
	const output: string[] = [];
	const presenter: AnalysisResultPresenter = {
		showInformation: message => shown.push(`info:${message}`),
		showWarning: message => shown.push(`warning:${message}`),
		showError: message => shown.push(`error:${message}`),
		appendOutput: line => output.push(line),
		displayUri: uri => uri.replace('file:///', '/'),
	};
	presentAnalysisResult({
		status,
		summary: 'Analysis summary',
		reports: [{ kind: 'csv', uri: 'file:///reports/result.csv' }],
		diagnostics: [{
			severity: 'error',
			uri: 'file:///instances/model.aaxl2',
			elementPath: 'top.bus',
			message: 'over budget',
		}],
	}, presenter);
	return { shown, output };
}

suite('analysis result presentation', () => {
	for (const status of ['info', 'warning', 'error'] as const) {
		test(`uses a ${status} toast`, () => {
			const { shown } = present(status);
			assert.deepStrictEqual(shown, [`${status}:Analysis summary`]);
		});
	}

	test('writes report locations and diagnostics to output', () => {
		const { output } = present('error');
		assert.deepStrictEqual(output, [
			'Analysis summary',
			'csv: /reports/result.csv',
			'/instances/model.aaxl2:top.bus: error: over budget',
		]);
	});
});
