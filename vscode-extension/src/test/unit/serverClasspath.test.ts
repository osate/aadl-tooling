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
import * as os from 'os';
import * as path from 'path';
import { serverClasspath } from '../../serverClasspath';

suite('language server classpath', () => {
	test('uses every packaged language server bundle', () => {
		const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'aadl-server-classpath-'));
		try {
			for (const name of [
				'org.antlr.antlr4-runtime_4.13.2.jar',
				'org.osate.aadl.ls_1.0.0.jar',
			]) {
				fs.writeFileSync(path.join(directory, name), '');
			}

			const entries = serverClasspath(directory).split(path.delimiter).map(entry => path.basename(entry));
			assert.deepStrictEqual(entries, [
				'org.antlr.antlr4-runtime_4.13.2.jar',
				'org.osate.aadl.ls_1.0.0.jar',
			]);
		} finally {
			fs.rmSync(directory, { recursive: true, force: true });
		}
	});

	test('ignores non-jar files', () => {
		const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'aadl-server-classpath-'));
		try {
			fs.writeFileSync(path.join(directory, 'org.osate.aadl.ls_1.0.0.jar'), '');
			fs.writeFileSync(path.join(directory, 'README.txt'), '');
			assert.deepStrictEqual(
				serverClasspath(directory).split(path.delimiter).map(entry => path.basename(entry)),
				['org.osate.aadl.ls_1.0.0.jar'],
			);
		} finally {
			fs.rmSync(directory, { recursive: true, force: true });
		}
	});
});
