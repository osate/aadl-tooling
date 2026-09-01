/*******************************************************************************
 * Copyright (c) 2004-2026 Carnegie Mellon University and others. (see Contributors file).
 * All Rights Reserved.
 *
 * NO WARRANTY. ALL MATERIAL IS FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON UNIVERSITY MAKES NO WARRANTIES OF ANY
 * KIND, EITHER EXPRESSED OR IMPLIED, AS TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF FITNESS FOR PURPOSE
 * OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT
 * MAKE ANY WARRANTY OF ANY KIND WITH RESPECT TO FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created, in part, with funding and support from the United States Government. (see Acknowledgments file).
 *
 * This program includes and/or can make use of certain third party source code, object code, documentation and other
 * files ("Third Party Software"). The Third Party Software that is used by this program is dependent upon your system
 * configuration. By using this program, You agree to comply with any and all relevant Third Party Software terms and
 * conditions contained in any such Third Party Software or separate license file distributed with such Third Party
 * Software. The parties who own the Third Party Software ("Third Party Licensors") are intended third party beneficiaries
 * to this license with respect to the terms applicable to their Third Party Software. Third Party Software licenses
 * only apply to the Third Party Software and not any other portion of this program or this program as a whole.
 *******************************************************************************/
package org.osate.aadl.ls.commands;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.osate.result.util.ResultUtil;

public class CommandUtilTest {

	@Test
	public void instanceDiagnosticsAreSingleLineAndSorted() {
		var analysisResult = ResultUtil.createAnalysisResult("test", null);
		analysisResult.getDiagnostics().add(ResultUtil.createWarningDiagnostic("first\r\nsecond", null));
		var result = CommandUtil.result(analysisResult, "completed", "file:///instance.aaxl2", List.of());
		Assert.assertEquals("warning", result.status());
		Assert.assertEquals("first  second", result.summary());
		Assert.assertEquals("first  second", result.diagnostics().get(0).message());
	}

	@Test
	public void convertsFileUriWithEscapedCharacters() {
		var expected = Path.of(System.getProperty("java.io.tmpdir"), "AADL reports", "mödel.aaxl2").toAbsolutePath();
		var uri = URI.createURI(expected.toUri().toString());
		Assert.assertEquals(expected, CommandUtil.toPath(uri));
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNonFileUri() {
		CommandUtil.toPath(URI.createURI("platform:/plugin/example/model.aadl"));
	}

	@Test
	public void convertsStandardAndEncodedWindowsDriveUris() {
		Assume.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
		Assert.assertEquals(Path.of("C:\\Users\\test\\model.aaxl2"),
				CommandUtil.toPath(URI.createURI("file:///C:/Users/test/model.aaxl2")));
		Assert.assertEquals(Path.of("c:\\Users\\test\\model.aaxl2"),
				CommandUtil.toPath(URI.createURI("file:///c%3A/Users/test/model.aaxl2")));
	}

	@Test
	public void convertsWindowsUncUri() {
		Assume.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
		Assert.assertEquals(Path.of("\\\\server\\share\\model.aaxl2"),
				CommandUtil.toPath(URI.createURI("file://server/share/model.aaxl2")));
	}
}
