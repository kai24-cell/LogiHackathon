import io
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from zipfile import ZipFile

from package_distribution import (
    check_entry,
    create_archive,
    inspect_artifact,
    source_entries,
)


def zipped(entries):
    output = io.BytesIO()
    with ZipFile(output, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return output.getvalue()


class PackagingSafetyTest(unittest.TestCase):
    def test_private_runtime_and_dependencies_are_rejected(self):
        for path in (
            ".env",
            ".env.demo",
            "config/runtime.json",
            "credentials.json",
            "service-account.json",
            "private.pem",
            "web/node_modules/pkg/index.js",
            ".playwright-mcp/browser-state.json",
            "docs/validation-results/answer.json",
            "samples/user-workspaces/Application.java",
            "../outside.txt",
            "C:/outside.txt",
        ):
            with self.subTest(path=path), self.assertRaises(ValueError):
                check_entry(path, b"not-read-from-user-files")

    def test_env_example_must_have_only_blank_values(self):
        check_entry(".env.example", b"# Local configuration\nMODEL=\nKEY=\n")
        for contents in (b"KEY=fixture-secret", b"invalid-line"):
            with self.assertRaisesRegex(ValueError, "NONEMPTY_ENV_EXAMPLE"):
                check_entry(".env.example", contents)

    def test_renamed_and_nested_credential_json_is_rejected(self):
        for contents in (
            {"type": "service_account", "private_key": "fake"},
            {"nested": [{"refresh_token": "fake"}]},
        ):
            with self.assertRaisesRegex(ValueError, "CREDENTIAL_JSON"):
                check_entry("backend/config/renamed.json", json.dumps(contents).encode())

    def test_api_key_pattern_is_rejected_without_reporting_value(self):
        value = b"AIza" + b"a" * 35
        with self.assertRaises(ValueError) as caught:
            check_entry("backend/config/example.properties", value)
        self.assertNotIn(value.decode(), str(caught.exception))

    def test_tracked_secret_aborts_even_outside_allowlist(self):
        with self.assertRaises(ValueError):
            list(source_entries(zipped({"private/.env": "fixture"})))

    def test_distribution_allowlist_excludes_editor_configuration_and_word(self):
        fixture = {
            "README.md": "guide",
            "docs/guide.md": "guide",
            "docs/design.docx": "binary",
            ".vscode/settings.json": "{}",
            "samples/example.java": "class Example {}",
            "samples/api-validation/case.json": "{}",
            "backend/src/main/resources/demo/answer.json": "{}",
        }
        entries = list(source_entries(zipped(fixture)))
        self.assertEqual(
            {name for name, _ in entries},
            {
                "README.md", "docs/guide.md", "samples/example.java",
                "samples/api-validation/case.json",
                "backend/src/main/resources/demo/answer.json",
            },
        )

    def test_artifact_inspection_rejects_embedded_credentials(self):
        for kind, required in (
            ("jar", "BOOT-INF/classes/jp/cheapreview/Application.class"),
            ("vsix", "extension/package.json"),
        ):
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                inspect_artifact(
                    zipped({required: "{}", "hidden/runtime.json": "{}"}), kind,
                )

    def test_expected_compiled_artifacts_pass_and_wrong_format_fails(self):
        inspect_artifact(
            zipped({
                "extension/package.json": "{}",
                "extension/dist/extension.js": "compiled",
            }),
            "vsix",
        )
        inspect_artifact(
            zipped({"BOOT-INF/classes/jp/cheapreview/Application.class": "compiled"}),
            "jar",
        )
        with self.assertRaises(ValueError):
            inspect_artifact(zipped({"other.txt": "not-a-built-app"}), "jar")

    def test_complete_zip_manifest_and_failed_replacement(self):
        # 実キー・実成果物を使わず、最終ZIPの内容と失敗時の保持を確認する。
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "dist").mkdir()
            (root / "dist/cheapreview-0.1.0.jar").write_bytes(zipped({
                "BOOT-INF/classes/jp/cheapreview/Application.class": "compiled",
            }))
            (root / "dist/cheapreview-0.1.0.vsix").write_bytes(zipped({
                "extension/package.json": "{}",
                "extension/dist/extension.js": "compiled",
            }))
            source = zipped({"README.md": "guide", ".vscode/settings.json": "{}"})
            replies = [
                SimpleNamespace(stdout=source),
                SimpleNamespace(stdout="fake-commit"),
            ]
            destination = root / "dist/distribution.zip"
            with patch("package_distribution.subprocess.run", side_effect=replies):
                create_archive(root, destination)
            original = destination.read_bytes()
            with ZipFile(destination) as archive:
                self.assertEqual(set(archive.namelist()), {
                    "README.md",
                    "dist/cheapreview-0.1.0.jar",
                    "dist/cheapreview-0.1.0.vsix",
                    "distribution-manifest.json",
                })
                manifest = json.loads(archive.read("distribution-manifest.json"))
                self.assertEqual(manifest["sourceCommit"], "fake-commit")
                self.assertEqual(set(manifest["files"]), set(archive.namelist()) - {
                    "distribution-manifest.json",
                })

            bad_source = zipped({"private/.env": "fake-secret"})
            with patch(
                "package_distribution.subprocess.run",
                return_value=SimpleNamespace(stdout=bad_source),
            ):
                with self.assertRaises(ValueError):
                    create_archive(root, destination)
            self.assertEqual(destination.read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
