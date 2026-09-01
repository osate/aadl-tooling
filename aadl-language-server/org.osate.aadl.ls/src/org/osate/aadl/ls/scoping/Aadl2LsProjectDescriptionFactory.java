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
package org.osate.aadl.ls.scoping;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.xtext.ide.server.DefaultProjectDescriptionFactory;
import org.eclipse.xtext.resource.impl.ProjectDescription;
import org.eclipse.xtext.workspace.IProjectConfig;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class Aadl2LsProjectDescriptionFactory extends DefaultProjectDescriptionFactory {

	static final String PROJECT_FILE = ".project";

	@SuppressWarnings("restriction")
	@Override
	public ProjectDescription getProjectDescription(IProjectConfig project) {
		var description = super.getProjectDescription(project);

		var uri = project.getPath();
		if (Objects.nonNull(uri) && uri.isFile()) {
			String path = uri.toFileString();
			description.setDependencies(getProjectDependencies(path + PROJECT_FILE));
		}

		return description;
	}

	private List<String> getProjectDependencies(String filePath) {
		List<String> projectDependencies = new ArrayList<>();
		File xmlFile = new File(filePath);
		if (xmlFile.canRead()) {
			try {
				DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
				DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
				Document doc = dBuilder.parse(xmlFile);

				// Normalize the XML structure
				doc.getDocumentElement().normalize();

				// Inter-project dependencies are listed inside <projects> <project> tags
				NodeList nList = doc.getElementsByTagName("project");

				for (int i = 0; i < nList.getLength(); i++) {
					Node nNode = nList.item(i);
					// Ensure we only get <project> tags that are children of <projects>
					if (nNode.getParentNode().getNodeName().equals("projects")) {
						projectDependencies.add(nNode.getTextContent().trim());
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return projectDependencies;
	}
}
