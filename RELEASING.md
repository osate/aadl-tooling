# Releasing

The three deliverables version and release independently. A release is triggered
by pushing a component-prefixed tag; nothing is published from `main`.

| Component | Tag | Version source |
| --- | --- | --- |
| `osate-cli` | `osate-cli-v<version>` | `<revision>` in [`osate-cli/pom.xml`](osate-cli/pom.xml) |
| VS Code extension | `vscode-v<version>` or `vscode-v<version>-pre` | `version` in [`vscode-extension/package.json`](vscode-extension/package.json) |
| Language server | `ls-v<version>` | `aadl.ls.parent` version in [`aadl-language-server/pom.xml`](aadl-language-server/pom.xml) |

The tag does not set the version. Each release workflow reads the version out of
the repository and **fails if the tag disagrees**, so there is exactly one source
of truth. Bump the version in a normal commit first, then tag that commit.

## Cutting a release

```bash
# 1. Bump the version and merge it through a pull request.
#    osate-cli:  <revision> in osate-cli/pom.xml
#    extension:  package.json and package-lock.json together
#    server:     aadl-language-server/pom.xml (a -SNAPSHOT version is refused)

# 2. Tag the merged commit and push the tag.
git switch main && git pull
git tag osate-cli-v0.1.4
git push origin osate-cli-v0.1.4
```

Watch it with `gh run watch`. Each workflow also accepts `workflow_dispatch`,
which builds and packages everything but creates no release — use that to
rehearse a release before tagging.

## What each release produces

**`osate-cli-v*`** — [`release-osate-cli.yml`](.github/workflows/release-osate-cli.yml)

Four platform tarballs (linux-x64, linux-arm64, macos-x64, macos-arm64), two
`.deb`, two `.rpm`, and `SHA256SUMS`, all attached to a GitHub Release. The
workflow then regenerates the Homebrew formula against the release's download
prefix and pushes it to the tap. Every artifact is checked for presence and
verified against `SHA256SUMS` before publishing; `nfpm` is installed and required
so a missing packager cannot silently drop the four Linux packages.

**`vscode-v*`** — [`release-vscode.yml`](.github/workflows/release-vscode.yml)

`aadl2-<version>.vsix` attached to a GitHub Release, then published to the VS
Code Marketplace and Open VSX. The VSIX is checked for a plausible number of
bundled server plug-ins first, because the server reaches it through a symlink
that would otherwise fail silently.

### Stable or pre-release

A `-pre` suffix on the tag publishes a pre-release; without it the release is
stable. The version itself is identical either way:

```bash
git tag vscode-v0.0.2-pre    # pre-release: users must opt in
git tag vscode-v0.0.2        # stable: offered to everyone
```

A pre-release is marked as such on the Marketplace and on Open VSX, and the
GitHub Release gets the pre-release badge. VS Code keeps users on the newest
*stable* version unless they explicitly switch that extension to the pre-release
channel, so this is the way to put a build in front of willing testers without
pushing it at everyone.

The suffix belongs on the tag rather than in the version because VS Code
extension versions must stay `major.minor.patch` — a pre-release is not a
different version string, it is a property recorded inside the package. That
property is written by `vsce package --pre-release`, and `vsce publish` **refuses**
to publish a package as a pre-release unless it was built as one:

```text
Cannot use '--pre-release' flag with a package that was not packaged as
pre-release. Please package it using the '--pre-release' flag and publish again.
```

So the decision is made at build time, not publish time. The tag drives it all
the way down: `release-vscode.yml` parses the suffix, passes
`extension-pre-release` to the build workflow, which passes
`--extension-pre-release` to `scripts/build-test-release`, which sets
`-Dvsce.package.script=package:pre-release` so Maven runs the npm script that adds
the flag. Before publishing, the workflow re-reads the built VSIX manifest and
fails if the marker disagrees with the tag, so the two cannot drift apart.

Marketplace and Open VSX both treat a published version as permanent — you can
unpublish an extension but not an individual version. Choosing pre-release does
not change that; it only changes who is offered the build.

To produce a pre-release VSIX locally:

```bash
./scripts/build-test-release --skip-osate --extension-pre-release
```

**`ls-v*`** — [`release-server.yml`](.github/workflows/release-server.yml)

The p2 repository archive plus `build-provenance.properties`, attached to a
GitHub Release, so other LSP clients and Eclipse installs can consume the server
without building OSATE.

## Required secrets

GitHub Releases need nothing — `GITHUB_TOKEN` covers them. The other targets skip
cleanly with a note in the run summary until their secret exists, so the
workflows are usable before any of this is set up.

| Secret | For | One-time setup |
| --- | --- | --- |
| `VSCE_PAT` | VS Code Marketplace | Create an Azure DevOps organization, create a publisher with the ID `osate` at <https://marketplace.visualstudio.com/manage>, then issue a PAT scoped to **Marketplace → Manage** (all accessible organizations). |
| `OVSX_PAT` | Open VSX | Sign in at <https://open-vsx.org>, claim the `osate` namespace, sign the publisher agreement, and create an access token. |
| `HOMEBREW_TAP_TOKEN` | Homebrew tap | Create the repository `osate/homebrew-osate`, then a fine-grained PAT with **Contents: read and write** on it. `GITHUB_TOKEN` cannot write to another repository. |

Add them with `gh secret set VSCE_PAT --repo osate/aadl-tooling`.

## Checking the credentials

Every credential above is only exercised at the very end of a release, after a
build that can take a quarter of an hour. Verify them first — it takes seconds
and publishes nothing:

```bash
gh workflow run verify-credentials.yml --repo osate/aadl-tooling
gh run watch --repo osate/aadl-tooling
```

It authenticates the Marketplace PAT against the `osate` publisher with
`vsce verify-pat`, checks the Open VSX namespace, and confirms push access to the
tap. The run summary says which credentials are valid, which are unset, and what
to fix. It also runs weekly, so an expired token surfaces before a release
rather than during one.

To rehearse a release without publishing, dispatch the release workflow itself:
every publish step is gated on the ref being a tag, so a manual run builds and
packages but releases nothing.

```bash
gh workflow run release-osate-cli.yml --repo osate/aadl-tooling --ref main
```

## Notes

- The `osate2` submodule pin is part of every release. `build-provenance.properties`
  records the tooling commit, the OSATE commit and gitlink, and all three
  versions, so a released artifact can always be traced back to its exact inputs.
- macOS tarballs are unsigned and not notarized. Downloads through a browser will
  be quarantined by Gatekeeper; the Homebrew path is not affected. The `.deb` and
  `.rpm` packages are unsigned too.
- Release runs reuse the cached OSATE build when the submodule pin has not moved.
  A release right after a submodule bump pays for a full OSATE build.
