<!--
    VSCode extension for AADL

    Copyright 2026 Carnegie Mellon University.

    NO WARRANTY. THIS CARNEGIE MELLON UNIVERSITY AND SOFTWARE ENGINEERING INSTITUTE MATERIAL IS
    FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON UNIVERSITY MAKES NO WARRANTIES OF ANY KIND,
    EITHER EXPRESSED OR IMPLIED, AS TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF
    FITNESS FOR PURPOSE OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE
    MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT MAKE ANY WARRANTY OF ANY KIND WITH RESPECT TO
    FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.

    Licensed under a BSD (SEI)-style license, please see LICENSE.txt
    or contact permission@sei.cmu.edu for full terms.

    [DISTRIBUTION STATEMENT A] This material has been approved for public release and unlimited
    distribution.  Please see Copyright notice for non-US Government use and distribution.

    This Software includes and/or makes use of Third-Party Software each subject to its own license.

    DM26-0821
 -->

# AADL2 Extension for Visual Studio Code

Edit and validate AADL models, instantiate component implementations, and run
latency, bus load, and mode reachability analyses without leaving Visual Studio
Code.

## Installation

In Visual Studio Code, open the Extensions view, search for `AADL2` from the
`osate` publisher, and select **Install**.

Installing from the Marketplace gets the package built for your platform, which
is what you want: each one contains its own Java runtime.

To install a downloaded release instead, open the Extensions view menu, select
**Install from VSIX...**, and choose the `aadl2-<platform>-<version>.vsix` file
matching your operating system and processor, such as
`aadl2-darwin-arm64-<version>.vsix`. A package for another platform installs but
cannot start the language server.

Nothing else has to be installed. The extension carries the Java runtime it
needs, and no longer requires the Red Hat Java extension.

## Getting Started

1. Open a folder or workspace containing `.aadl` files.
2. Open an AADL file. The language server starts automatically and reports
   syntax and validation problems in the editor and Problems view.
3. Place the cursor inside a component implementation and run
   **AADL2: Instantiate** from the Command Palette. The generated `.aaxl2`
   instance model is written under the owning workspace root's `instances/`
   directory.
4. Right-click the generated `.aaxl2` file in the Explorer and select a latency,
   bus load, or mode reachability analysis.

Analysis summaries appear as notifications. Report paths and detailed
diagnostics are written to the **AADL2 Language Server** output channel.

## Features

- Language-aware editing of AADL source files (syntax and validation errors
  marked while typing)
- Code completion
- Jump to definition for AADL classifiers, features, and properties. Definitions
  from pre-declared and plugin-contributed AADL resources open as read-only
  virtual documents without being copied into the workspace
- Breadcrumbs and outline view
- AadlDoc hover information
- Access to pre-declared AADL property sets and other property sets and
  packages contributed by OSATE plugins
- Error model annex support
- Component instantiation
- Latency analysis on instance models
- Bus load analysis on instance models
- Mode reachability analysis on instance models

## Requirements

The extension requires:

- Visual Studio Code 1.110 or newer
- One of the supported platforms: macOS (Intel or Apple silicon), Linux (x64 or
  arm64), or Windows (x64 or arm64)

No Java installation is required. Every package bundles an Eclipse Temurin 21
JRE, and the extension runs the language server with that runtime only — it never
uses `JAVA_HOME`, a Java installation on the `PATH`, or another extension's
runtime, so the server behaves the same on every machine.

Platforms outside that list, including Alpine Linux and 32-bit ARM, have no
package and cannot install the extension.

## Extension Settings

| Setting | Default | Description |
| --- | --- | --- |
| `aadl2Server.maxNumberOfProblems` | `100` | Maximum number of problems reported per file. |
| `aadl2Server.trace.server` | `off` | LSP traffic tracing: `off`, `messages`, or `verbose`. |
| `aadl2Server.latency.asynchronousSystem` | `true` | Assume an asynchronous system; disable for a synchronous system. |
| `aadl2Server.latency.majorFrameDelay` | `true` | Use major-frame delay for partition output; disable to use partition-end delay. |
| `aadl2Server.latency.worstCaseDeadline` | `true` | Use worst-case processing time based on deadline; disable for best-case compute execution time. |
| `aadl2Server.latency.bestCaseEmptyQueue` | `true` | Assume an empty queue for best-case latency; disable to assume a full queue. |
| `aadl2Server.latency.disableQueuingLatency` | `false` | Exclude queuing latency from latency analysis. |
| `aadl2Server.reachability.generateDot` | `true` | Generate a DOT mode reachability report. |
| `aadl2Server.reachability.generateHtml` | `true` | Generate an HTML mode reachability report. |
| `aadl2Server.reachability.generateSmv` | `true` | Generate an SMV mode reachability report. |

## Commands

- `AADL2: Instantiate` — with the cursor in a component implementation,
  creates an instance model in the `instances/` directory
- `AADL2: Run latency analysis on instance model` — right-click a `.aaxl2`
  file in the explorer to run latency analysis
- `AADL2: Run bus load analysis on instance model` — right-click a `.aaxl2`
  file in the explorer to run bus load analysis
- `AADL2: Run mode reachability analysis on instance model` — right-click a
  `.aaxl2` file in the explorer to run mode reachability analysis
- `AADL2: Restart Language Server` — stops and restarts the language client
  without reloading VSCode

The analysis commands are available from the Explorer context menu for
`.aaxl2` files. Use the Command Palette for instantiation and language-server
restart.

## Generated Files

- Instantiation writes `.aaxl2` instance models under the owning workspace
  root's `instances/` directory.
- Latency analysis writes reports under
  `<instance-folder>/reports/latency/`.
- Bus load analysis writes reports under
  `<instance-folder>/reports/BusLoad/`.
- Mode reachability analysis writes the selected DOT, HTML, and SMV reports
  under `<instance-folder>/reports/som-reachability/`.

## Troubleshooting

### The language server does not start

Open **View: Toggle Output**, select **AADL2 Language Server**, and inspect the
startup message. The channel identifies the Java executable and version used to
launch the server, which is always the runtime inside the extension.

If the error says the bundled runtime could not be run, the installed package was
almost certainly built for a different platform — most often because it was
installed from a downloaded VSIX. Reinstall from the Marketplace, which selects
the right package automatically.

### Editing results appear stale

Run **AADL2: Restart Language Server** from the Command Palette. This restarts
the language client and bundled server without reloading Visual Studio Code.

### More protocol detail is needed

Set `aadl2Server.trace.server` to `messages` or `verbose`, reproduce the
problem, and inspect the **AADL2 Language Server** output channel. Protocol
traces can contain model text, paths, and other workspace information; review
them before sharing.

Report reproducible problems through the repository's
[issue tracker](https://github.com/osate/aadl-tooling/issues). Include the
extension version, Visual Studio Code version, operating system, reproduction
steps, and relevant output-channel messages.

## Release Notes

See the [changelog](CHANGELOG.md) for release notes.

## Third-party software

Each package embeds an unmodified Eclipse Temurin 21 JRE from
[Adoptium](https://adoptium.net), distributed under the GNU General Public
License, version 2, with the Classpath Exception. Its own license and notice files
travel with it under `runtime/legal/` and `runtime/NOTICE` inside the installed
extension. The AADL extension itself, and the language server it runs, are covered
by [LICENSE.txt](LICENSE.txt).
