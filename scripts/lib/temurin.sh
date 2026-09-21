#!/usr/bin/env bash

# Copyright (c) 2004-2026 Carnegie Mellon University and others. (see Contributors file).
# All Rights Reserved.
#
# NO WARRANTY. ALL MATERIAL IS FURNISHED ON AN "AS-IS" BASIS. CARNEGIE MELLON UNIVERSITY MAKES NO WARRANTIES OF ANY
# KIND, EITHER EXPRESSED OR IMPLIED, AS TO ANY MATTER INCLUDING, BUT NOT LIMITED TO, WARRANTY OF FITNESS FOR PURPOSE
# OR MERCHANTABILITY, EXCLUSIVITY, OR RESULTS OBTAINED FROM USE OF THE MATERIAL. CARNEGIE MELLON UNIVERSITY DOES NOT
# MAKE ANY WARRANTY OF ANY KIND WITH RESPECT TO FREEDOM FROM PATENT, TRADEMARK, OR COPYRIGHT INFRINGEMENT.
#
# This program and the accompanying materials are made available under the terms of the Eclipse Public License 2.0
# which is available at https://www.eclipse.org/legal/epl-2.0/
# SPDX-License-Identifier: EPL-2.0
#
# Created, in part, with funding and support from the United States Government. (see Acknowledgments file).
#
# This program includes and/or can make use of certain third party source code, object code, documentation and other
# files ("Third Party Software"). The Third Party Software that is used by this program is dependent upon your system
# configuration. By using this program, You agree to comply with any and all relevant Third Party Software terms and
# conditions contained in any such Third Party Software or separate license file distributed with such Third Party
# Software. The parties who own the Third Party Software ("Third Party Licensors") are intended third party beneficiaries
# to this license with respect to the terms applicable to their Third Party Software. Third Party Software licenses
# only apply to the Third Party Software and not any other portion of this program or this program as a whole.

# Downloads and unpacks Eclipse Temurin JREs from Adoptium.
#
# Sourced by both bundling paths: osate-cli release packaging and VS Code
# extension packaging. It takes Adoptium coordinates (os, architecture, archive
# extension, java executable name) rather than either caller's target ids, so
# neither naming scheme leaks in here. Every function is prefixed and defines
# its own diagnostics, so sourcing it cannot collide with a caller's helpers.

# The feature version both paths bundle. osate-cli pins its own value in
# osate-cli/packaging/metadata.env, which is sourced before this file and is
# therefore left alone; this default serves callers that have no metadata of
# their own. It must stay >= minimumJavaMajorVersion in
# vscode-extension/src/javaRuntime.ts.
: "${TEMURIN_FEATURE_VERSION:=21}"

temurin_die() {
	echo "error: $*" >&2
	exit 1
}

temurin_warn() {
	echo "warning: $*" >&2
}

temurin_require_command() {
	command -v "$1" >/dev/null 2>&1 || temurin_die "required command not found: $1"
}

temurin_sha256() {
	local file=$1
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$file" | awk '{ print $1 }'
	elif command -v shasum >/dev/null 2>&1; then
		shasum -a 256 "$file" | awk '{ print $1 }'
	else
		temurin_die "neither sha256sum nor shasum is available"
	fi
}

temurin_download_url() {
	local feature=$1 os=$2 arch=$3
	printf 'https://api.adoptium.net/v3/binary/latest/%s/ga/%s/%s/jre/hotspot/normal/eclipse?project=jdk\n' \
		"$feature" "$os" "$arch"
}

# The checksum Adoptium publishes for the archive we are about to fetch. Prints
# nothing when the API cannot be reached or the response has no archive entry,
# which is what lets an offline rebuild proceed from a cached archive.
#
# The assets response holds several binaries per platform (a .tar.gz or .zip
# archive plus a .pkg or .msi installer), each with its own checksum, so the one
# we want is selected by the archive's file name. Collapsing the whitespace and
# splitting on '}' puts each binary's checksum and name on one line, which is
# what makes this a sed job rather than a JSON parse.
temurin_expected_checksum() {
	local feature=$1 os=$2 arch=$3 ext=$4
	local url="https://api.adoptium.net/v3/assets/latest/${feature}/hotspot?os=${os}&architecture=${arch}&image_type=jre&vendor=eclipse"

	curl -fsSL --retry 2 --retry-delay 2 "$url" 2>/dev/null |
		tr -d ' \t\n' |
		tr '}' '\n' |
		grep "\"name\":\"[^\"]*\.${ext}\"" |
		sed -n 's/.*"checksum":"\([0-9a-f]\{64\}\)".*/\1/p' |
		head -n 1
}

