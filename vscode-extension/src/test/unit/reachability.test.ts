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
import {
	ConfigLike,
	REACHABILITY_DEFAULTS,
	readReachabilityArgs,
	toCommandArgs
} from '../../reachability';

function fakeCfg(overrides: Record<string, unknown>): ConfigLike {
	return {
		get<T>(section: string, defaultValue: T): T {
			return section in overrides ? (overrides[section] as T) : defaultValue;
		},
	};
}

suite('reachability.readReachabilityArgs', () => {
	test('returns defaults when nothing is configured', () => {
		const args = readReachabilityArgs(fakeCfg({}));
		assert.deepStrictEqual(args, REACHABILITY_DEFAULTS);
	});

	test('defaults enable all VS Code report formats', () => {
		assert.deepStrictEqual(REACHABILITY_DEFAULTS, {
			generateDot: true,
			generateHtml: true,
			generateSmv: true,
		});
	});

	test('reads each flag independently', () => {
		const args = readReachabilityArgs(fakeCfg({
			generateDot: false,
			generateHtml: false,
			generateSmv: false,
		}));
		assert.deepStrictEqual(args, {
			generateDot: false,
			generateHtml: false,
			generateSmv: false,
		});
	});

	test('partial overrides keep defaults for the rest', () => {
		const args = readReachabilityArgs(fakeCfg({ generateHtml: false }));
		assert.strictEqual(args.generateDot, true);
		assert.strictEqual(args.generateHtml, false);
		assert.strictEqual(args.generateSmv, true);
	});
});

suite('reachability.toCommandArgs', () => {
	test('emits uri followed by the three report flags in server-expected order', () => {
		const out = toCommandArgs('file:///x.aaxl2', {
			generateDot: true,
			generateHtml: false,
			generateSmv: true,
		});
		assert.deepStrictEqual(out, [
			'file:///x.aaxl2',
			true,
			false,
			true,
		]);
	});
});
