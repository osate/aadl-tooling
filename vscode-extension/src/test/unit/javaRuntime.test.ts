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
	javaExecutableIn,
	minimumJavaMajorVersion,
	parseJavaMajorVersion
} from '../../javaRuntime';

suite('Java runtime selection helpers', () => {
	test('requires Java 21', () => {
		assert.strictEqual(minimumJavaMajorVersion, 21);
	});

	test('parses current OpenJDK version output', () => {
		assert.strictEqual(parseJavaMajorVersion('openjdk version "21.0.11" 2026-04-21 LTS'), 21);
		assert.strictEqual(parseJavaMajorVersion('java version "25.0.1" 2025-10-21 LTS'), 25);
	});

	test('parses legacy Java version output', () => {
		assert.strictEqual(parseJavaMajorVersion('java version "1.8.0_452"'), 8);
	});

	test('rejects unrecognized version output', () => {
		assert.strictEqual(parseJavaMajorVersion('not a Java version'), undefined);
	});

	test('constructs the platform-specific executable inside a Java home', () => {
		assert.strictEqual(javaExecutableIn('/opt/jdk', 'linux'), '/opt/jdk/bin/java');
		assert.strictEqual(javaExecutableIn('C:\\Java\\jdk', 'win32'), 'C:\\Java\\jdk/bin/java.exe');
	});
});
