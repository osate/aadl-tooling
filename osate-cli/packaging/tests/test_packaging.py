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

"""Run release assembly against small offline payloads to catch archive/launcher loss."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile

REPO = Path(__file__).resolve().parents[3]
BUILDER = REPO / "osate-cli/packaging/scripts/build-release-artifacts.sh"


class PackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="osate packaging ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.dist = self.root / "dist"
        self.output = self.root / "output with spaces"
        (self.dist / "lib").mkdir(parents=True)
        (self.dist / "lib/server.jar").write_bytes(b"fixture plugin")
        (self.dist / "bin").mkdir()
        (self.dist / "bin/osate-cli").write_text("old Unix launcher")
        (self.dist / "bin/osate-cli.bat").write_text("old Windows launcher")
        self.pin = subprocess.check_output(
            ["git", "ls-files", "--stage", "osate2"], cwd=REPO, text=True
        ).split()[1]
        self.write_jar(self.pin)
        self.downloads = self.output / "downloads"
        self.downloads.mkdir(parents=True)
        self.tools = self.root / "tools"
        self.tools.mkdir()
        # Only metadata is requested: every fixture archive is already cached.
        # Any attempt to download an unprepared archive fails the test.
        curl = self.tools / "curl"
        curl.write_text("#!" + sys.executable + "\n" +
            "import json, os, sys\n" +
            "from urllib.parse import urlparse, parse_qs\n" +
            "q = parse_qs(urlparse(sys.argv[-1]).query)\n" +
            "key = q['os'][0] + '/' + q['architecture'][0]\n" +
            "print(json.dumps(json.loads(os.environ['FIXTURE_SUMS'])[key]))\n")
        curl.chmod(0o755)
        self.sums = {}

    def write_jar(self, pin):
        with zipfile.ZipFile(self.dist / "osate-cli.jar", "w") as jar:
            jar.writestr("org/osate/cli/version.properties",
                         f"version=1.2.3\nls.version=1.0\nls.commit=abc\nosate.version=2.20\nosate.commit={pin}\n")

    def runtime(self, target, missing_java=False):
        platform, arch = target.split("-")
        platform = {"macos": "mac"}.get(platform, platform)
        arch = {"arm64": "aarch64"}.get(arch, arch)
        windows = platform == "windows"
        extension = "zip" if windows else "tar.gz"
        archive = self.downloads / f"temurin-21-{platform}-{arch}.{extension}"
        java = "java.exe" if windows and not missing_java else "java"
        prefix = "jre/Contents/Home" if platform == "mac" else "jre"
        files = {f"{prefix}/bin/{java}": b"fixture runtime", f"{prefix}/release": b'JAVA_VERSION="21"'}
        if windows:
            with zipfile.ZipFile(archive, "w") as out:
                for name, content in files.items():
                    out.writestr(name, content)
        else:
            with tarfile.open(archive, "w:gz") as out:
                for name, content in files.items():
                    entry = tarfile.TarInfo(name)
                    entry.size = len(content)
                    out.addfile(entry, io.BytesIO(content))
        self.sums[f"{platform}/{arch}"] = {"package": {
            "name": f"runtime.{extension}", "checksum": hashlib.sha256(archive.read_bytes()).hexdigest()}}

    def build(self, *targets, success=True):
        env = dict(os.environ, PATH=str(self.tools) + os.pathsep + os.environ["PATH"],
                   FIXTURE_SUMS=json.dumps(self.sums))
        command = [str(BUILDER), "--dist-dir", str(self.dist), "--output-dir",
                   os.path.relpath(self.output, REPO), "--no-nfpm"]
        for target in targets:
            command += ["--target", target]
        result = subprocess.run(command, cwd=REPO, env=env, text=True, capture_output=True)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def test_windows_archives_keep_bundled_runtime_launcher_and_plugins(self):
        targets = ("windows-x64", "windows-arm64")
        for target in targets:
            self.runtime(target)
        self.build(*targets)
        for target in targets:
            base = f"osate-cli-1.2.3-{target}"
            archive = self.output / "artifacts" / (base + ".zip")
            with zipfile.ZipFile(archive) as package:
                self.assertIsNone(package.testzip())
                self.assertIn(f"{base}/runtime/bin/java.exe", package.namelist())
                self.assertEqual(package.read(f"{base}/lib/server.jar"), b"fixture plugin")
                self.assertIn(f"{base}/LICENSE.txt", package.namelist())
                launcher = package.read(f"{base}/bin/osate-cli.bat")
                self.assertIn(b'%~dp0..\\runtime\\bin\\java.exe', launcher)
                self.assertIn(b'exit /b %errorlevel%\r\n', launcher)
                self.assertNotIn(b"\n", launcher.replace(b"\r\n", b""))
                self.assertNotIn(f"{base}/bin/osate-cli", package.namelist())
            checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
            self.assertIn(f"{checksum}  {archive.name}", (archive.parent / "SHA256SUMS").read_text())

    def test_unix_archives_still_have_unix_launcher(self):
        for target in ("macos-arm64", "linux-x64"):
            self.runtime(target)
        self.build("macos-arm64", "linux-x64")
        for target in ("macos-arm64", "linux-x64"):
            base = f"osate-cli-1.2.3-{target}"
            with tarfile.open(self.output / "artifacts" / (base + ".tar.gz")) as package:
                self.assertIn(f"{base}/runtime/bin/java", package.getnames())
                self.assertEqual(package.getmember(f"{base}/bin/osate-cli").mode & 0o777, 0o755)
                self.assertNotIn(f"{base}/bin/osate-cli.bat", package.getnames())

    def test_missing_windows_executable_stops_before_creating_archive(self):
        self.runtime("windows-x64", missing_java=True)
        result = self.build("windows-x64", success=False)
        self.assertIn("could not find bin/java.exe", result.stderr)
        self.assertFalse(list((self.output / "artifacts").glob("*.zip")))

    def test_stale_osate_provenance_is_rejected(self):
        self.write_jar("0" * 40)
        result = self.build("windows-x64", success=False)
        self.assertIn("but the osate2 gitlink is", result.stderr)

    def test_release_gate_requires_both_msis_and_their_checksums(self):
        artifacts = self.output / "artifacts"
        artifacts.mkdir()
        (artifacts / "VERSION").write_text("1.2.3\n")
        names = [f"osate-cli-1.2.3-{target}.tar.gz" for target in
                 ("macos-x64", "macos-arm64", "linux-x64", "linux-arm64")]
        names += [f"osate-cli-1.2.3-windows-{arch}.{ext}"
                  for arch in ("x64", "arm64") for ext in ("zip", "msi")]
        names += ["osate-cli_1.2.3_amd64.deb", "osate-cli_1.2.3_arm64.deb",
                  "osate-cli-1.2.3-1.x86_64.rpm", "osate-cli-1.2.3-1.aarch64.rpm"]
        sums = []
        for name in names:
            content = name.encode()
            (artifacts / name).write_bytes(content)
            sums.append(f"{hashlib.sha256(content).hexdigest()}  {name}\n")
        checksum_file = artifacts / "SHA256SUMS"
        checksum_file.write_text("".join(sums))
        command = [str(BUILDER.with_name("verify-release-artifacts.sh")),
                   "--artifacts-dir", str(artifacts)]
        result = subprocess.run(command, cwd=REPO, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        # A hash file that simply omits an asset must not make that asset pass.
        checksum_file.write_text("".join(s for s in sums if "windows-arm64.msi" not in s))
        result = subprocess.run(command, cwd=REPO, text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing or duplicate checksum", result.stderr)
        checksum_file.write_text("".join(sums))
        (artifacts / "osate-cli-1.2.3-windows-x64.msi").unlink()
        result = subprocess.run(command, cwd=REPO, text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing release artifact", result.stderr)


if __name__ == "__main__":
    unittest.main()
