"""Build a checked submission archive from tracked source and fresh artifacts."""
import io
import json
import re
import subprocess
import tempfile
from pathlib import Path, PurePosixPath
from zipfile import ZipFile, ZIP_DEFLATED

SOURCE_ROOTS = {"backend", "web", "extension", "scripts", "samples", "docs", ".github"}
ROOT_FILES = {"README.md", "AGENTS.md", ".gitignore", ".gitattributes", ".env.example"}
FORBIDDEN_PARTS = {
    "node_modules", "target", "dist", ".git", ".cache", ".tools", "logs",
    ".playwright-mcp", "validation-results", "user-workspaces",
}
FORBIDDEN_NAMES = {"runtime.json", "credentials.json", "application.properties.local"}
SECRET_JSON_KEYS = {"private_key", "private_key_id", "client_secret", "refresh_token"}
MAX_ENTRY_BYTES = 128 * 1024 * 1024
SECRET_PATTERN = re.compile(
    rb"AIza[0-9A-Za-z_-]{35}|"
    rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\r\n]+"
    rb"[A-Za-z0-9+/=\r\n]{32,}"
    rb"-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
)


def contains_credentials(node):
    """別名・入れ子のJSONでも認証情報の構造を判定する。"""
    if isinstance(node, dict):
        return bool(SECRET_JSON_KEYS.intersection(node)) or any(
            contains_credentials(item) for item in node.values()
        )
    return isinstance(node, list) and any(
        contains_credentials(item) for item in node
    )


def check_env_example(data):
    """設定例はコメントと空欄だけ許可し、実値を例外へ含めない。"""
    for line in data.decode("utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line or line.split("=", 1)[1].strip():
            raise ValueError("NONEMPTY_ENV_EXAMPLE")


def check_entry(name, data):
    """Reject dangerous paths and credential-shaped files without revealing values."""
    path = PurePosixPath(name)
    parts = [part.lower() for part in path.parts]
    if path.is_absolute() or ".." in parts or "\\" in name or ":" in name:
        raise ValueError("UNSAFE_ARCHIVE_PATH")
    leaf = parts[-1] if parts else ""
    if any(part in FORBIDDEN_PARTS for part in parts) or leaf in FORBIDDEN_NAMES:
        raise ValueError("FORBIDDEN_ARCHIVE_ENTRY")
    if (leaf.startswith(".env") and leaf != ".env.example") or leaf.endswith(
        (".pem", ".key", ".p12", ".pfx", ".log")
    ):
        raise ValueError("FORBIDDEN_ARCHIVE_ENTRY")
    if leaf.endswith(".json") and any(
        word in leaf for word in ("credential", "service-account", "service_account")
    ):
        raise ValueError("FORBIDDEN_ARCHIVE_ENTRY")
    if leaf == ".env.example":
        check_env_example(data)
    if leaf.endswith(".json"):
        try:
            value = json.loads(data)
        except (ValueError, UnicodeError):
            raise ValueError("INVALID_ARCHIVE_JSON") from None
        if contains_credentials(value):
            raise ValueError("CREDENTIAL_JSON_IN_ARCHIVE")
    if SECRET_PATTERN.search(data):
        raise ValueError("SECRET_PATTERN_IN_ARCHIVE")


def inspect_artifact(data, kind):
    """Inspect the app's own packaged files; dependency binaries stay untouched."""
    with ZipFile(io.BytesIO(data)) as archive:
        names = set(archive.namelist())
        required = (
            "BOOT-INF/classes/jp/cheapreview/Application.class"
            if kind == "jar"
            else "extension/package.json"
        )
        if required not in names:
            raise ValueError("INVALID_BUILD_ARTIFACT")
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            if entry.file_size > MAX_ENTRY_BYTES:
                raise ValueError("OVERSIZED_ARCHIVE_ENTRY")
            # VSIXの既知のコンパイル済み出力だけ許可する。他のdistは拒否する。
            name = entry.filename
            if kind == "vsix" and name.startswith("extension/dist/"):
                name = name.replace("extension/dist/", "extension/compiled/", 1)
            check_entry(name, archive.read(entry))


def source_entries(source_zip):
    with ZipFile(io.BytesIO(source_zip)) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            name = entry.filename
            # 許可リスト外も検査し、tracked秘密があれば黙って省かず中止する。
            if entry.file_size > MAX_ENTRY_BYTES:
                raise ValueError("OVERSIZED_ARCHIVE_ENTRY")
            data = archive.read(entry)
            check_entry(name, data)
            path = PurePosixPath(name)
            allowed_source = (
                path.parts[0] in SOURCE_ROOTS
                and not name.lower().endswith((".docx", ".doc"))
            )
            if name in ROOT_FILES or allowed_source:
                yield name, data


def create_archive(root, destination):
    """Publish only a complete, validated ZIP, never a partially appended archive."""
    source = subprocess.run(
        ["git", "archive", "--format=zip", "HEAD"],
        cwd=root, check=True, capture_output=True,
    ).stdout
    entries = list(source_entries(source))
    for kind in ("jar", "vsix"):
        name = f"cheapreview-0.1.0.{kind}"
        data = (root / "dist" / name).read_bytes()
        inspect_artifact(data, kind)
        entries.append((f"dist/{name}", data))
    commit = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=root, check=True, capture_output=True, text=True,
    ).stdout.strip()
    manifest = {
        "sourceCommit": commit,
        "files": sorted(name for name, _ in entries),
        "verification": (
            "build.ps1 must succeed immediately before packaging; "
            "real browser/API acceptance is separate"
        ),
    }
    entries.append(("distribution-manifest.json", json.dumps(manifest, indent=2).encode()))
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        dir=destination.parent, suffix=".zip", delete=False,
    ) as temporary:
        staging = Path(temporary.name)
    try:
        with ZipFile(staging, "w", ZIP_DEFLATED) as archive:
            for name, data in sorted(entries):
                archive.writestr(name, data)
        with ZipFile(staging) as archive:
            if archive.testzip() is not None:
                raise ValueError("CORRUPT_DISTRIBUTION")
        staging.replace(destination)
    finally:
        staging.unlink(missing_ok=True)


def main():
    root = Path(__file__).resolve().parent.parent
    try:
        create_archive(root, root / "dist" / "cheapreview-source.zip")
    except Exception:
        print("PACKAGING_FAILED: inspect tracked source and build artifacts; no secret values are printed")
        return 1
    print("PACKAGING_CONFIRMED: dist/cheapreview-source.zip (tracked source, jar, VSIX, manifest)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
