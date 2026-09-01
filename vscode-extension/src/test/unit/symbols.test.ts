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
import { findSymbol, DocumentSymbolLike, RangeLike } from '../../symbols';

interface Pos { line: number; character: number; }

function range(startLine: number, endLine: number): RangeLike<Pos> {
	return {
		contains(pos: Pos): boolean {
			return pos.line >= startLine && pos.line <= endLine;
		},
	};
}

function leaf(name: string, startLine: number, endLine: number): DocumentSymbolLike<Pos> {
	return { name, range: range(startLine, endLine) };
}

function group(children: DocumentSymbolLike<Pos>[]): DocumentSymbolLike<Pos> {
	return { name: 'section', range: range(0, 1000), children };
}

function pkg(sections: DocumentSymbolLike<Pos>[]): DocumentSymbolLike<Pos>[] {
	return [{ name: 'pkg', range: range(0, 1000), children: sections }];
}

suite('symbols.findSymbol', () => {
	test('returns undefined when there are no symbols', () => {
		assert.strictEqual(findSymbol([] as DocumentSymbolLike<Pos>[], { line: 0, character: 0 }), undefined);
	});

	test('returns undefined when the package has no children', () => {
		const symbols: DocumentSymbolLike<Pos>[] = [{ name: 'pkg', range: range(0, 100) }];
		assert.strictEqual(findSymbol(symbols, { line: 1, character: 0 }), undefined);
	});

	test('finds the declaration whose range contains the cursor', () => {
		const symbols = pkg([
			group([leaf('A', 0, 5), leaf('B', 6, 10)]),
		]);
		const result = findSymbol(symbols, { line: 7, character: 0 });
		assert.ok(result);
		assert.strictEqual(result?.name, 'B');
	});

	test('searches across multiple sections (e.g. public + private)', () => {
		const symbols = pkg([
			group([leaf('Pub', 0, 5)]),
			group([leaf('Priv', 6, 10)]),
		]);
		const result = findSymbol(symbols, { line: 8, character: 0 });
		assert.strictEqual(result?.name, 'Priv');
	});

	test('skips sections without children', () => {
		const symbols = pkg([
			{ name: 'empty', range: range(0, 100) },
			group([leaf('A', 0, 5)]),
		]);
		const result = findSymbol(symbols, { line: 2, character: 0 });
		assert.strictEqual(result?.name, 'A');
	});

	test('returns undefined when no declaration contains the cursor', () => {
		const symbols = pkg([
			group([leaf('A', 0, 5)]),
		]);
		assert.strictEqual(findSymbol(symbols, { line: 50, character: 0 }), undefined);
	});
});
