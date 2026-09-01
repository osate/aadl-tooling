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

default_targets=(macos-x64 macos-arm64 linux-x64 linux-arm64)
targets=()
dist_dir="$repo_root/osate-cli/dist/target/dist"
output_dir="$packaging_dir/target"
nfpm_mode="auto"
java_feature_version="$TEMURIN_FEATURE_VERSION"
expect_version=""

usage() {
	cat <<EOF
usage: $(basename "$0") [options]

Build macOS/Linux architecture-specific osate-cli release artifacts with bundled
Eclipse Temurin Java, plus Linux deb/rpm packages when nFPM is available.

Options:
  --dist-dir <dir>             Existing Maven dist directory.
                               Default: $dist_dir
  --output-dir <dir>           Output directory.
                               Default: $output_dir
  --target <target>            Target to build. May be repeated.
                               Defaults: ${default_targets[*]}
  --java-feature-version <n>   Temurin feature version to download.
                               Default: $java_feature_version
  --expect-version <version>   Fail unless the dist reports this version.
  --nfpm                       Require nFPM and build Linux deb/rpm packages.
  --no-nfpm                    Do not build Linux deb/rpm packages.
  --help                       Show this help.

The package version is read from the built osate-cli.jar, which Maven versions from
the <revision> property in osate-cli/pom.xml. It therefore always matches the version
reported by 'osate-cli -v'.

Targets:
  macos-x64, macos-arm64, linux-x64, linux-arm64
EOF
}

while [ $# -gt 0 ]; do
	case "$1" in
		--dist-dir)
			[ $# -ge 2 ] || die "--dist-dir requires a value"
			dist_dir=$2
			shift 2
			;;
		--output-dir)
			[ $# -ge 2 ] || die "--output-dir requires a value"
			output_dir=$2
			shift 2
			;;
		--target)
			[ $# -ge 2 ] || die "--target requires a value"
			target_platform "$2" >/dev/null
			targets+=("$2")
			shift 2
			;;
		--java-feature-version)
			[ $# -ge 2 ] || die "--java-feature-version requires a value"
			java_feature_version=$2
			shift 2
			;;
		--expect-version)
			[ $# -ge 2 ] || die "--expect-version requires a value"
			expect_version=$2
			shift 2
			;;
		--nfpm)
			nfpm_mode="require"
			shift
			;;
		--no-nfpm)
			nfpm_mode="skip"
			shift
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

