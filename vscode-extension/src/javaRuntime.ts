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

export const minimumJavaMajorVersion = 21;

export interface JavaRuntime {
	executable: string;
	home: string;
	source: string;
	majorVersion: number;
}

export function javaExecutableIn(javaHome: string, platform: NodeJS.Platform = process.platform): string {
	return path.join(javaHome, 'bin', platform === 'win32' ? 'java.exe' : 'java');
}

export function parseJavaMajorVersion(versionOutput: string): number | undefined {
	const match = /version\s+"([^"]+)"/i.exec(versionOutput);
	if (!match) {
		return undefined;
	}

	const parts = match[1].split(/[._+-]/);
	const majorPart = parts[0] === '1' ? parts[1] : parts[0];
	const major = Number.parseInt(majorPart, 10);
	return Number.isNaN(major) ? undefined : major;
}
