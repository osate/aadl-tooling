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
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ExecuteCommandCapabilities;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.junit.Assert;
import org.junit.Test;
import org.osate.aadl.ls.commands.AnalysisCommandResult;

import com.google.gson.JsonPrimitive;

public class CommandServiceBusLoadTest extends AbstractAadlLanguageServerTest {

	@Test
	public void busLoadCommandRunsAnalysisAndFormatsDiagnostics() throws Exception {
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
				package busloadtest
				public
					with SEI;

					data D8
						properties
							Data_Size => 8 Bytes;
					end D8;

					data D16
						properties
							Data_Size => 16 Bytes;
					end D16;

					data D24
						properties
							Data_Size => 24 Bytes;
					end D24;

					bus B
						properties
							SEI::BandWidthBudget => 64.0 KBytesps;
							SEI::BandwidthCapacity => 96.0 KBytesps;
					end B;

					system S1
						features
							out1: out data port D8;
							out2: out data port D16;
							out3: out data port D24;
					end S1;

					system S2
						features
							in1: in data port D8;
							in2: in data port D16;
							in3: in data port D24;
					end S2;

					system top
					end top;

					system implementation top.i
						subcomponents
							sub1: system S1;
							sub2: system S2;
							theBus: bus B;
						connections
							conn1: port sub1.out1 -> sub2.in1;
							conn2: port sub1.out2 -> sub2.in2 {
								SEI::BandWidthBudget => 8.0 KBytesps;
							};
							conn3: port sub1.out3 -> sub2.in3 {
								SEI::BandWidthBudget => 32.0 KBytesps;
							};
						properties
							Actual_Connection_Binding => (reference (theBus)) applies to conn1;
							Actual_Connection_Binding => (reference (theBus)) applies to conn2;
							Actual_Connection_Binding => (reference (theBus)) applies to conn3;
							Communication_Properties::Output_Rate => [
								Value_Range => 800.0 .. 1000.0;
								Rate_Unit => PerSecond;
							] applies to sub1.out1;
							Communication_Properties::Output_Rate => [
								Value_Range => 800.0 .. 1000.0;
								Rate_Unit => PerSecond;
							] applies to sub1.out2;
							Communication_Properties::Output_Rate => [
								Value_Range => 800.0 .. 1000.0;
								Rate_Unit => PerSecond;
							] applies to sub1.out3;
					end top.i;
				end busloadtest;
				""";
		String uri = writeFile("busloadtest.aadl", source);
		open(uri, source);

		ExecuteCommandParams instantiateParams = new ExecuteCommandParams();
		instantiateParams.setCommand("aadl.instantiate");
		instantiateParams.setArguments(List.of(new JsonPrimitive(uri), new JsonPrimitive("busloadtest::top.i")));

		CompletableFuture<Object> instantiateFuture = languageServer.getWorkspaceService().executeCommand(
				instantiateParams);
		Object instantiateResult = instantiateFuture.get();
		Assert.assertTrue("expected instantiation success, got: " + instantiateResult,
				instantiateResult instanceof String s && s.startsWith("Instantiated busloadtest::top.i"));

		Path instanceFile = findInstanceFile(Path.of(java.net.URI.create(uri)).getParent());

		ExecuteCommandParams busLoadParams = new ExecuteCommandParams();
		busLoadParams.setCommand("aadl.analyze.busLoad");
		busLoadParams.setArguments(List.of(new JsonPrimitive(instanceFile.toUri().toString())));

		CompletableFuture<Object> busLoadFuture = languageServer.getWorkspaceService().executeCommand(busLoadParams);
		Object busLoadResult = busLoadFuture.get();

		Assert.assertTrue("expected structured result, got: " + busLoadResult,
				busLoadResult instanceof AnalysisCommandResult);
		var result = (AnalysisCommandResult) busLoadResult;
		Assert.assertEquals("error", result.status());
		Assert.assertEquals(1, result.reports().size());
		var report = result.reports().get(0);
		Assert.assertEquals("csv", report.kind());
		Assert.assertTrue(report.uri().endsWith("__BusLoad.csv"));
		Assert.assertTrue("expected csv file on disk: " + report.uri(),
				Files.isRegularFile(Path.of(java.net.URI.create(report.uri()))));
		Assert.assertFalse("expected bus-load diagnostics", result.diagnostics().isEmpty());
		Assert.assertTrue("expected missing-budget warning, got: " + result.diagnostics(),
				result.diagnostics().stream().anyMatch(d -> d.message().contains("has no bandwidth budget")));
		Assert.assertTrue("expected over-budget error, got: " + result.diagnostics(),
				result.diagnostics().stream().anyMatch(d -> d.message().contains("Actual bandwidth > budget")));
	}

	private static Path findInstanceFile(Path root) throws Exception {
		try (var stream = Files.walk(root)) {
			return stream.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".aaxl2"))
					.findFirst()
					.orElseThrow(() -> new AssertionError("expected .aaxl2 file under " + root));
		}
	}

}