if [ ${#targets[@]} -eq 0 ]; then
	targets=("${default_targets[@]}")
fi

require_command curl
require_command awk

if [ ! -f "$dist_dir/osate-cli.jar" ]; then
	die "osate-cli.jar not found in $dist_dir. Run: mvn -f osate-cli/pom.xml package"
fi
if [ ! -d "$dist_dir/lib" ]; then
	die "lib directory not found in $dist_dir. The osate-cli dist layout is incomplete."
fi

OSATE_CLI_VERSION=$(version_from_dist "$dist_dir")
if [ -n "$expect_version" ] && [ "$expect_version" != "$OSATE_CLI_VERSION" ]; then
	die "dist reports version $OSATE_CLI_VERSION, but --expect-version is $expect_version"
fi
echo "Packaging $OSATE_CLI_PACKAGE_NAME $OSATE_CLI_VERSION (from $dist_dir/osate-cli.jar)"

downloads_dir="$output_dir/downloads"
staging_dir="$output_dir/staging"
artifacts_dir="$output_dir/artifacts"
generated_dir="$output_dir/generated"

rm -rf "$artifacts_dir"
mkdir -p "$downloads_dir" "$staging_dir" "$artifacts_dir" "$generated_dir"

# Recorded so the Homebrew formula generator can version the formula without
# needing the dist tree.
printf '%s\n' "$OSATE_CLI_VERSION" > "$artifacts_dir/VERSION"

temurin_download_url() {
	local target=$1
	local adoptium_os adoptium_arch
	adoptium_os=$(target_adoptium_os "$target")
	adoptium_arch=$(target_adoptium_arch "$target")
	printf 'https://api.adoptium.net/v3/binary/latest/%s/ga/%s/%s/jre/hotspot/normal/eclipse?project=jdk\n' \
		"$java_feature_version" "$adoptium_os" "$adoptium_arch"
}

download_temurin() {
	local target=$1
	local ext=$2
	local archive="$downloads_dir/temurin-${java_feature_version}-${target}.${ext}"
	local tmp="$archive.tmp"
	local url

	if [ -f "$archive" ]; then
		printf '%s\n' "$archive"
		return
	fi

	url=$(temurin_download_url "$target")
	echo "Downloading Eclipse Temurin $java_feature_version JRE for $target" >&2
	curl -fL --retry 3 --retry-delay 2 -o "$tmp" "$url"
	mv "$tmp" "$archive"
	printf '%s\n' "$archive"
}

extract_runtime() {
	local target=$1
	local archive=$2
	local extract_dir=$3
	local runtime_dir=$4
	local java_name="java"
	local java_bin runtime_home

	rm -rf "$extract_dir" "$runtime_dir"
	mkdir -p "$extract_dir" "$runtime_dir"

	require_command tar
	tar -xzf "$archive" -C "$extract_dir"

	java_bin=$(find "$extract_dir" -path "*/bin/$java_name" -print -quit)
	[ -n "$java_bin" ] || die "could not find $java_name in extracted runtime for $target"
	runtime_home=$(cd "$(dirname "$java_bin")/.." && pwd)
	cp -R "$runtime_home/." "$runtime_dir/"
}

write_unix_launcher() {
	local file=$1
	cat > "$file" <<'EOF'
#!/bin/sh
DIR=$(cd "$(dirname "$0")" && pwd)
exec "$DIR/../runtime/bin/java" -jar "$DIR/../osate-cli.jar" "$@"
EOF
	chmod 755 "$file"
}

write_release_properties() {
	local target=$1
	local file=$2
	cat > "$file" <<EOF
name=$OSATE_CLI_PACKAGE_NAME
version=$OSATE_CLI_VERSION
target=$target
java.vendor=Eclipse Temurin
java.feature.version=$java_feature_version
EOF
}

# The notice files live beside the CLI sources, not at the repository root; the
# root holds only LICENSE.md, which describes the per-directory split rather than
# the terms this deliverable ships under.
copy_notice_files() {
	local payload_dir=$1
	local notice
	for notice in COPYRIGHT LICENSE.txt; do
		if [ ! -f "$repo_root/osate-cli/$notice" ]; then
			die "notice file not found: osate-cli/$notice"
		fi
		cp "$repo_root/osate-cli/$notice" "$payload_dir/"
	done
}

assemble_payload() {
	local target=$1
	local base payload_dir archive ext download_archive extract_dir

	base=$(artifact_basename "$target")
	payload_dir="$staging_dir/$base"
	ext=$(artifact_extension "$target")
	download_archive=$(download_temurin "$target" "$ext")
	extract_dir="$generated_dir/runtime-extract/$target"

	rm -rf "$payload_dir"
	mkdir -p "$payload_dir"
	cp -R "$dist_dir/." "$payload_dir/"
	extract_runtime "$target" "$download_archive" "$extract_dir" "$payload_dir/runtime"

	mkdir -p "$payload_dir/bin"
	write_unix_launcher "$payload_dir/bin/osate-cli"
	rm -f "$payload_dir/bin/osate-cli.bat"
	write_release_properties "$target" "$payload_dir/release.properties"
	copy_notice_files "$payload_dir"

	require_command tar
	archive="$artifacts_dir/$base.tar.gz"
	rm -f "$archive"
	(cd "$staging_dir" && tar -czf "$archive" "$base")

	printf '%s\n' "$payload_dir"
}

write_nfpm_config() {
	local target=$1
	local nfpm_root=$2
	local config=$3
	local arch
	arch=$(target_nfpm_arch "$target")

	{
		cat <<EOF
name: "$OSATE_CLI_PACKAGE_NAME"
arch: "$arch"
platform: "linux"
version: "$OSATE_CLI_VERSION"
section: "devel"
priority: "optional"
maintainer: "$OSATE_CLI_MAINTAINER_EMAIL"
description: "$OSATE_CLI_DESCRIPTION"
vendor: "$OSATE_CLI_VENDOR"
license: "$OSATE_CLI_LICENSE"
EOF
		if [ -n "$OSATE_CLI_HOMEPAGE" ]; then
			printf 'homepage: "%s"\n' "$OSATE_CLI_HOMEPAGE"
		fi
		cat <<EOF
contents:
  - src: "$nfpm_root/opt/osate-cli"
    dst: "/opt/osate-cli"
  - src: "$nfpm_root/usr/bin/osate-cli"
    dst: "/usr/bin/osate-cli"
EOF
	} > "$config"
}

build_nfpm_packages() {
	local target=$1
	local payload_dir=$2
	local nfpm_root="$generated_dir/nfpm-root/$target"
	local nfpm_config="$generated_dir/nfpm/$target.yaml"
	local packager

	case "$target" in
		linux-*) ;;
		*) return 0 ;;
	esac

	if ! command -v nfpm >/dev/null 2>&1; then
		if [ "$nfpm_mode" = "require" ]; then
			die "nFPM is required by --nfpm but was not found on PATH"
		fi
		warn "nFPM not found; skipping deb/rpm packages for $target"
		return 0
	fi

	rm -rf "$nfpm_root"
	mkdir -p "$nfpm_root/opt" "$nfpm_root/usr/bin" "$(dirname "$nfpm_config")"
	cp -R "$payload_dir" "$nfpm_root/opt/osate-cli"
	cat > "$nfpm_root/usr/bin/osate-cli" <<'EOF'
#!/bin/sh
exec /opt/osate-cli/bin/osate-cli "$@"
EOF
	chmod 755 "$nfpm_root/usr/bin/osate-cli"

	write_nfpm_config "$target" "$nfpm_root" "$nfpm_config"
	for packager in deb rpm; do
		echo "Building $packager package for $target"
		nfpm package --packager "$packager" --config "$nfpm_config" --target "$artifacts_dir"
	done
}

write_checksums() {
	local file
	: > "$artifacts_dir/SHA256SUMS"
	while IFS= read -r file; do
		printf '%s  %s\n' "$(sha256_file "$file")" "$(basename "$file")" >> "$artifacts_dir/SHA256SUMS"
	done < <(find "$artifacts_dir" -maxdepth 1 -type f ! -name SHA256SUMS ! -name VERSION -print | sort)

	: > "$downloads_dir/SHA256SUMS"
	while IFS= read -r file; do
		printf '%s  %s\n' "$(sha256_file "$file")" "$(basename "$file")" >> "$downloads_dir/SHA256SUMS"
	done < <(find "$downloads_dir" -maxdepth 1 -type f ! -name SHA256SUMS -print | sort)
}

for target in "${targets[@]}"; do
	echo "Assembling $target"
	payload_dir=$(assemble_payload "$target")
	if [ "$nfpm_mode" != "skip" ]; then
		build_nfpm_packages "$target" "$payload_dir"
	fi
done

write_checksums

echo "Artifacts: $artifacts_dir"
echo "Checksums: $artifacts_dir/SHA256SUMS"
