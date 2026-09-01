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
export interface LatencyArgs {
	asynchronousSystem: boolean;
	majorFrameDelay: boolean;
	worstCaseDeadline: boolean;
	bestCaseEmptyQueue: boolean;
	disableQueuingLatency: boolean;
}

export const LATENCY_DEFAULTS: LatencyArgs = {
	asynchronousSystem: true,
	majorFrameDelay: true,
	worstCaseDeadline: true,
	bestCaseEmptyQueue: true,
	disableQueuingLatency: false,
};

export interface ConfigLike {
	get<T>(section: string, defaultValue: T): T;
}

export function readLatencyArgs(cfg: ConfigLike): LatencyArgs {
	return {
		asynchronousSystem: cfg.get('asynchronousSystem', LATENCY_DEFAULTS.asynchronousSystem),
		majorFrameDelay: cfg.get('majorFrameDelay', LATENCY_DEFAULTS.majorFrameDelay),
		worstCaseDeadline: cfg.get('worstCaseDeadline', LATENCY_DEFAULTS.worstCaseDeadline),
		bestCaseEmptyQueue: cfg.get('bestCaseEmptyQueue', LATENCY_DEFAULTS.bestCaseEmptyQueue),
		disableQueuingLatency: cfg.get('disableQueuingLatency', LATENCY_DEFAULTS.disableQueuingLatency),
	};
}

export function toCommandArgs(uri: string, args: LatencyArgs): unknown[] {
	return [
		uri,
		args.asynchronousSystem,
		args.majorFrameDelay,
		args.worstCaseDeadline,
		args.bestCaseEmptyQueue,
		args.disableQueuingLatency,
	];
}
