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

Edit AADL files, instantiate, run latency, bus load, and mode reachability analysis

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

Depends on the Red Hat Java extension (`redhat.java`), which is installed
automatically. The language server uses the tooling JRE provided by that
extension and verifies that it is Java 21 or newer. If that JRE is unavailable
or outdated, update or reinstall the Red Hat Java extension.

## Extension Settings

- `aadl2Server.maxNumberOfProblems` — maximum number of problems reported per
  file (default 100)
- `aadl2Server.trace.server` — `off` | `messages` | `verbose`; traces LSP
  traffic between VSCode and the language server

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

## Known Issues

Very early prototype. Jump to definition and AadlDoc hover do not yet work
inside annexes.

## Release Notes

See the [changelog](CHANGELOG.md) for release notes.
