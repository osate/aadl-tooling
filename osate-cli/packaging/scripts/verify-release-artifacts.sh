#!/usr/bin/env bash
# OSATE Command Line Interface
#
# Copyright 2026 Carnegie Mellon University.
#
# NO WARRANTY. THIS CARNEGIE MELLON UNIVERSITY AND SOFTWARE ENGINEERING INSTITUTE MATERIAL IS
# FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON UNIVERSITY MAKES NO WARRANTIES OF ANY KIND,
# EITHER EXPRESSED OR IMPLIED, AS TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF
# FITNESS FOR PURPOSE OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE
# MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT MAKE ANY WARRANTY OF ANY KIND WITH RESPECT TO
# FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.
#
# Licensed under a BSD (SEI)-style license, please see LICENSE.txt
# or contact permission@sei.cmu.edu for full terms.
#
# [DISTRIBUTION STATEMENT A] This material has been approved for public release and unlimited
# distribution.  Please see Copyright notice for non-US Government use and distribution.
#
# This Software includes and/or makes use of Third-Party Software each subject to its own license.
#
# DM26-0838

set -euo pipefail
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
source "$script_dir/common.sh"
artifacts_dir="$packaging_dir/target/artifacts"
include_msi=true
while [ $# -gt 0 ]; do
	case "$1" in
		--artifacts-dir)
			[ $# -ge 2 ] || die "--artifacts-dir requires a value"
			artifacts_dir=$2
			shift 2
			;;
		--without-msi) include_msi=false; shift ;;
		*) die "unknown option: $1" ;;
	esac
done
OSATE_CLI_VERSION=$(version_from_artifacts "$artifacts_dir")
files=()
for target in macos-x64 macos-arm64 linux-x64 linux-arm64 windows-x64 windows-arm64; do
	files+=("$(artifact_filename "$target")")
done
files+=("osate-cli_${OSATE_CLI_VERSION}_amd64.deb" "osate-cli_${OSATE_CLI_VERSION}_arm64.deb"
	"osate-cli-${OSATE_CLI_VERSION}-1.x86_64.rpm" "osate-cli-${OSATE_CLI_VERSION}-1.aarch64.rpm")
if [ "$include_msi" = true ]; then
	files+=("osate-cli-${OSATE_CLI_VERSION}-windows-x64.msi" "osate-cli-${OSATE_CLI_VERSION}-windows-arm64.msi")
fi
for file in "${files[@]}"; do
	[ -s "$artifacts_dir/$file" ] || die "missing release artifact: $file"
	expected=$(artifact_sha256 "$artifacts_dir/SHA256SUMS" "$file")
	[[ "$expected" =~ ^[0-9a-fA-F]{64}$ ]] || die "missing or duplicate checksum for $file"
	[ "$(sha256_file "$artifacts_dir/$file")" = "$expected" ] || die "checksum mismatch for $file"
	echo "Verified $file"
done
