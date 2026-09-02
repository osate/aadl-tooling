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

die() {
	echo "error: $*" >&2
	exit 1
}

warn() {
	echo "warning: $*" >&2
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

common_script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$common_script_dir/../../.." && pwd)
packaging_dir="$repo_root/osate-cli/packaging"

if [ ! -f "$packaging_dir/metadata.env" ]; then
	die "metadata file not found: $packaging_dir/metadata.env"
fi

# shellcheck source=/dev/null
source "$packaging_dir/metadata.env"

# The version is NOT metadata: it is declared once in the <revision> property of
# osate-cli/pom.xml and read back out of the built jar, so package versions cannot
# drift from what 'osate-cli -v' reports. OSATE_CLI_VERSION is set by exactly one of
# the two functions below before any version-dependent helper is used.
OSATE_CLI_VERSION=""

# Reads the version from the version.properties resource in a built osate-cli.jar.
version_from_dist() {
	local dist_dir=$1
	local jar="$dist_dir/osate-cli.jar"
	local version

	[ -f "$jar" ] || die "osate-cli.jar not found in $dist_dir"
	require_command unzip
	version=$(unzip -p "$jar" org/osate/cli/version.properties 2>/dev/null \
		| awk -F= '/^version=/ { gsub(/[[:space:]]/, "", $2); print $2; exit }')
	[ -n "$version" ] || die "no version found in $jar (org/osate/cli/version.properties)"
	case "$version" in
		'${'*) die "unfiltered version in $jar: $version" ;;
	esac
	printf '%s\n' "$version"
}

# Reads one build-provenance key from the version.properties resource in a built jar.
provenance_from_dist() {
	local dist_dir=$1
	local key=$2
	local jar="$dist_dir/osate-cli.jar"

	[ -f "$jar" ] || die "osate-cli.jar not found in $dist_dir"
	require_command unzip
	unzip -p "$jar" org/osate/cli/version.properties 2>/dev/null |
		awk -F= -v k="^$key=" '$0 ~ k { gsub(/[[:space:]]/, "", $2); print $2; exit }'
}

# A release must be able to say which OSATE it was built from, and must say so
# truthfully. Both checks matter because the values are only supplied when the build
# goes through scripts/build-test-release: packaging a CLI built any other way would
# otherwise ship 'unknown', and nothing would catch a recorded OSATE commit that
# disagrees with the submodule pin actually built.
require_release_provenance() {
	local dist_dir=$1
	local expected_osate_commit=${2:-}
	local key value

	for key in ls.version ls.commit osate.version osate.commit; do
		value=$(provenance_from_dist "$dist_dir" "$key")
		[ -n "$value" ] || die "missing $key in osate-cli.jar; build via scripts/build-test-release"
		case "$value" in
			unknown | '${'*)
				die "osate-cli.jar reports $key=$value; a release must record real provenance. Build via scripts/build-test-release, which supplies it."
				;;
		esac
	done

	# A warning, not a failure: the -dirty suffix is recorded in the jar and shown by
	# 'osate-cli help', so the artifact is not misleading, and refusing would block the
	# local packaging smoke tests in packaging/README.md. A CI release builds from a
	# clean checkout and never reaches this.
	value=$(provenance_from_dist "$dist_dir" ls.commit)
	case "$value" in
		*-dirty)
			warn "packaging a CLI built from a dirty tree (ls.commit=$value)"
			;;
	esac

	if [ -n "$expected_osate_commit" ]; then
		value=$(provenance_from_dist "$dist_dir" osate.commit)
		if [ "$value" != "$expected_osate_commit" ]; then
			die "osate-cli.jar records osate.commit=$value but the osate2 gitlink is $expected_osate_commit"
		fi
	fi
}

# Reads the version recorded next to the artifacts by build-release-artifacts.sh.
version_from_artifacts() {
	local artifacts_dir=$1
	local file="$artifacts_dir/VERSION"
	local version

	[ -f "$file" ] || die "version file not found: $file (run build-release-artifacts.sh first)"
	version=$(tr -d '[:space:]' < "$file")
	[ -n "$version" ] || die "version file is empty: $file"
	printf '%s\n' "$version"
}

require_version() {
	[ -n "$OSATE_CLI_VERSION" ] || die "internal error: version was not resolved"
}

sha256_file() {
	local file=$1
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$file" | awk '{print $1}'
	elif command -v shasum >/dev/null 2>&1; then
		shasum -a 256 "$file" | awk '{print $1}'
	else
		die "required command not found: sha256sum or shasum"
	fi
}

artifact_sha256() {
	local sums_file=$1
	local artifact_name=$2
	awk -v artifact="$artifact_name" '$2 == artifact { print $1 }' "$sums_file"
}

strip_trailing_slash() {
	local value=$1
	while [ "${value%/}" != "$value" ]; do
		value=${value%/}
	done
	printf '%s\n' "$value"
}

target_platform() {
	case "$1" in
		macos-x64 | macos-arm64)
			printf '%s\n' "macos"
			;;
		linux-x64 | linux-arm64)
			printf '%s\n' "linux"
			;;
		*)
			die "unsupported target: $1"
			;;
	esac
}

target_adoptium_os() {
	case "$1" in
		macos-*)
			printf '%s\n' "mac"
			;;
		linux-*)
			printf '%s\n' "linux"
			;;
		*)
			die "unsupported target: $1"
			;;
	esac
}

target_adoptium_arch() {
	case "$1" in
		*-x64)
			printf '%s\n' "x64"
			;;
		*-arm64)
			printf '%s\n' "aarch64"
			;;
		*)
			die "unsupported target: $1"
			;;
	esac
}

target_nfpm_arch() {
	case "$1" in
		linux-x64)
			printf '%s\n' "amd64"
			;;
		linux-arm64)
			printf '%s\n' "arm64"
			;;
		*)
			die "nFPM packages are only defined for Linux targets: $1"
			;;
	esac
}

artifact_extension() {
	case "$1" in
		macos-* | linux-*)
			printf '%s\n' "tar.gz"
			;;
		*)
			die "unsupported target: $1"
			;;
	esac
}

artifact_basename() {
	local target=$1
	require_version
	printf '%s-%s-%s\n' "$OSATE_CLI_PACKAGE_NAME" "$OSATE_CLI_VERSION" "$target"
}

artifact_filename() {
	local target=$1
	printf '%s.%s\n' "$(artifact_basename "$target")" "$(artifact_extension "$target")"
}
