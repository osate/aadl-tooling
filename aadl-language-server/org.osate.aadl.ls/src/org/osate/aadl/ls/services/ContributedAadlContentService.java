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
package org.osate.aadl.ls.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.URIConverter;
import org.osate.pluginsupport.PluginSupportUtil;

public class ContributedAadlContentService {

	public String read(String uriText) throws IOException {
		if (uriText == null || uriText.isBlank()) {
			throw new IllegalArgumentException("A contributed AADL URI is required");
		}

		final URI uri;
		try {
			uri = URI.createURI(uriText);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("The contributed AADL URI is malformed", exception);
		}

		if (!uri.isPlatformPlugin() || uri.isRelative() || uri.hasQuery() || uri.hasFragment()
				|| !PluginSupportUtil.getContributedAadl().contains(uri)) {
			throw new IllegalArgumentException("The URI does not identify a registered contributed AADL resource");
		}

		try (var input = URIConverter.INSTANCE.createInputStream(uri)) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
