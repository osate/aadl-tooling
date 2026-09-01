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
package org.osate.aadl.ls.tests.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.resource.impl.ProjectDescription;
import org.eclipse.xtext.workspace.FileProjectConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.osate.aadl.ls.scoping.Aadl2LsProjectDescriptionFactory;

public class Aadl2LsProjectDescriptionFactoryTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private ProjectDescription describe(File projectDir) {
		FileProjectConfig config = new FileProjectConfig(URI.createFileURI(projectDir.getAbsolutePath() + "/"),
				projectDir.getName());
		return new Aadl2LsProjectDescriptionFactory().getProjectDescription(config);
	}

	private void writeProjectFile(File projectDir, String xml) throws Exception {
		Files.writeString(new File(projectDir, ".project").toPath(), xml, StandardCharsets.UTF_8);
	}

	@Test
	public void readsMultipleDependenciesFromProjectFile() throws Exception {
		File dir = folder.newFolder("ProjB");
		writeProjectFile(dir, """
				<?xml version="1.0" encoding="UTF-8"?>
				<projectDescription>
					<name>ProjB</name>
					<projects>
						<project>Dep1</project>
						<project>Dep2</project>
					</projects>
				</projectDescription>
				""");

		ProjectDescription desc = describe(dir);

		assertEquals(java.util.List.of("Dep1", "Dep2"), desc.getDependencies());
	}

	@Test
	public void returnsEmptyDependenciesWhenNoProjectsElement() throws Exception {
		File dir = folder.newFolder("ProjA");
		writeProjectFile(dir, """
				<?xml version="1.0" encoding="UTF-8"?>
				<projectDescription>
					<name>ProjA</name>
					<projects>
					</projects>
				</projectDescription>
				""");

		ProjectDescription desc = describe(dir);

		assertTrue(desc.getDependencies().isEmpty());
	}

	@Test
	public void returnsEmptyDependenciesWhenProjectFileMissing() throws Exception {
		File dir = folder.newFolder("NoProjFile");

		ProjectDescription desc = describe(dir);

		assertTrue(desc.getDependencies().isEmpty());
	}

	@Test
	public void ignoresProjectTagsOutsideProjectsParent() throws Exception {
		File dir = folder.newFolder("ProjC");
		writeProjectFile(dir, """
				<?xml version="1.0" encoding="UTF-8"?>
				<projectDescription>
					<name>ProjC</name>
					<projects>
						<project>Real</project>
					</projects>
					<comment>
						<project>NotADependency</project>
					</comment>
				</projectDescription>
				""");

		ProjectDescription desc = describe(dir);

		assertEquals(java.util.List.of("Real"), desc.getDependencies());
	}
}
