/*******************************************************************************
 * VSCode extension for AADL
 *
 * Copyright 2026 Carnegie Mellon University.
 *
 * NO WARRANTY. THIS CARNEGIE MELLON UNIVERSITY AND SOFTWARE ENGINEERING INSTITUTE MATERIAL IS
 * FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON UNIVERSITY MAKES NO WARRANTIES OF ANY KIND,
 * EITHER EXPRESSED OR IMPLIED, AS TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF
 * FITNESS FOR PURPOSE OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE
 * MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT MAKE ANY WARRANTY OF ANY KIND WITH RESPECT TO
 * FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.
 *
 * Licensed under a BSD (SEI)-style license, please see LICENSE.txt
 * or contact permission@sei.cmu.edu for full terms.
 *
 * [DISTRIBUTION STATEMENT A] This material has been approved for public release and unlimited
 * distribution.  Please see Copyright notice for non-US Government use and distribution.
 *
 * This Software includes and/or makes use of Third-Party Software each subject to its own license.
 *
 * DM26-0821
 ******************************************************************************/
import {
	CancellationToken,
	TextDocumentContentProvider,
	Uri
} from 'vscode';

import { LanguageClient } from 'vscode-languageclient/node';

export const CONTRIBUTED_AADL_URI_SCHEME = 'platform';
export const READ_CONTRIBUTED_AADL_REQUEST = 'aadlServer/readContributedAadl';

export class ContributedAadlContentProvider implements TextDocumentContentProvider {
	constructor(private readonly getClient: () => LanguageClient) {}

	provideTextDocumentContent(uri: Uri, token: CancellationToken): Thenable<string> {
		if (uri.scheme !== CONTRIBUTED_AADL_URI_SCHEME || !uri.path.startsWith('/plugin/')) {
			return Promise.reject(new Error(`Unsupported contributed AADL URI: ${uri.toString()}`));
		}

		return this.getClient().sendRequest<string>(
			READ_CONTRIBUTED_AADL_REQUEST,
			{ uri: uri.toString() },
			token
		);
	}
}
