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
import * as oniguruma from 'vscode-oniguruma';
import * as textmate from 'vscode-textmate';

interface GrammarPattern {
	include?: string;
	match?: string;
	begin?: string;
	name?: string;
	contentName?: string;
	patterns?: GrammarPattern[];
}

interface TextMateGrammar {
	scopeName: string;
	patterns: GrammarPattern[];
	repository: Record<string, GrammarPattern>;
}

interface ExtensionManifest {
	contributes: {
		languages: Array<{ id: string }>;
		grammars: Array<{
			language: string;
			scopeName: string;
			path: string;
			embeddedLanguages?: Record<string, string>;
		}>;
	};
}

const CLIENT_ROOT = path.resolve(__dirname, '..', '..', '..');

function loadGrammar(name: string): TextMateGrammar {
	return JSON.parse(fs.readFileSync(path.join(CLIENT_ROOT, 'syntaxes', name), 'utf8'));
}

function loadManifest(): ExtensionManifest {
	return JSON.parse(fs.readFileSync(path.join(CLIENT_ROOT, 'package.json'), 'utf8'));
}

function javascriptRegex(pattern: string): RegExp {
	const caseInsensitive = pattern.includes('(?i:');
	const source = pattern.replace(/\(\?i:/g, '(?:');
	return new RegExp(`^(?:${source})$`, caseInsensitive ? 'i' : '');
}

function matchScopes(grammar: TextMateGrammar, text: string): string[] {
	const scopes: string[] = [];

	const visit = (pattern: GrammarPattern): void => {
		if (pattern.include?.startsWith('#')) {
			visit(grammar.repository[pattern.include.slice(1)]);
		} else if (pattern.patterns) {
			for (const nested of pattern.patterns) {
				visit(nested);
			}
		} else if (pattern.match && pattern.name && javascriptRegex(pattern.match).test(text)) {
			scopes.push(pattern.name);
		}
	};

	for (const pattern of grammar.patterns) {
		visit(pattern);
	}
	return scopes;
}

function assertScope(grammar: TextMateGrammar, text: string, expectedScope: string): void {
	assert.ok(
		matchScopes(grammar, text).includes(expectedScope),
		`expected ${JSON.stringify(text)} to match ${expectedScope}`,
	);
}

async function loadAadlGrammar(): Promise<textmate.IGrammar> {
	const wasm = fs.readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm'));
	const wasmBuffer = wasm.buffer.slice(wasm.byteOffset, wasm.byteOffset + wasm.byteLength) as ArrayBuffer;
	await oniguruma.loadWASM(wasmBuffer);

	const scopeFiles: Record<string, string> = {
		'source.aadl2': 'aadl2.json',
		'source.aadl2.emv2': 'emv2.json',
		'source.aadl2.behavior': 'behavior.json',
	};
	const registry = new textmate.Registry({
		onigLib: Promise.resolve({
			createOnigScanner: patterns => new oniguruma.OnigScanner(patterns),
			createOnigString: text => new oniguruma.OnigString(text),
		}),
		loadGrammar: async scopeName => {
			const grammarFile = scopeFiles[scopeName];
			return grammarFile
				? textmate.parseRawGrammar(
					fs.readFileSync(path.join(CLIENT_ROOT, 'syntaxes', grammarFile), 'utf8'),
					grammarFile,
				)
				: null;
		},
	});
	const grammar = await registry.loadGrammar('source.aadl2');
	assert.ok(grammar, 'AADL TextMate grammar did not load');
	return grammar;
}

suite('embedded annex TextMate grammars', () => {
	const manifest = loadManifest();
	const aadl = loadGrammar('aadl2.json');
	const emv2 = loadGrammar('emv2.json');
	const behavior = loadGrammar('behavior.json');

	test('manifest registers both grammars and embedded language mappings', () => {
		const languageIds = manifest.contributes.languages.map(language => language.id);
		assert.ok(languageIds.includes('aadl2-emv2'));
		assert.ok(languageIds.includes('aadl2-behavior'));

		const aadlContribution = manifest.contributes.grammars
			.find(grammar => grammar.language === 'aadl2');
		assert.deepStrictEqual(aadlContribution?.embeddedLanguages, {
			'meta.embedded.block.emv2': 'aadl2-emv2',
			'meta.embedded.block.behavior': 'aadl2-behavior',
		});

		const emv2Contribution = manifest.contributes.grammars
			.find(grammar => grammar.language === 'aadl2-emv2');
		assert.deepStrictEqual(emv2Contribution, {
			language: 'aadl2-emv2',
			scopeName: 'source.aadl2.emv2',
			path: './syntaxes/emv2.json',
		});

		const behaviorContribution = manifest.contributes.grammars
			.find(grammar => grammar.language === 'aadl2-behavior');
		assert.deepStrictEqual(behaviorContribution, {
			language: 'aadl2-behavior',
			scopeName: 'source.aadl2.behavior',
			path: './syntaxes/behavior.json',
		});
	});

	test('AADL dispatches named annexes before the generic fallback', () => {
		const annexPatterns = aadl.repository.annex.patterns;
		assert.ok(annexPatterns);
		assert.strictEqual(annexPatterns!.length, 3);

		const [emv2Annex, behaviorAnnex, unknownAnnex] = annexPatterns!;
		assert.ok(javascriptRegex(emv2Annex.begin!).test('ANNEX EMV2{**'));
		assert.strictEqual(emv2Annex.contentName, 'meta.embedded.block.emv2');
		assert.deepStrictEqual(emv2Annex.patterns, [{ include: 'source.aadl2.emv2' }]);

		assert.ok(javascriptRegex(behaviorAnnex.begin!).test('Annex Behavior_Specification {**'));
		assert.strictEqual(behaviorAnnex.contentName, 'meta.embedded.block.behavior');
		assert.deepStrictEqual(behaviorAnnex.patterns, [{ include: 'source.aadl2.behavior' }]);

		assert.ok(javascriptRegex(unknownAnnex.begin!).test('annex custom_annex {**'));
		assert.strictEqual(unknownAnnex.contentName, 'meta.embedded.block.unknown.aadl');
	});

	test('EMV2 covers every keyword emitted by the generated Xtext lexer', () => {
		const keywords = [
			'transformations', 'propagations', 'equivalence', 'propagation', 'transitions',
			'classifier', 'connection', 'detections', 'properties', 'component', 'composite',
			'processor', 'reference', 'subclause', 'behavior', 'bindings', 'constant',
			'mappings', 'applies', 'binding', 'compute', 'extends', 'initial', 'library',
			'noerror', 'package', 'recover', 'renames', 'access', 'events', 'memory',
			'orless', 'ormore', 'others', 'public', 'repair', 'source', 'states', 'annex',
			'delta', 'error', 'event', 'false', 'flows', 'modes', 'paths', 'point', 'state',
			'types', 'mode', 'path', 'same', 'sink', 'true', 'type', 'when', 'with', 'all',
			'and', 'end', 'not', 'out', 'set', 'use', 'if', 'in', 'or', 'to',
		];
		for (const keyword of keywords) {
			const scopes = matchScopes(emv2, keyword);
			assert.ok(
				scopes.some(scope =>
					scope.startsWith('keyword.') || scope.startsWith('constant.language.')),
				`EMV2 keyword ${keyword} only matched ${scopes.join(', ')}`,
			);
		}
		assertScope(emv2, 'TRANSITIONS', 'keyword.other.section.emv2');
		assertScope(emv2, 'ORMORE', 'keyword.operator.logical.emv2');
		assertScope(emv2, 'NOERROR', 'constant.language.emv2');
	});

	test('EMV2 covers lexer literals, comments, and operators', () => {
		assertScope(emv2, '12#ff#E+3', 'constant.numeric.based.emv2');
		assertScope(emv2, '10_000.25e-2', 'constant.numeric.float.emv2');
		assertScope(emv2, '123', 'constant.numeric.integer.emv2');
		assertScope(emv2, ']->', 'keyword.operator.transition.emv2');
		assertScope(emv2, '+=>', 'keyword.operator.assignment.emv2');
		assertScope(emv2, '-- an error model comment', 'comment.line.double-dash.emv2');
	});

	test('Behavior Annex covers every keyword from AadlBa.g', () => {
		const keywords = [
			'abs', 'and', 'any', 'binding', 'classifier', 'complete', 'computation',
			'count', 'dispatch', 'do', 'else', 'elsif', 'end', 'false', 'final', 'for',
			'forall', 'fresh', 'frozen', 'if', 'in', 'initial', 'lower_bound', 'mod',
			'not', 'on', 'or', 'otherwise', 'reference', 'variables', 'rem', 'state',
			'states', 'stop', 'timeout', 'transitions', 'true', 'until', 'upper_bound',
			'while', 'xor',
		];
		for (const keyword of keywords) {
			const scopes = matchScopes(behavior, keyword);
			assert.ok(
				scopes.some(scope =>
					scope.startsWith('keyword.') || scope.startsWith('constant.language.')),
				`Behavior Annex keyword ${keyword} only matched ${scopes.join(', ')}`,
			);
		}
		assertScope(behavior, 'VARIABLES', 'keyword.other.section.aadl-behavior');
		assertScope(behavior, 'WHILE', 'keyword.control.aadl-behavior');
		assertScope(behavior, 'FROZEN', 'constant.language.aadl-behavior');
	});

	test('Behavior Annex covers lexer literals, invalid tokens, and operators', () => {
		assertScope(behavior, '16#ff#e+2', 'constant.numeric.based.aadl-behavior');
		assertScope(behavior, '1_000.5e-2', 'constant.numeric.float.aadl-behavior');
		assertScope(behavior, '123e+4', 'constant.numeric.integer.aadl-behavior');
		assertScope(behavior, ']->', 'keyword.operator.transition.aadl-behavior');
		assertScope(behavior, ':=', 'keyword.operator.assignment.aadl-behavior');
		assertScope(behavior, '!<', 'keyword.operator.comparison.aadl-behavior');
		assertScope(behavior, '==', 'invalid.illegal.aadl-behavior');
		assertScope(behavior, 'ENDIF', 'invalid.illegal.aadl-behavior');
		assertScope(behavior, '-- a behavior comment', 'comment.line.double-dash.aadl-behavior');
	});

	test('VS Code tokenizer scopes prioritized Behavior transition names as declarations', async () => {
		const grammar = await loadAadlGrammar();
		let ruleStack = textmate.INITIAL;
		const tokenized = new Map<string, textmate.IToken[]>();
		for (const line of [
			'annex behavior_specification {**',
			'  transitions',
			'    plain: idle -[]-> done;',
			'    prioritized [1_2]: idle -[]-> done;',
			'    based_priority [16#ff#]: idle -[]-> done;',
			'    complete [1]: idle -[]-> done;',
			'**};',
		]) {
			const result = grammar.tokenizeLine(line, ruleStack);
			ruleStack = result.ruleStack;
			tokenized.set(line, result.tokens);
		}

		const assertTokenScope = (line: string, text: string, expectedScope: string): textmate.IToken => {
			const token = tokenized.get(line)!.find(candidate =>
				line.slice(candidate.startIndex, candidate.endIndex) === text);
			assert.ok(token?.scopes.includes(expectedScope), `${text} was scoped as ${token?.scopes.join(', ')}`);
			return token!;
		};

		assertTokenScope(
			'    plain: idle -[]-> done;',
			'plain',
			'entity.name.declaration.aadl-behavior',
		);
		assertTokenScope(
			'    prioritized [1_2]: idle -[]-> done;',
			'prioritized',
			'entity.name.declaration.aadl-behavior',
		);
		assertTokenScope(
			'    based_priority [16#ff#]: idle -[]-> done;',
			'based_priority',
			'entity.name.declaration.aadl-behavior',
		);
		const reserved = assertTokenScope(
			'    complete [1]: idle -[]-> done;',
			'complete',
			'constant.language.aadl-behavior',
		);
		assert.ok(!reserved.scopes.includes('entity.name.declaration.aadl-behavior'));
	});

	test('VS Code tokenizer applies Behavior scopes and exits at the annex terminator', async () => {
		const grammar = await loadAadlGrammar();
		let ruleStack = textmate.INITIAL;
		const tokenized = new Map<string, textmate.IToken[]>();
		for (const line of [
			'annex behavior_specification {**',
			'  states',
			'    idle: initial state;',
			'**};',
			'end demo;',
		]) {
			const result = grammar.tokenizeLine(line, ruleStack);
			ruleStack = result.ruleStack;
			tokenized.set(line, result.tokens);
		}

		const statesLine = tokenized.get('  states')!;
		const states = statesLine.find(token => '  states'.slice(token.startIndex, token.endIndex) === 'states');
		assert.ok(states?.scopes.includes('keyword.other.section.aadl-behavior'));

		const terminatorLine = tokenized.get('**};')!;
		const terminator = terminatorLine.find(token => '**};'.slice(token.startIndex, token.endIndex) === '**}');
		assert.ok(terminator?.scopes.includes('punctuation.definition.annex.end.aadl'));
		assert.ok(!terminator?.scopes.includes('meta.embedded.block.behavior'));

		const aadlEndLine = tokenized.get('end demo;')!;
		const aadlEnd = aadlEndLine.find(token => 'end demo;'.slice(token.startIndex, token.endIndex) === 'end');
		assert.ok(aadlEnd?.scopes.includes('keyword.control.aadl'));
		assert.ok(!aadlEnd?.scopes.includes('meta.annex.behavior.aadl'));
	});
});
