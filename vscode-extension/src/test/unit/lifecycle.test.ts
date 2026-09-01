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
import * as fs from 'fs';
import * as path from 'path';

suite('language server lifecycle', () => {
	const root = path.resolve(__dirname, '..', '..', '..');
	const source = fs.readFileSync(path.join(root, 'src', 'extension.ts'), 'utf8');

	test('relies on vscode-languageclient for workspace-folder notifications', () => {
		assert.ok(!source.includes('onDidChangeWorkspaceFolders'),
			'the extension must not register a duplicate workspace-folder listener');
	});

	test('creates one file watcher and reuses it when restarting', () => {
		assert.strictEqual((source.match(/createFileSystemWatcher/g) ?? []).length, 1);
		assert.ok(source.includes('context.subscriptions.push(aadlFileWatcher)'));
		assert.strictEqual((source.match(/startLanguageServer\(context, aadlFileWatcher\)/g) ?? []).length, 2,
			'activation and restart must share the same watcher');
	});

	test('launches the server main class through Java instead of a platform script', () => {
		assert.ok(source.includes("const mainClass = 'org.osate.aadl.ls.RunAadl2Server'"));
		assert.ok(source.includes("args: ['-classpath', classpath, mainClass]"));
		assert.ok(!source.includes('aadl-standalone.bat'));
		assert.ok(!source.includes("path.join('server', 'aadl', 'bin'"));
	});

	test('does not fall back from the Red Hat tooling JRE', () => {
		assert.ok(source.includes("source: 'Red Hat Java extension'"));
		assert.ok(!source.includes("source: 'JAVA_HOME'"));
		assert.ok(!source.includes("source: 'PATH'"));
	});
});
