# License

This repository is licensed per directory. There is no single license covering
the whole tree — check the directory a file belongs to.

| Directory | License | Files |
| --- | --- | --- |
| [`aadl-language-server/`](aadl-language-server/) | Eclipse Public License 2.0 (`EPL-2.0`) | [`LICENSE`](aadl-language-server/LICENSE), [`COPYRIGHT`](aadl-language-server/COPYRIGHT), [`CONTRIBUTORS`](aadl-language-server/CONTRIBUTORS), [`ACKNOWLEDGEMENTS`](aadl-language-server/ACKNOWLEDGEMENTS) |
| [`vscode-extension/`](vscode-extension/) | BSD (SEI)-style | [`LICENSE.txt`](vscode-extension/LICENSE.txt), [`COPYRIGHT`](vscode-extension/COPYRIGHT) |
| [`osate-cli/`](osate-cli/) | BSD (SEI)-style | [`LICENSE.txt`](osate-cli/LICENSE.txt), [`COPYRIGHT`](osate-cli/COPYRIGHT) |
| `osate2/` | Governed by its own repository | <https://github.com/osate/osate2> |

## Language server — EPL-2.0

`aadl-language-server/` derives from OSATE and stays under the Eclipse Public
License 2.0, available at <https://www.eclipse.org/legal/epl-2.0/>. Copyright
(c) 2004-2026 Carnegie Mellon University and others; see
[`aadl-language-server/CONTRIBUTORS`](aadl-language-server/CONTRIBUTORS).

## VS Code extension and CLI — BSD (SEI)-style

`vscode-extension/` and `osate-cli/` are Copyright 2026 Carnegie Mellon
University and are licensed under a BSD (SEI)-style license. The full terms are
in each directory's `LICENSE.txt`; for other terms contact
<permission@sei.cmu.edu>. Both carry
`[DISTRIBUTION STATEMENT A] This material has been approved for public release
and unlimited distribution.` The SEI document numbers are DM26-0821 (VS Code
extension) and DM26-0838 (CLI).

Source files in these directories carry the corresponding header, and packaging
metadata identifies the license as `LicenseRef-BSD-SEI`.

## OSATE submodule

`osate2/` is a Git submodule pinned to a specific commit of
<https://github.com/osate/osate2>. Nothing in this repository relicenses it; it
remains under its own license and copyright.

## Third-party software

All three modules include and/or make use of third-party software, each subject
to its own license. See the `LICENSE`/`LICENSE.txt` file in the relevant
directory for the enumerated dependencies.
