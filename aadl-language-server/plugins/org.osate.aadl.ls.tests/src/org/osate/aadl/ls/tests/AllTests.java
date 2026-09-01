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
package org.osate.aadl.ls.tests;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.osate.aadl.ls.commands.CommandUtilTest;
import org.osate.aadl.ls.tests.lsp.BehaviorAnnexParsingTest;
import org.osate.aadl.ls.tests.lsp.CommandServiceBusLoadTest;
import org.osate.aadl.ls.tests.lsp.CommandServiceInstantiateTest;
import org.osate.aadl.ls.tests.lsp.CommandServiceLatencyTest;
import org.osate.aadl.ls.tests.lsp.CommandServiceReachabilityTest;
import org.osate.aadl.ls.tests.lsp.DiagnosticsSmokeTest;
import org.osate.aadl.ls.tests.lsp.DocumentSymbolLspTest;
import org.osate.aadl.ls.tests.lsp.Emv2ParsingTest;
import org.osate.aadl.ls.tests.lsp.MultiRootLinkingTest;
import org.osate.aadl.ls.tests.lsp.PredeclaredPropertyDefinitionLspTest;
import org.osate.aadl.ls.tests.unit.Aadl2LsGlobalScopeProviderTest;
import org.osate.aadl.ls.tests.unit.Aadl2LsProjectDescriptionFactoryTest;
import org.osate.aadl.ls.tests.unit.AadlSymbolNameProviderTest;
import org.osate.aadl.ls.tests.unit.AadlUriExtensionsTest;
import org.osate.aadl.ls.tests.unit.CommandServiceInitializeTest;
import org.osate.aadl.ls.tests.unit.ContributedAadlContentServiceTest;

@RunWith(Suite.class)
@Suite.SuiteClasses({
		CommandUtilTest.class,
		CommandServiceInitializeTest.class,
		Aadl2LsProjectDescriptionFactoryTest.class,
		AadlSymbolNameProviderTest.class,
		Aadl2LsGlobalScopeProviderTest.class,
		ContributedAadlContentServiceTest.class,
		AadlUriExtensionsTest.class,
		DiagnosticsSmokeTest.class,
		BehaviorAnnexParsingTest.class,
		DocumentSymbolLspTest.class,
		PredeclaredPropertyDefinitionLspTest.class,
		CommandServiceBusLoadTest.class,
		CommandServiceLatencyTest.class,
		CommandServiceReachabilityTest.class,
		CommandServiceInstantiateTest.class,
		MultiRootLinkingTest.class,
		Emv2ParsingTest.class
})
public class AllTests {
}
