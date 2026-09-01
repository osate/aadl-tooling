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
import {
	commands,
	extensions,
	window,
	workspace,
	ExtensionContext,
	Uri,
	DocumentSymbol,
	FileSystemWatcher
} from 'vscode';

import {
	LanguageClient,
	LanguageClientOptions,
	ServerOptions,
} from 'vscode-languageclient/node';

import * as path from 'path';
import { execFile } from 'child_process';

import { findSymbol } from './symbols';
import { readLatencyArgs, toCommandArgs as toLatencyCommandArgs } from './latency';
import { readReachabilityArgs, toCommandArgs as toReachabilityCommandArgs } from './reachability';
import {
	CONTRIBUTED_AADL_URI_SCHEME,
	ContributedAadlContentProvider
} from './contributedAadl';
import { AnalysisCommandResult, presentAnalysisResult } from './analysisResult';
import {
	javaExecutableIn,
	JavaRuntime,
	minimumJavaMajorVersion,
	parseJavaMajorVersion
} from './javaRuntime';
import { serverClasspath } from './serverClasspath';

let client: LanguageClient;

interface RedHatJavaApi {
	javaRequirement?: {
		// eslint-disable-next-line @typescript-eslint/naming-convention
		tooling_jre?: string;
	};
}

export interface AadlExtensionApi {
	javaRuntime: JavaRuntime;
}

function errorMessage(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}

async function redHatJavaHome(): Promise<string> {
	const ext = extensions.getExtension<RedHatJavaApi>('redhat.java');
	if (!ext) {
		throw new Error('The Red Hat Java extension (redhat.java) is required to run the AADL language server.');
	}
	let api: RedHatJavaApi | undefined;
	try {
		api = ext.isActive ? ext.exports : await ext.activate();
	} catch (error) {
		throw new Error(`Could not activate the Red Hat Java extension: ${errorMessage(error)}`);
	}
	const javaHome = api?.javaRequirement?.tooling_jre;
	if (!javaHome) {
		throw new Error('The Red Hat Java extension did not provide a tooling JRE. Update or reinstall redhat.java.');
	}
	return javaHome;
}

async function javaMajorVersion(executable: string): Promise<number | undefined> {
	return new Promise(resolve => {
		execFile(executable, ['-version'], { windowsHide: true }, (error, stdout, stderr) => {
			if (error) {
				resolve(undefined);
				return;
			}
			resolve(parseJavaMajorVersion(`${stdout}\n${stderr}`));
		});
	});
}

async function resolveJavaRuntime(): Promise<JavaRuntime> {
	const toolingJre = await redHatJavaHome();
	const executable = javaExecutableIn(toolingJre);
	const majorVersion = await javaMajorVersion(executable);
	if (majorVersion === undefined) {
		throw new Error(
			`Could not run the Red Hat Java extension's tooling JRE at ${executable}. `
			+ 'Update or reinstall redhat.java.'
		);
	}
	if (majorVersion < minimumJavaMajorVersion) {
		throw new Error(
			`The Red Hat Java extension's tooling JRE is Java ${majorVersion}; `
			+ `Java ${minimumJavaMajorVersion} or newer is required. Update redhat.java.`
		);
	}
	return { executable, home: toolingJre, source: 'Red Hat Java extension', majorVersion };
}

async function startLanguageServer(
	context: ExtensionContext,
	aadlFileWatcher: FileSystemWatcher
): Promise<JavaRuntime> {
	const java = await resolveJavaRuntime();
	const classpath = serverClasspath(context.asAbsolutePath(path.join('server', 'aadl', 'lib')));
	const mainClass = 'org.osate.aadl.ls.RunAadl2Server';
	// eslint-disable-next-line @typescript-eslint/naming-convention
	const baseEnv = { ...process.env, ...(java.home ? { JAVA_HOME: java.home } : {}) };

	const serverOptions: ServerOptions = {
		run: {
			command: java.executable,
			args: ['-classpath', classpath, mainClass],
			options: { env: baseEnv }
		},
		debug: {
			command: java.executable,
			args: [
				'-agentlib:jdwp=transport=dt_socket,server=y,address=localhost:8123,suspend=y,quiet=y',
				'-classpath', classpath, mainClass,
				'-trace', '-log', 'debug'
			],
			options: { env: baseEnv }
		}
	};

	const clientOptions: LanguageClientOptions = {
		documentSelector: [
			{ scheme: 'file', language: 'aadl2' },
			{ scheme: CONTRIBUTED_AADL_URI_SCHEME, language: 'aadl2' }
		],
		synchronize: {
			fileEvents: aadlFileWatcher
		}
	};

	// Create and start the language client
	client = new LanguageClient(
		'aadl2Server',
		'AADL2 Language Server',
		serverOptions,
		clientOptions
	);
	client.outputChannel.appendLine(
		`Using ${java.source} (Java ${java.majorVersion}) to launch language server: ${java.executable}`
	);

	await client.start();
	return java;
}

