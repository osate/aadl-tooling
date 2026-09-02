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

To install a downloaded release instead, open the Extensions view menu, select
**Install from VSIX...**, and choose the `aadl2-<version>.vsix` file.

The extension automatically installs its required Red Hat Java extension
dependency.

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
- The Red Hat Java extension (`redhat.java`), installed automatically
- Java 21 or newer in the tooling JRE provided by the Red Hat Java extension

The AADL extension always uses that tooling JRE to run its bundled language
server. If the JRE is unavailable or outdated, update or reinstall the Red Hat
Java extension.

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
launch the server.

If the Red Hat Java extension does not provide a tooling JRE, or provides a Java
version older than 21, update or reinstall that extension.

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
