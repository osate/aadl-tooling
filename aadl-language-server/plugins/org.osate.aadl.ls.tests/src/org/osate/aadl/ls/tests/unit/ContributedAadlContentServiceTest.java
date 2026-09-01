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

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.plugin.EcorePlugin;
import org.junit.BeforeClass;
import org.junit.Test;
import org.osate.aadl.ls.services.ContributedAadlContentService;
import org.osate.pluginsupport.PluginSupportUtil;

public class ContributedAadlContentServiceTest {

	private final ContributedAadlContentService service = new ContributedAadlContentService();

	@BeforeClass
	public static void discoverPluginContributions() {
		EcorePlugin.ExtensionProcessor.process(null);
	}

	@Test
	public void readsRegisteredPropertySet() throws IOException {
		URI uri = timingPropertiesUri();

		String contents = service.read(uri.toString());

		assertTrue(contents.contains("property set Timing_Properties is"));
		assertTrue(contents.contains("Period: inherit Time"));
	}

	@Test
	public void rejectsFileUri() {
		assertThrows(IllegalArgumentException.class, () -> service.read("file:/tmp/Timing_Properties.aadl"));
	}

	@Test
	public void rejectsUnregisteredPluginResource() {
		assertThrows(IllegalArgumentException.class,
				() -> service.read("platform:/plugin/org.osate.aadl2.contrib/META-INF/MANIFEST.MF"));
	}

	@Test
	public void rejectsQueryAndFragment() {
		String uri = timingPropertiesUri().toString();

		assertThrows(IllegalArgumentException.class, () -> service.read(uri + "?raw=true"));
		assertThrows(IllegalArgumentException.class, () -> service.read(uri + "#Period"));
	}

	@Test
	public void rejectsMissingAndMalformedUri() {
		assertThrows(IllegalArgumentException.class, () -> service.read(null));
		assertThrows(IllegalArgumentException.class, () -> service.read(" "));
		assertThrows(IllegalArgumentException.class, () -> service.read("platform:/plugin/%"));
	}

	private static URI timingPropertiesUri() {
		return PluginSupportUtil.getContributedAadl()
				.stream()
				.filter(uri -> "Timing_Properties.aadl".equals(uri.lastSegment()))
				.findFirst()
				.orElseThrow();
	}
}
