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
output_dir="$packaging_dir/target/local-homebrew"
base_url=""
tap_name="osate/local"

usage() {
	cat <<EOF
usage: $(basename "$0") [options]

Generate a local Homebrew tap under packaging/target for testing. Homebrew 6+
requires formulae to be installed from a tap; direct installation from an
arbitrary formula file is rejected.

Options:
  --artifacts-dir <dir>  Directory containing artifacts and SHA256SUMS.
                         Default: $artifacts_dir
  --output-dir <dir>     Local tap directory.
                         Default: $output_dir
  --base-url <url>       URL prefix for macOS artifacts.
                         Default: file://<artifacts-dir>
  --tap-name <name>      Tap name to show in printed brew commands.
                         Default: $tap_name
  --help                 Show this help.
EOF
}

while [ $# -gt 0 ]; do
	case "$1" in
		--artifacts-dir)
			[ $# -ge 2 ] || die "--artifacts-dir requires a value"
			artifacts_dir=$2
			shift 2
			;;
		--output-dir)
			[ $# -ge 2 ] || die "--output-dir requires a value"
			output_dir=$2
			shift 2
			;;
		--base-url)
			[ $# -ge 2 ] || die "--base-url requires a value"
			base_url=$2
			shift 2
			;;
		--tap-name)
			[ $# -ge 2 ] || die "--tap-name requires a value"
			tap_name=$2
			shift 2
			;;
		--help | -h)
			usage
			exit 0
			;;
		*)
			die "unknown option: $1"
			;;
	esac
done

require_command git

if [ -z "$base_url" ]; then
	base_url="file://$(cd "$artifacts_dir" && pwd)"
fi

"$script_dir/generate-homebrew-formula.sh" \
	--base-url "$base_url" \
	--artifacts-dir "$artifacts_dir" \
	--output-dir "$output_dir"

if [ ! -d "$output_dir/.git" ]; then
	git -C "$output_dir" init -q
fi

git -C "$output_dir" add Formula/osate-cli.rb
if ! git -C "$output_dir" diff --cached --quiet; then
	git -C "$output_dir" \
		-c user.name="osate-cli packaging" \
		-c user.email="$OSATE_CLI_MAINTAINER_EMAIL" \
		commit -q -m "Update osate-cli formula"
fi

tap_url="file://$(cd "$output_dir" && pwd)"

cat <<EOF
Local Homebrew tap prepared: $output_dir

brew tap $tap_name "$tap_url"
brew install $tap_name/osate-cli
brew test $tap_name/osate-cli
EOF
