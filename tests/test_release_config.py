import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
from release_config import identity, trusted_main


class ReleaseConfigTest(unittest.TestCase):
    def test_versions_and_bounds(self):
        sha = 'a' * 40
        self.assertEqual(identity(sha, '1'), (4, '1.2+sha.' + sha))
        self.assertEqual(identity(sha, '2099999997')[0], 2100000000)
        self.assertGreater(identity(sha, 2)[0], identity('b' * 40, 1)[0])
        for run in ('0', '-1', '1.0', '2099999998'):
            with self.assertRaises(ValueError):
                identity(sha, run)
        for invalid in ('a' * 39, 'A' * 40, '../main', 'g' * 40):
            with self.assertRaises(ValueError):
                identity(invalid, 1)

    def test_release_conditions(self):
        for event in ('push', 'pull_request', 'workflow_run', 'workflow_dispatch'):
            for ref in ('refs/heads/main', 'refs/heads/feature', 'refs/tags/main'):
                self.assertEqual(trusted_main(event, ref), event == 'push' and ref == 'refs/heads/main')

    def test_manifest_defaults_preserved(self):
        root = ET.parse(Path(__file__).resolve().parents[1] / 'AndroidManifest.xml').getroot()
        ns = '{http://schemas.android.com/apk/res/android}'
        self.assertEqual(root.get(ns + 'versionCode'), '3')
        self.assertEqual(root.get(ns + 'versionName'), '1.2')
        self.assertEqual(root.find('application').get(ns + 'debuggable'), 'true')


class BinaryVersionEvidenceTest(unittest.TestCase):
    def test_requested_identity_must_come_from_actual_package_badging(self):
        from verify_offline import binary_version_matches
        sha = 'a' * 40
        version = '1.2+sha.' + sha
        package = f"package: name='org.offline.screenmemory' versionCode='42' versionName='{version}' platformBuildVersionName='12'"
        self.assertTrue(binary_version_matches(package, 42, version))
        self.assertTrue(binary_version_matches(package + "\r\n", 42, version))
        for wrong in (
            package.replace("versionCode='42'", "versionCode='3'"),
            package.replace(version, '1.2'),
            package.replace('org.offline.screenmemory', 'other.package'),
            package.replace('package: ', 'RUN aapt2 '),
            package + '\n' + package,
            "package: name='org.offline.screenmemory' versionCode='42'\nversionName='" + version + "'",
        ):
            with self.subTest(evidence=wrong):
                self.assertFalse(binary_version_matches(wrong, 42, version))
        self.assertFalse(binary_version_matches(package + "\nversionCode='3' versionName='1.2'", 3, '1.2'))
        self.assertTrue(binary_version_matches("package: name='org.offline.screenmemory' versionCode='3' versionName='1.2'", 3, '1.2'))

    def test_build_replaces_tracked_manifest_versions_and_checks_badging(self):
        build = (Path(__file__).resolve().parents[1] / 'build.ps1').read_text()
        link = next(line for line in build.splitlines() if "@('link'" in line)
        self.assertIn("'--replace-version'", link)
        self.assertIn("'--version-code',([string]$VersionCode)", link)
        self.assertIn("'--version-name',$VersionName", link)
        self.assertIn("$badging=Run -Exe $aapt2", build)
        self.assertIn("[regex]::Escape([string]$VersionCode)", build)
        self.assertIn("[regex]::Escape($VersionName)", build)
        self.assertIn("throw 'APK version does not match requested identity'", build)


class WorkflowSafetyTest(unittest.TestCase):
    def test_single_build_sequence_and_premerge_release_signing(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/verify.yml').read_text()
        self.assertEqual(workflow.count('-File ./build.ps1'), 1)
        self.assertEqual(workflow.count('python ./verify_offline.py $apk'), 1)
        self.assertIn("'[\"Debug\",\"ReleaseTest\"]'", workflow)
        self.assertIn("'[\"Release\"]'", workflow)
        self.assertIn("-SigningMode $signingMode -VersionCode $identity[0] -VersionName $identity[1]", workflow)
        self.assertIn("-storepass:env RELEASE_KEYSTORE_PASSWORD -keypass:env RELEASE_KEY_PASSWORD", workflow)
        self.assertIn("} finally {\n            if (Test-Path $keyPath) { Remove-Item -LiteralPath $keyPath -Force }", workflow)
        self.assertNotIn('GITHUB_ENV', workflow)

    def test_secret_values_are_main_guarded_and_missing_main_secrets_have_no_dummy_fallback(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/verify.yml').read_text()
        secret_lines = [line for line in workflow.splitlines() if 'secrets.RELEASE_' in line]
        self.assertEqual(len(secret_lines), 4)
        for line in secret_lines:
            self.assertIn("github.event_name == 'push' && github.ref == 'refs/heads/main' && secrets.", line)
            self.assertTrue(line.endswith("|| '' }}"))
            if 'temporary-test-' in line:
                self.assertIn("(github.event_name != 'push' || github.ref != 'refs/heads/main') &&", line)
