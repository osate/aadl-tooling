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
package org.osate.aadl.ls.setup;

import org.eclipse.xtext.ISetup;
import org.eclipse.xtext.resource.FileExtensionProvider;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.ResourceServiceProviderServiceLoader;

import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Singleton;

/**
 * Provides the {@link IResourceServiceProvider.Registry} for the language server, forcing the
 * language-server flavored setups to win their file-extension slots.
 *
 * <p>Why this exists: Xtext's stock {@link ResourceServiceProviderServiceLoader} builds the registry
 * by iterating <em>every</em> {@link ISetup} on the {@code META-INF/services/org.eclipse.xtext.ISetup}
 * SPI and, for a given file extension, the last setup processed wins. The bundled OSATE plugins
 * register two setups that both claim the {@code aadl} extension:
 * <ul>
	 *   <li>{@code Aadl2LsSetup} (this bundle) — mixes in {@link Aadl2LsIdeModule}, which binds the
	 *       {@code AadlLanguageServerExtension} {@code @JsonRequest} endpoints and the standalone
 *       {@code Aadl2LsGlobalScopeProvider}; and</li>
 *   <li>{@code org.osate.xtext.aadl2.ide.Aadl2IdeSetup} (from the {@code .ide} bundle) — the stock
 *       OSATE IDE wiring, which binds neither.</li>
 * </ul>
 * SPI iteration order is effectively the (nondeterministic) jar order, so when {@code Aadl2IdeSetup}
 * wins, {@link org.eclipse.xtext.ide.server.LanguageServerImpl#supportedMethods()} cannot find the
 * {@code ILanguageServerExtension} on the {@code aadl} provider and {@code aadlServer/waitUntilFinished}
 * fails with "unknown json request" — and name resolution loses the custom global scope provider too.
 *
 * <p>This provider takes the stock registry and then re-registers the LS setups last, so their
 * customized injectors deterministically own every extension they declare.
 */
@Singleton
public class Aadl2LsResourceServiceProviderRegistry implements Provider<IResourceServiceProvider.Registry> {

	private final IResourceServiceProvider.Registry registry;

	public Aadl2LsResourceServiceProviderRegistry() {
		registry = new ResourceServiceProviderServiceLoader().get();
		// These overwrite whatever stock SPI entry claimed the same extensions (e.g. Aadl2IdeSetup
		// for "aadl"), so the language-server injectors win.
		register(new Aadl2LsSetup());
		register(new ErrorModelLsSetup());
	}

	private void register(ISetup setup) {
		Injector injector = setup.createInjectorAndDoEMFRegistration();
		IResourceServiceProvider provider = injector.getInstance(IResourceServiceProvider.class);
		FileExtensionProvider extensions = injector.getInstance(FileExtensionProvider.class);
		for (String extension : extensions.getFileExtensions()) {
			registry.getExtensionToFactoryMap().put(extension, provider);
		}
	}

	@Override
	public IResourceServiceProvider.Registry get() {
		return registry;
	}
}
