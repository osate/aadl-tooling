# Security Policy

## Reporting a vulnerability

Please do not open a public issue for a security vulnerability.

Report it privately through GitHub: go to the
[Security tab](https://github.com/osate/aadl-tooling/security/advisories/new) of
this repository and open a draft security advisory. If private reporting is
unavailable to you, email <info@osate.org> instead.

Please include:

- the affected component (language server, VS Code extension, or `osate-cli`)
  and version;
- your platform and Java version;
- what an attacker can do, and the steps or model needed to reproduce it.

You can expect an acknowledgement within a few business days. We will keep you
informed while we investigate and will credit you in the advisory unless you ask
otherwise.

## Scope

This policy covers the code in this repository. Vulnerabilities in OSATE itself
belong to <https://github.com/osate/osate2>, and vulnerabilities in Eclipse,
Xtext, or the Red Hat Java extension belong to their respective projects.

Note that `osate-cli` deliberately runs a long-lived workspace server bound to
`127.0.0.1` only. Reports that require an attacker to already have local code
execution as the same user are generally out of scope, but if you find a way to
reach that server from another host or another user, we want to hear about it.

## Supported versions

Only the current `main` branch receives security fixes.
