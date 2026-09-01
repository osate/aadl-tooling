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

import org.eclipse.xtext.testing.InjectWith;
import org.eclipse.xtext.testing.XtextRunner;
import org.eclipse.xtext.testing.util.ParseHelper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osate.aadl.ls.services.AadlSymbolNameProvider;
import org.osate.aadl.ls.tests.AadlLsInjectorProvider;
import org.osate.aadl2.AadlPackage;
import org.osate.aadl2.Classifier;
import org.osate.aadl2.PackageSection;

import com.google.inject.Inject;

@RunWith(XtextRunner.class)
@InjectWith(AadlLsInjectorProvider.class)
public class AadlSymbolNameProviderTest {

	@Inject
	private ParseHelper<AadlPackage> parseHelper;

	@Inject
	private AadlSymbolNameProvider nameProvider;

	@Test
	public void packageShowsFullyQualifiedName() throws Exception {
		AadlPackage pkg = parseHelper.parse("""
				package Sample
				public
					system sys
					end sys;
				end Sample;
				""");
		assertEquals("Sample", nameProvider.getName(pkg));
	}

	@Test
	public void publicSectionShowsLiteralPublic() throws Exception {
		AadlPackage pkg = parseHelper.parse("""
				package Sample
				public
					system sys
					end sys;
				end Sample;
				""");
		PackageSection pub = pkg.getOwnedPublicSection();
		assertEquals("public", nameProvider.getName(pub));
	}

	@Test
	public void privateSectionShowsLiteralPrivate() throws Exception {
		AadlPackage pkg = parseHelper.parse("""
				package Sample
				private
					system sys
					end sys;
				end Sample;
				""");
		PackageSection priv = pkg.getOwnedPrivateSection();
		assertEquals("private", nameProvider.getName(priv));
	}

	@Test
	public void classifierShowsSimpleName() throws Exception {
		AadlPackage pkg = parseHelper.parse("""
				package Sample
				public
					system sys
					end sys;
				end Sample;
				""");
		Classifier sys = pkg.getOwnedPublicSection().getOwnedClassifiers().get(0);
		assertEquals("sys", nameProvider.getName(sys));
	}
}