# Prints the path to a verified archive, downloading it only when the cache does
# not already hold it. "latest GA" floats, so a cached archive whose digest no
# longer matches the published one is a superseded build rather than corruption:
# fetch once more, then insist.
temurin_download() {
	local feature=$1 os=$2 arch=$3 ext=$4 cache_dir=$5
	local archive="$cache_dir/temurin-${feature}-${os}-${arch}.${ext}"
	local expected actual url attempt

	temurin_require_command curl
	temurin_require_command awk
	mkdir -p "$cache_dir"

	expected=$(temurin_expected_checksum "$feature" "$os" "$arch" "$ext")
	url=$(temurin_download_url "$feature" "$os" "$arch")

	for attempt in 1 2; do
		if [ ! -f "$archive" ]; then
			echo "Downloading Eclipse Temurin $feature JRE for $os/$arch" >&2
			curl -fL --retry 3 --retry-delay 2 -o "$archive.tmp" "$url"
			mv "$archive.tmp" "$archive"
		fi

		if [ -z "$expected" ]; then
			temurin_warn "could not read the published checksum for $os/$arch; using $archive unverified"
			printf '%s\n' "$archive"
			return
		fi

		actual=$(temurin_sha256 "$archive")
		if [ "$actual" = "$expected" ]; then
			printf '%s\n' "$archive"
			return
		fi

		if [ "$attempt" = 1 ]; then
			temurin_warn "cached $archive does not match the published checksum; downloading it again"
			rm -f "$archive"
		fi
	done

	temurin_die "checksum mismatch for $archive: expected $expected, got $actual"
}

# Unpacks an archive and prints the Java home inside it.
#
# The macOS archives nest the runtime under Contents/Home while the others do
# not, so the home is located by finding the java executable instead of being
# assumed: every caller then sees the same bin/<java> layout. Extraction is
# skipped when the directory already holds this exact archive, which keeps
# repeated staging in a local build loop cheap.
temurin_unpack() {
	local archive=$1 extract_dir=$2 java_exe=$3
	local digest marker java_bin dangling

	digest=$(temurin_sha256 "$archive")
	marker="$extract_dir/.temurin-unpacked"
	java_bin=""

	# The marker records which archive was unpacked here, but something else may
	# have disturbed the tree since — a clean that followed a symlink into it, for
	# instance. Re-extract unless the executable is actually still there, so a
	# damaged cache heals itself instead of failing every later build.
	if [ -f "$marker" ] && [ "$(cat "$marker")" = "$digest" ]; then
		java_bin=$(find "$extract_dir" -path "*/bin/$java_exe" -print -quit 2>/dev/null || true)
	fi

	if [ -z "$java_bin" ]; then
		rm -rf "$extract_dir"
		mkdir -p "$extract_dir"
		case "$archive" in
		*.zip)
			temurin_require_command unzip
			unzip -q "$archive" -d "$extract_dir"
			;;
		*.tar.gz)
			temurin_require_command tar
			tar -xzf "$archive" -C "$extract_dir"
			;;
		*)
			temurin_die "unsupported archive type: $archive"
			;;
		esac
		printf '%s\n' "$digest" > "$marker"
	fi

	java_bin=$(find "$extract_dir" -path "*/bin/$java_exe" -print -quit)
	[ -n "$java_bin" ] || temurin_die "could not find bin/$java_exe in $archive"

	# The Linux runtimes symlink most of legal/ into java.base. Packaging tools
	# that dereference symlinks fail on a broken one, so refuse a runtime that
	# would only fall over later.
	dangling=$(find "$(dirname "$java_bin")/.." -type l ! -exec test -e {} \; -print)
	[ -z "$dangling" ] || temurin_die "dangling symlinks in the unpacked runtime: $dangling"

	(cd "$(dirname "$java_bin")/.." && pwd)
}
