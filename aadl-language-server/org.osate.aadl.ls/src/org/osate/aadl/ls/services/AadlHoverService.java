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

import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.documentation.IEObjectDocumentationProvider;
import org.eclipse.xtext.ide.labels.INameLabelProvider;
import org.eclipse.xtext.ide.server.hover.HoverService;

import com.google.inject.Inject;

public class AadlHoverService extends HoverService {
	@Inject
	private IEObjectDocumentationProvider eObjectDocumentationProvider;

	@SuppressWarnings("restriction")
	@Inject
	private INameLabelProvider nameLabelProvider;

	@Override
	public String getContents(EObject element) {
		String documentation = eObjectDocumentationProvider.getDocumentation(element);
		if (documentation == null) {
			return getFirstLine(element);
		} else {
			return getFirstLine(element) + "  \n" + htmlToMarkdown(documentation);
		}
	}

	private String getFirstLine(EObject o) {
		@SuppressWarnings("restriction")
		String label = nameLabelProvider.getNameLabel(o);
		return o.eClass().getName() + (label != null ? " **" + label + "**" : "");
	}

	private String htmlToMarkdown(String html) {
		if (html == null) {
			return "";
		}

		String markdown = html;

		// Remove existing newlines
		markdown = markdown.replaceAll("\\n", "");

		// Bold: <b> or <strong> -> **text**
		markdown = markdown.replaceAll("(?i)<(b|strong)>(.*?)</\\1>", "**$2**");

		// Italics: <i> or <em> -> *text*
		markdown = markdown.replaceAll("(?i)<(i|em)>(.*?)</\\1>", "*$2*");

		// Headings: <h1> -> # text, <h2> -> ## text
		markdown = markdown.replaceAll("(?i)<h1>(.*?)</h1>", "# $1\n\n");
		markdown = markdown.replaceAll("(?i)<h2>(.*?)</h2>", "## $1\n\n");

		// Paragraphs & Line Breaks
		markdown = markdown.replaceAll("(?i)<p>(.*?)</p>", "\n\n$1\n\n");
		markdown = markdown.replaceAll("(?i)<(br|p)\\s*/?>", "\n\n");

		// 6. Strip all remaining HTML tags
		markdown = markdown.replaceAll("<[^>]+>", "");

		return markdown.trim();
	}
}
