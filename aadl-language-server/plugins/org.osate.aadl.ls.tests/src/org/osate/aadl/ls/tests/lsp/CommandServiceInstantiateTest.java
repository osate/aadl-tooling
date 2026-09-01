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
package org.osate.aadl.ls.tests.lsp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ExecuteCommandCapabilities;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.junit.Assert;
import org.junit.Test;

import com.google.gson.JsonPrimitive;

public class CommandServiceInstantiateTest extends AbstractAadlLanguageServerTest {

	@Test
	public void instantiateCommandIsRegisteredAndDispatches() throws Exception {
		initialize(params -> {
			ClientCapabilities caps = params.getCapabilities();
			if (caps == null) {
				caps = new ClientCapabilities();
				params.setCapabilities(caps);
			}
			WorkspaceClientCapabilities workspace = caps.getWorkspace();
			if (workspace == null) {
				workspace = new WorkspaceClientCapabilities();
				caps.setWorkspace(workspace);
			}
			workspace.setExecuteCommand(new ExecuteCommandCapabilities());
		});
		String source = """
				package sys
				public
					system sys
					end sys;

					system implementation sys.impl
					end sys.impl;
				end sys;
				""";
		String uri = writeFile("sys.aadl", source);
		open(uri, source);

		ExecuteCommandParams params = new ExecuteCommandParams();
		params.setCommand("aadl.instantiate");
		params.setArguments(List.of(new JsonPrimitive(uri), new JsonPrimitive("sys.impl")));

		CompletableFuture<Object> future = languageServer.getWorkspaceService().executeCommand(params);
		Object result = future.get();

		// Whether a sibling .aaxl is written depends on OSATE's instance-file I/O under
		// the non-UI harness; a stable signal here is that the command is dispatched end-to-end
		// through CommandService.execute() and returns its success message.
		Assert.assertTrue("expected success message, got: " + result,
				result instanceof String s && s.startsWith("Instantiated sys.impl"));
	}
}
