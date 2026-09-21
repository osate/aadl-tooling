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
import * as path from 'path';
import * as os from 'os';
import * as fs from 'fs';
import {
	runTests,
	downloadAndUnzipVSCode,
} from '@vscode/test-electron';

function resolveDownloadedExecutable(downloadedExecutable: string): string {
	if (fs.existsSync(downloadedExecutable)) {
		return downloadedExecutable;
	}
	if (process.platform === 'darwin' && path.basename(downloadedExecutable) === 'Electron') {
		const codeExecutable = path.join(path.dirname(downloadedExecutable), 'Code');
		if (fs.existsSync(codeExecutable)) {
			return codeExecutable;
		}
	}
	return downloadedExecutable;
}

/**
 * Fails early, with the fix in the message, when the extension has no runtime
 * staged. Without this the suite would launch VS Code, wait for an activation
 * that cannot happen, and report a timeout.
 */
function requireStagedRuntime(extensionDevelopmentPath: string): void {
	const executable = path.join(
		extensionDevelopmentPath,
		'runtime',
		'bin',
		process.platform === 'win32' ? 'java.exe' : 'java',
	);
	if (!fs.existsSync(executable)) {
		throw new Error(
			`No bundled Java runtime at ${executable}. Stage one first: npm run stage-runtime`,
		);
	}
}

async function main() {
	try {
		// Commands launched from a VS Code extension host inherit this flag. The
		// downloaded Electron binary must run as VS Code, not as a Node process.
		delete process.env.ELECTRON_RUN_AS_NODE;

		const extensionDevelopmentPath = path.resolve(__dirname, '..', '..');
		const extensionTestsPath = path.resolve(__dirname, 'integration', 'index');
		const workspacePath = path.resolve(extensionDevelopmentPath, 'src', 'test', 'fixtures', 'workspace');
		requireStagedRuntime(extensionDevelopmentPath);
		const vscodeExecutablePath = resolveDownloadedExecutable(await downloadAndUnzipVSCode());
		// Keep the user-data-dir and extensions-dir short — VS Code's IPC
		// socket path has a hard 103-char limit on macOS, which we blow past
		// if we let the defaults land inside the deeply nested workspace.
		const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'aadl-vsc-'));
		const extensionsDir = fs.mkdtempSync(path.join(os.tmpdir(), 'aadl-ext-'));

		// --disable-extensions still loads the extension under development, so the
		// suite exercises the bundled runtime in isolation. It used to install
		// redhat.java here to supply a JVM, which made the tests need the
		// marketplace and let them skip themselves when it was unreachable.
		await runTests({
			extensionDevelopmentPath,
			extensionTestsPath,
			vscodeExecutablePath,
			launchArgs: [
				workspacePath,
				'--disable-extensions',
				'--user-data-dir', userDataDir,
				'--extensions-dir', extensionsDir,
			],
		});
	} catch (err) {
		console.error('Integration tests failed:', err);
		process.exit(1);
	}
}

main();
