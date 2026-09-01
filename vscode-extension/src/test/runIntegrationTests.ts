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
import * as cp from 'child_process';
import {
	runTests,
	downloadAndUnzipVSCode,
	resolveCliArgsFromVSCodeExecutablePath,
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

function locateInstalledRedHatJava(extensionsDir: string): string | undefined {
	if (!fs.existsSync(extensionsDir)) {
		return undefined;
	}
	const entries = fs.readdirSync(extensionsDir, { withFileTypes: true });
	const match = entries
		.filter(e => e.isDirectory() && /^redhat\.java-/i.test(e.name))
		.map(e => e.name)
		.sort()
		.pop();
	return match ? path.join(extensionsDir, match) : undefined;
}

async function tryDownloadRedHatJava(extensionsDir: string, userDataDir: string): Promise<boolean> {
	let vscodeExe: string;
	try {
		vscodeExe = await downloadAndUnzipVSCode();
	} catch {
		return false;
	}
	const [cli, ...cliArgs] = resolveCliArgsFromVSCodeExecutablePath(vscodeExe);
	// NODE_USE_SYSTEM_CA=1 makes Node consult the OS trust store, which on
	// macOS picks up corp roots from the keychain. Falls back gracefully
	// on environments where the flag is unrecognized.
	const result = cp.spawnSync(
		cli,
		[
			...cliArgs,
			'--user-data-dir', userDataDir,
			'--extensions-dir', extensionsDir,
			'--install-extension', 'redhat.java',
			'--force',
		],
		{
			stdio: 'inherit',
			env: { ...process.env, NODE_USE_SYSTEM_CA: '1' },
		},
	);
	return result.status === 0;
}

async function main() {
	try {
		// Commands launched from a VS Code extension host inherit this flag. The
		// downloaded Electron binary must run as VS Code, not as a Node process.
		delete process.env.ELECTRON_RUN_AS_NODE;

		const extensionDevelopmentPath = path.resolve(__dirname, '..', '..');
		const extensionTestsPath = path.resolve(__dirname, 'integration', 'index');
		const workspacePath = path.resolve(extensionDevelopmentPath, 'src', 'test', 'fixtures', 'workspace');
		const vscodeExecutablePath = resolveDownloadedExecutable(await downloadAndUnzipVSCode());
		// Keep the user-data-dir and extensions-dir short — VS Code's IPC
		// socket path has a hard 103-char limit on macOS, which we blow past
		// if we let the defaults land inside the deeply nested workspace.
		const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'aadl-vsc-'));
		const extensionsDir = fs.mkdtempSync(path.join(os.tmpdir(), 'aadl-ext-'));

		// The AADL extension's activate() resolves a tooling JRE through
		// redhat.java. To install it we, in order:
		//   1. copy from ~/.vscode/extensions if present;
		//   2. download from the marketplace via the VS Code CLI, with
		//      NODE_USE_SYSTEM_CA=1 so corp-MITM TLS chains validate
		//      against the OS keychain.
		// If both fail the activation suite skips itself.
		const userExtDir = path.join(os.homedir(), '.vscode', 'extensions');
		const localCopy = locateInstalledRedHatJava(userExtDir);
		if (localCopy) {
			const dest = path.join(extensionsDir, path.basename(localCopy));
			fs.cpSync(localCopy, dest, { recursive: true });
		} else if (!(await tryDownloadRedHatJava(extensionsDir, userDataDir))) {
			console.warn('redhat.java not available locally and download failed; activation tests will skip.');
		}

		// Don't pass --disable-extensions: the AADL extension's activate()
		// requires redhat.java's tooling JRE.
		await runTests({
			extensionDevelopmentPath,
			extensionTestsPath,
			vscodeExecutablePath,
			launchArgs: [
				workspacePath,
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
