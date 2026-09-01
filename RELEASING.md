# Releasing

The three deliverables version and release independently. A release is triggered
by pushing a component-prefixed tag; nothing is published from `main`.

| Component | Tag | Version source |
| --- | --- | --- |
| `osate-cli` | `osate-cli-v<version>` | `<revision>` in [`osate-cli/pom.xml`](osate-cli/pom.xml) |
| VS Code extension | `vscode-v<version>` | `version` in [`vscode-extension/package.json`](vscode-extension/package.json) |
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

## Notes

- The `osate2` submodule pin is part of every release. `build-provenance.properties`
  records the tooling commit, the OSATE commit and gitlink, and all three
  versions, so a released artifact can always be traced back to its exact inputs.
- macOS tarballs are unsigned and not notarized. Downloads through a browser will
  be quarantined by Gatekeeper; the Homebrew path is not affected. The `.deb` and
  `.rpm` packages are unsigned too.
- Release runs reuse the cached OSATE build when the submodule pin has not moved.
  A release right after a submodule bump pays for a full OSATE build.
