"""Seed/check public stale-output canaries around the normal Windows build."""
import argparse
from pathlib import Path
from zipfile import ZipFile

CANARY = "cheapreview-stale-canary"
GENERATED_FILES = (
    f"web/dist/{CANARY}.js",
    f"backend/src/main/resources/static/{CANARY}.js",
    f"backend/target/classes/jp/cheapreview/{CANARY}.class",
    f"extension/dist/{CANARY}.js",
)


def generated_path(root, relative):
    """生成先の実パスを確認し、junction等によるroot外書き込みを拒否する。"""
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError("UNSAFE_GENERATED_PATH")
    return path


def seed(root):
    """古い出力だけに公開fixtureを置く。既存ファイルは上書きしない。"""
    for relative in GENERATED_FILES:
        path = generated_path(root, relative)
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("x", encoding="utf-8") as output:
            output.write("public stale-output fixture; not a credential\n")


def check(root):
    """ビルド後の生成先と最終jar/VSIXに古い出力が残っていないことを確認する。"""
    for relative in GENERATED_FILES:
        if generated_path(root, relative).exists():
            raise ValueError("STALE_GENERATED_FILE")

    for suffix in ("jar", "vsix"):
        path = root / "dist" / f"cheapreview-0.1.0.{suffix}"
        with ZipFile(path) as archive:
            if any(CANARY in name for name in archive.namelist()):
                raise ValueError("STALE_ARTIFACT_ENTRY")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("seed", "check"))
    options = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    try:
        (seed if options.mode == "seed" else check)(root)
    except Exception:
        print("STALE_OUTPUT_CHECK_FAILED: generated paths or artifact entries")
        return 1
    print("STALE_OUTPUT_" + options.mode.upper() + "_CONFIRMED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
