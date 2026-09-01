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
import * as path from 'path';
import * as vscode from 'vscode';

const EXTENSION_ID = 'osate.aadl2';

interface RedHatJavaApi {
	javaRequirement?: {
		// eslint-disable-next-line @typescript-eslint/naming-convention
		tooling_jre?: string;
	};
}

interface AadlExtensionApi {
	javaRuntime: {
		executable: string;
		home: string;
		source: string;
		majorVersion: number;
	};
}

function redHatJavaAvailable(): boolean {
	return !!vscode.extensions.getExtension('redhat.java');
}

async function waitForActivation(timeoutMs: number): Promise<boolean> {
	const ext = vscode.extensions.getExtension(EXTENSION_ID);
	assert.ok(ext, `extension ${EXTENSION_ID} not found`);
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (ext!.isActive) {
			return true;
		}
		await new Promise(r => setTimeout(r, 100));
	}
	return ext!.isActive;
}

async function waitForDefinition(
	document: vscode.TextDocument,
	position: vscode.Position,
	timeoutMs: number
): Promise<Array<vscode.Location | vscode.LocationLink>> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		try {
			const definitions = await vscode.commands.executeCommand<Array<vscode.Location | vscode.LocationLink>>(
				'vscode.executeDefinitionProvider',
				document.uri,
				position
			);
			if (definitions?.length) {
				return definitions;
			}
		} catch {
			// The server may still be processing the initial workspace build.
		}
		await new Promise(r => setTimeout(r, 250));
	}
	return [];
}

async function waitForHover(
	document: vscode.TextDocument,
	position: vscode.Position,
	timeoutMs: number
): Promise<vscode.Hover[]> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		try {
			const hovers = await vscode.commands.executeCommand<vscode.Hover[]>(
				'vscode.executeHoverProvider',
				document.uri,
				position
			);
			if (hovers?.length) {
				return hovers;
			}
		} catch {
			// The server may still be processing the initial workspace build.
		}
		await new Promise(r => setTimeout(r, 250));
	}
	return [];
}

async function waitForDocumentSymbols(
	document: vscode.TextDocument,
	timeoutMs: number
): Promise<Array<vscode.DocumentSymbol | vscode.SymbolInformation>> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		const symbols = await vscode.commands.executeCommand<Array<vscode.DocumentSymbol | vscode.SymbolInformation>>(
			'vscode.executeDocumentSymbolProvider',
			document.uri
		);
		if (symbols?.length) {
			return symbols;
		}
		await new Promise(r => setTimeout(r, 250));
	}
	return [];
}

async function waitForReferences(
	document: vscode.TextDocument,
	position: vscode.Position,
	timeoutMs: number
): Promise<vscode.Location[]> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		const references = await vscode.commands.executeCommand<vscode.Location[]>(
			'vscode.executeReferenceProvider',
			document.uri,
			position
		);
		if (references?.length) {
			return references;
		}
		await new Promise(r => setTimeout(r, 250));
	}
	return [];
}