function showAnalysisResult(result: AnalysisCommandResult): void {
	presentAnalysisResult(result, {
		showInformation: message => { window.showInformationMessage(message); },
		showWarning: message => { window.showWarningMessage(message); },
		showError: message => { window.showErrorMessage(message); },
		appendOutput: line => { client.outputChannel.appendLine(line); },
		displayUri: uriText => {
			const uri = Uri.parse(uriText);
			return uri.scheme === 'file' ? uri.fsPath : uriText;
		}
	});
}

export async function activate(context: ExtensionContext): Promise<AadlExtensionApi> {
	const aadlFileWatcher = workspace.createFileSystemWatcher('**/*.aadl');
	context.subscriptions.push(aadlFileWatcher);
	// Start the language server
	const javaRuntime = await startLanguageServer(context, aadlFileWatcher);

	// Make plugin-contributed AADL sources available as read-only virtual documents.
	// Resolve the client lazily because the restart command replaces the client instance.
	const contributedAadlProvider = workspace.registerTextDocumentContentProvider(
		CONTRIBUTED_AADL_URI_SCHEME,
		new ContributedAadlContentProvider(() => client)
	);
	context.subscriptions.push(contributedAadlProvider);

	// Register instantiate command
	const instantiateCommand = commands.registerCommand('aadl2.instantiate', async () => {
		const activeEditor = window.activeTextEditor;
		if (!activeEditor || activeEditor.document.languageId !== 'aadl2') {
			return;
		}

		const uri = activeEditor.document.uri;
		const pos = activeEditor.selection.anchor;

		try {
			const symbols = await commands.executeCommand<DocumentSymbol[]>(
				'vscode.executeDocumentSymbolProvider',
				uri
			);

			if (!symbols) {
				return;
			}

			const decl = findSymbol(symbols, pos);
			if (!decl) {
				window.showWarningMessage('No component implementation found at cursor position');
				return;
			}

			const msg = await commands.executeCommand<string>('aadl.instantiate', uri.toString(), decl.name);
			if (msg) {
				window.showInformationMessage(msg);
			}
		} catch (error) {
			window.showErrorMessage(`Instantiation failed: ${error}`);
		}
	});
	context.subscriptions.push(instantiateCommand);

	// Register latency analysis command
	const latencyAnalysisCommand = commands.registerCommand('aadl2.analyze.latency', async (uri: Uri) => {
		try {
			const args = readLatencyArgs(workspace.getConfiguration('aadl2Server.latency', uri));
			const result = await commands.executeCommand<AnalysisCommandResult>(
				'aadl.analyze.latency',
				...toLatencyCommandArgs(uri.toString(), args)
			);
			if (result) {
				showAnalysisResult(result);
			}
		} catch (error) {
			window.showErrorMessage(`Latency analysis failed: ${error}`);
		}
	});
	context.subscriptions.push(latencyAnalysisCommand);

	// Register bus load analysis command
	const busLoadAnalysisCommand = commands.registerCommand('aadl2.analyze.busLoad', async (uri: Uri) => {
		try {
			const result = await commands.executeCommand<AnalysisCommandResult>(
				'aadl.analyze.busLoad',
				uri.toString()
			);
			if (result) {
				showAnalysisResult(result);
			}
		} catch (error) {
			window.showErrorMessage(`Bus load analysis failed: ${error}`);
		}
	});
	context.subscriptions.push(busLoadAnalysisCommand);

	// Register mode reachability analysis command
	const reachabilityAnalysisCommand = commands.registerCommand('aadl2.analyze.reachability', async (uri: Uri) => {
		try {
			const args = readReachabilityArgs(workspace.getConfiguration('aadl2Server.reachability', uri));
			const result = await commands.executeCommand<AnalysisCommandResult>(
				'aadl.analyze.reachability',
				...toReachabilityCommandArgs(uri.toString(), args)
			);
			if (result) {
				showAnalysisResult(result);
			}
		} catch (error) {
			window.showErrorMessage(`Mode reachability analysis failed: ${error}`);
		}
	});
	context.subscriptions.push(reachabilityAnalysisCommand);

	// Register restart command
	const restartCommand = commands.registerCommand('aadl2.restart', async () => {
		try {
			window.showInformationMessage('Restarting AADL2 Language Server...');

			// Stop the current client
			if (client) {
				await client.stop();
			}

			// Start a new client
			await startLanguageServer(context, aadlFileWatcher);

			window.showInformationMessage('AADL2 Language Server restarted successfully');
		} catch (error) {
			window.showErrorMessage(`Failed to restart language server: ${error}`);
		}
	});
	context.subscriptions.push(restartCommand);

	return { javaRuntime };
}

export function deactivate(): Thenable<void> | undefined {
	return client?.stop();
}
