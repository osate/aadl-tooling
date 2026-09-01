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
export interface ReachabilityArgs {
	generateDot: boolean;
	generateHtml: boolean;
	generateSmv: boolean;
}

export const REACHABILITY_DEFAULTS: ReachabilityArgs = {
	generateDot: true,
	generateHtml: true,
	generateSmv: true,
};

export interface ConfigLike {
	get<T>(section: string, defaultValue: T): T;
}

export function readReachabilityArgs(cfg: ConfigLike): ReachabilityArgs {
	return {
		generateDot: cfg.get('generateDot', REACHABILITY_DEFAULTS.generateDot),
		generateHtml: cfg.get('generateHtml', REACHABILITY_DEFAULTS.generateHtml),
		generateSmv: cfg.get('generateSmv', REACHABILITY_DEFAULTS.generateSmv),
	};
}

export function toCommandArgs(uri: string, args: ReachabilityArgs): unknown[] {
	return [
		uri,
		args.generateDot,
		args.generateHtml,
		args.generateSmv,
	];
}
