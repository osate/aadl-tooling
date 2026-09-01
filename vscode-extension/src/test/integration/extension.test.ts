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
import * as vscode from 'vscode';

const EXTENSION_ID = 'osate.aadl2';

suite('extension presence', () => {
	test('extension is present', () => {
		assert.ok(vscode.extensions.getExtension(EXTENSION_ID),
			`extension ${EXTENSION_ID} not found`);
	});
});

suite('latency configuration defaults match server defaults', () => {
	const cfg = vscode.workspace.getConfiguration('aadl2Server.latency');
	const cases: Array<[string, boolean]> = [
		['asynchronousSystem', true],
		['majorFrameDelay', true],
		['worstCaseDeadline', true],
		['bestCaseEmptyQueue', true],
		['disableQueuingLatency', false],
	];
	for (const [key, def] of cases) {
		test(`${key} defaults to ${def}`, () => {
			assert.strictEqual(cfg.get<boolean>(key), def);
		});
	}
});

suite('reachability configuration defaults match VS Code command defaults', () => {
	const cfg = vscode.workspace.getConfiguration('aadl2Server.reachability');
	const cases: Array<[string, boolean]> = [
		['generateDot', true],
		['generateHtml', true],
		['generateSmv', true],
	];
	for (const [key, def] of cases) {
		test(`${key} defaults to ${def}`, () => {
			assert.strictEqual(cfg.get<boolean>(key), def);
		});
	}
});
