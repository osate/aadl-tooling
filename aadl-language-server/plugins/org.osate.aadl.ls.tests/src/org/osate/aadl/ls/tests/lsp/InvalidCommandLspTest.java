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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.ExecuteCommandCapabilities;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.junit.Assert;
import org.junit.Test;

import com.google.gson.JsonPrimitive;

/**
 * Exercises invalid commands through the LSP workspace service so clients receive
 * useful protocol errors and can continue using the server after a rejected request.
 */
public class InvalidCommandLspTest extends AbstractAadlLanguageServerTest {

	@Test
	public void unknownCommandReturnsErrorAndServerRemainsUsable() throws Exception {
		initialize(params -> params.getCapabilities().getWorkspace()
				.setExecuteCommand(new ExecuteCommandCapabilities()));
		assertUnknownCommandAndRecovery();
	}

	@Test
	public void unknownCommandWithoutClientCapabilityReturnsError() throws Exception {
		initialize();
		assertUnknownCommandAndRecovery();
	}

	@Test
	public void unknownCommandWithoutWorkspaceCapabilityReturnsError() throws Exception {
		initialize(params -> {
			params.setRootUri(root.toURI().toString());
			params.getCapabilities().setWorkspace(null);
		});
		assertUnknownCommandAndRecovery();
	}

	@Test
	public void missingCommandReturnsError() throws Exception {
		initialize();
		assertInvalidParams(new ExecuteCommandParams(), "A command name is required");
	}

	@Test
	public void blankCommandReturnsError() throws Exception {
		initialize();
		for (String command : List.of("", " \t")) {
			assertInvalidParams(new ExecuteCommandParams(command, List.of()), "A command name is required");
		}
	}

	@Test
	public void registeredCommandRetainsItsArgumentError() throws Exception {
		initialize(params -> params.getCapabilities().getWorkspace()
				.setExecuteCommand(new ExecuteCommandCapabilities()));
		assertInvalidParams(new ExecuteCommandParams("aadl.analyze.latency", List.of()), "A file URI is required");
	}

	private void assertUnknownCommandAndRecovery() throws Exception {
		assertInvalidParams(new ExecuteCommandParams("aadl.invalid", List.of()), "Unknown command: aadl.invalid");

		String source = Files.readString(Path.of("test-models", "instantiate", "sys.aadl"));
		String uri = writeFile("sys.aadl", source);
		open(uri, source);
		var command = new ExecuteCommandParams("aadl.instantiate",
				List.of(new JsonPrimitive(uri), new JsonPrimitive("sys.impl")));
		Object result = languageServer.getWorkspaceService().executeCommand(command).get(30, TimeUnit.SECONDS);
		Assert.assertTrue("expected a successful command after rejection, got: " + result,
				result instanceof String message && message.startsWith("Instantiated sys.impl"));
	}

	private void assertInvalidParams(ExecuteCommandParams params, String message) {
		var failure = Assert.assertThrows(ExecutionException.class,
				() -> languageServer.getWorkspaceService().executeCommand(params).get(30, TimeUnit.SECONDS));
		Assert.assertTrue("expected an LSP error, got: " + failure.getCause(),
				failure.getCause() instanceof ResponseErrorException);
		var error = ((ResponseErrorException) failure.getCause()).getResponseError();
		Assert.assertEquals(ResponseErrorCode.InvalidParams.getValue(), error.getCode());
		Assert.assertEquals(message, error.getMessage());
	}
}