suite('extension auto-activation on aadl files', function () {
	this.timeout(120000);

	let aadlFile: string;

	suiteSetup(function () {
		if (!redHatJavaAvailable()) {
			console.log('redhat.java not installed in this VS Code profile — skipping activation tests');
			this.skip();
		}
		const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
		assert.ok(workspaceFolder, 'integration test workspace is not open');
		aadlFile = path.join(workspaceFolder.uri.fsPath, 'TestProject', 'sample.aadl');
	});

	test('extension is inactive before any aadl file is opened', () => {
		const ext = vscode.extensions.getExtension(EXTENSION_ID);
		assert.ok(ext);
		assert.strictEqual(ext!.isActive, false,
			'extension should not be active before opening an .aadl file');
	});

	test('opening an .aadl file activates the extension', async () => {
		const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(aadlFile));
		assert.strictEqual(doc.languageId, 'aadl2',
			'opened document should be recognized as aadl2');
		const activated = await waitForActivation(60000);
		assert.strictEqual(activated, true, 'extension did not activate within 60s');
	});

	test('uses the Red Hat tooling JRE and requires Java 21 or newer', async () => {
		const redHatExtension = vscode.extensions.getExtension<RedHatJavaApi>('redhat.java');
		assert.ok(redHatExtension, 'redhat.java not found');
		const redHatApi = redHatExtension!.isActive
			? redHatExtension!.exports
			: await redHatExtension!.activate();
		const toolingJre = redHatApi?.javaRequirement?.tooling_jre;
		assert.ok(toolingJre, 'redhat.java did not provide a tooling JRE');

		const aadlExtension = vscode.extensions.getExtension<AadlExtensionApi>(EXTENSION_ID);
		assert.ok(aadlExtension?.isActive, 'AADL extension is not active');
		const runtime = aadlExtension!.exports.javaRuntime;
		const expectedExecutable = path.join(
			toolingJre!,
			'bin',
			process.platform === 'win32' ? 'java.exe' : 'java'
		);

		assert.strictEqual(runtime.source, 'Red Hat Java extension');
		assert.strictEqual(runtime.home, toolingJre);
		assert.strictEqual(runtime.executable, expectedExecutable);
		assert.ok(runtime.majorVersion >= 21, `expected Java 21+, got Java ${runtime.majorVersion}`);
	});

	test('custom commands are registered after activation', async () => {
		const all = await vscode.commands.getCommands(true);
		for (const cmd of [
			'aadl2.instantiate',
			'aadl2.analyze.latency',
			'aadl2.analyze.busLoad',
			'aadl2.analyze.reachability',
			'aadl2.restart'
		]) {
			assert.ok(all.includes(cmd), `command ${cmd} not registered`);
		}
	});

	test('predeclared property hover resolves Compute_Execution_Time', async () => {
		const source = await vscode.workspace.openTextDocument(vscode.Uri.file(aadlFile));
		const propertyOffset = source.getText().indexOf('COmpute_Execution_Time');
		assert.ok(propertyOffset >= 0);

		const hovers = await waitForHover(source, source.positionAt(propertyOffset + 2), 30000);
		assert.ok(hovers.length > 0, 'expected hover information for Compute_Execution_Time');

		const contents = hovers.flatMap(hover => hover.contents).map(content =>
			typeof content === 'string' ? content : content.value
		).join('\n');
		assert.ok(contents.includes('Compute_Execution_Time'), contents);
	});

	test('Compute_Execution_Time definition opens as a virtual AADL document', async () => {
		const source = await vscode.workspace.openTextDocument(vscode.Uri.file(aadlFile));
		const propertyOffset = source.getText().indexOf('COmpute_Execution_Time');
		assert.ok(propertyOffset >= 0);

		const definitions = await waitForDefinition(source, source.positionAt(propertyOffset + 2), 30000);
		assert.strictEqual(
			definitions.length,
			1,
			'expected one definition for Timing_Properties::Compute_Execution_Time'
		);

		const definition = definitions[0];
		const targetUri = definition instanceof vscode.Location ? definition.uri : definition.targetUri;
		const targetRange = definition instanceof vscode.Location
			? definition.range
			: definition.targetSelectionRange ?? definition.targetRange;

		assert.strictEqual(targetUri.scheme, 'platform');
		assert.ok(targetUri.path.endsWith('/Timing_Properties.aadl'), targetUri.toString());

		const target = await vscode.workspace.openTextDocument(targetUri);
		assert.strictEqual(target.languageId, 'aadl2');
		assert.ok(target.getText().includes('property set Timing_Properties is'));
		assert.ok(target.lineAt(targetRange.start.line).text.trim().startsWith('Compute_Execution_Time:'));
	});

	test('virtual contributed AADL documents receive language features', async () => {
		const targetUri = vscode.Uri.parse(
			'platform:/plugin/org.osate.aadl2.contrib/resources/properties/'
			+ 'Predeclared_Property_Sets/Timing_Properties.aadl'
		);
		const target = await vscode.workspace.openTextDocument(targetUri);
		const propertyOffset = target.getText().indexOf('Compute_Execution_Time:');
		assert.ok(propertyOffset >= 0);
		const propertyPosition = target.positionAt(propertyOffset + 2);

		const symbols = await waitForDocumentSymbols(target, 30000);
		assert.ok(symbols.length > 0, 'expected document symbols for Timing_Properties.aadl');

		const hovers = await waitForHover(target, propertyPosition, 30000);
		assert.ok(hovers.length > 0, 'expected hover information inside Timing_Properties.aadl');

		const typeOffset = target.getText().indexOf('Time_Range', propertyOffset);
		assert.ok(typeOffset >= 0);
		const definitions = await waitForDefinition(target, target.positionAt(typeOffset + 2), 30000);
		assert.ok(definitions.length > 0, 'expected go to definition inside Timing_Properties.aadl');

		const references = await waitForReferences(target, propertyPosition, 30000);
		assert.ok(
			references.some(reference => reference.uri.fsPath === aadlFile),
			'expected references to include sample.aadl'
		);
	});

	test('virtual AADL documents use the replacement client after restart', async () => {
		await vscode.commands.executeCommand('aadl2.restart');

		const memoryPropertiesUri = vscode.Uri.parse(
			'platform:/plugin/org.osate.aadl2.contrib/resources/properties/'
			+ 'Predeclared_Property_Sets/Memory_Properties.aadl'
		);
		const target = await vscode.workspace.openTextDocument(memoryPropertiesUri);

		assert.strictEqual(target.languageId, 'aadl2');
		assert.ok(target.getText().includes('property set Memory_Properties is'));
	});
});
