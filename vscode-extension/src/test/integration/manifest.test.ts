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
import * as fs from 'fs';

interface PackageJson {
	contributes: {
		commands: Array<{ command: string; title: string }>;
		languages: Array<{ id: string; aliases?: string[]; extensions?: string[] }>;
		grammars: Array<{
			language: string;
			scopeName: string;
			path: string;
			embeddedLanguages?: Record<string, string>;
		}>;
		menus?: Record<string, Array<{ command: string; when?: string }>>;
		configuration: {
			properties: Record<string, {
				type: string;
				default: unknown;
				scope?: string;
				description?: string;
				enum?: string[];
			}>;
		};
	};
}

function loadPackageJson(): PackageJson {
	const root = path.resolve(__dirname, '..', '..', '..');
	return JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
}

suite('package.json manifest', () => {
	const pkg = loadPackageJson();

	test('contributes the five custom commands', () => {
		const ids = pkg.contributes.commands.map(c => c.command).sort();
		assert.deepStrictEqual(ids, [
			'aadl2.analyze.busLoad',
			'aadl2.analyze.latency',
			'aadl2.analyze.reachability',
			'aadl2.instantiate',
			'aadl2.restart',
		]);
	});

	test('registers EMV2 and Behavior Annex as embedded grammars', () => {
		const languageIds = pkg.contributes.languages.map(language => language.id);
		assert.ok(languageIds.includes('aadl2-emv2'));
		assert.ok(languageIds.includes('aadl2-behavior'));

		const aadlGrammar = pkg.contributes.grammars.find(grammar => grammar.language === 'aadl2');
		assert.ok(aadlGrammar, 'AADL grammar contribution missing');
		assert.deepStrictEqual(aadlGrammar!.embeddedLanguages, {
			'meta.embedded.block.emv2': 'aadl2-emv2',
			'meta.embedded.block.behavior': 'aadl2-behavior',
		});

		const annexGrammars = pkg.contributes.grammars
			.filter(grammar => grammar.language !== 'aadl2')
			.map(grammar => [grammar.language, grammar.scopeName, grammar.path]);
		assert.deepStrictEqual(annexGrammars, [
			['aadl2-emv2', 'source.aadl2.emv2', './syntaxes/emv2.json'],
			['aadl2-behavior', 'source.aadl2.behavior', './syntaxes/behavior.json'],
		]);
	});

	test('instance analyses are wired to the explorer context menu for .aaxl2', () => {
		const items = pkg.contributes.menus?.['explorer/context'] ?? [];
		const latency = items.find(i => i.command === 'aadl2.analyze.latency');
		assert.ok(latency, 'aadl2.analyze.latency menu entry missing');
		assert.strictEqual(latency!.when, 'resourceExtname == .aaxl2');
		const busLoad = items.find(i => i.command === 'aadl2.analyze.busLoad');
		assert.ok(busLoad, 'aadl2.analyze.busLoad menu entry missing');
		assert.strictEqual(busLoad!.when, 'resourceExtname == .aaxl2');
		const reachability = items.find(i => i.command === 'aadl2.analyze.reachability');
		assert.ok(reachability, 'aadl2.analyze.reachability menu entry missing');
		assert.strictEqual(reachability!.when, 'resourceExtname == .aaxl2');
	});

	suite('latency configuration schema', () => {
		const props = pkg.contributes.configuration.properties;
		const expected: Array<[string, boolean]> = [
			['aadl2Server.latency.asynchronousSystem', true],
			['aadl2Server.latency.majorFrameDelay', true],
			['aadl2Server.latency.worstCaseDeadline', true],
			['aadl2Server.latency.bestCaseEmptyQueue', true],
			['aadl2Server.latency.disableQueuingLatency', false],
		];

		for (const [key, def] of expected) {
			test(`${key} is a resource-scoped boolean with default ${def}`, () => {
				const entry = props[key];
				assert.ok(entry, `missing setting ${key}`);
				assert.strictEqual(entry.type, 'boolean');
				assert.strictEqual(entry.scope, 'resource');
				assert.strictEqual(entry.default, def);
				assert.ok(entry.description && entry.description.length > 0,
					`${key} should have a description`);
			});
		}
	});

	suite('reachability configuration schema', () => {
		const props = pkg.contributes.configuration.properties;
		const expected: Array<[string, boolean]> = [
			['aadl2Server.reachability.generateDot', true],
			['aadl2Server.reachability.generateHtml', true],
			['aadl2Server.reachability.generateSmv', true],
		];

		for (const [key, def] of expected) {
			test(`${key} is a resource-scoped boolean with default ${def}`, () => {
				const entry = props[key];
				assert.ok(entry, `missing setting ${key}`);
				assert.strictEqual(entry.type, 'boolean');
				assert.strictEqual(entry.scope, 'resource');
				assert.strictEqual(entry.default, def);
				assert.ok(entry.description && entry.description.length > 0,
					`${key} should have a description`);
			});
		}
	});
});
