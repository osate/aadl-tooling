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
import { ConfigLike, LATENCY_DEFAULTS, readLatencyArgs, toCommandArgs } from '../../latency';

function fakeCfg(overrides: Record<string, unknown>): ConfigLike {
	return {
		get<T>(section: string, defaultValue: T): T {
			return section in overrides ? (overrides[section] as T) : defaultValue;
		},
	};
}

suite('latency.readLatencyArgs', () => {
	test('returns defaults when nothing is configured', () => {
		const args = readLatencyArgs(fakeCfg({}));
		assert.deepStrictEqual(args, LATENCY_DEFAULTS);
	});

	test('defaults match the server-side defaults in CommandService.optBool', () => {
		// Server: asynchronousSystem=true, majorFrameDelay=true, worstCaseDeadline=true,
		// bestCaseEmptyQueue=true, disableQueuingLatency=false.
		assert.deepStrictEqual(LATENCY_DEFAULTS, {
			asynchronousSystem: true,
			majorFrameDelay: true,
			worstCaseDeadline: true,
			bestCaseEmptyQueue: true,
			disableQueuingLatency: false,
		});
	});

	test('reads each flag independently', () => {
		const args = readLatencyArgs(fakeCfg({
			asynchronousSystem: false,
			majorFrameDelay: false,
			worstCaseDeadline: false,
			bestCaseEmptyQueue: false,
			disableQueuingLatency: true,
		}));
		assert.deepStrictEqual(args, {
			asynchronousSystem: false,
			majorFrameDelay: false,
			worstCaseDeadline: false,
			bestCaseEmptyQueue: false,
			disableQueuingLatency: true,
		});
	});

	test('partial overrides keep defaults for the rest', () => {
		const args = readLatencyArgs(fakeCfg({ disableQueuingLatency: true }));
		assert.strictEqual(args.disableQueuingLatency, true);
		assert.strictEqual(args.asynchronousSystem, true);
		assert.strictEqual(args.majorFrameDelay, true);
		assert.strictEqual(args.worstCaseDeadline, true);
		assert.strictEqual(args.bestCaseEmptyQueue, true);
	});
});

suite('latency.toCommandArgs', () => {
	test('emits uri followed by the five flags in server-expected order', () => {
		// Order must match CommandService.execute(): args[0]=uri, [1]=asynchronousSystem,
		// [2]=majorFrameDelay, [3]=worstCaseDeadline, [4]=bestCaseEmptyQueue, [5]=disableQueuingLatency.
		const out = toCommandArgs('file:///x.aaxl2', {
			asynchronousSystem: true,
			majorFrameDelay: false,
			worstCaseDeadline: true,
			bestCaseEmptyQueue: false,
			disableQueuingLatency: true,
		});
		assert.deepStrictEqual(out, [
			'file:///x.aaxl2',
			true,
			false,
			true,
			false,
			true,
		]);
	});
});
