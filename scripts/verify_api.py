"""専用サンプルを通常の生成経路で検証する。秘密や応答本文は出力しない。"""

import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener
import uuid
import re
import threading

ROOT = Path(__file__).resolve().parent.parent
SAFE_CODES = {
    "INVALID_DEMO_CONFIG", "INVALID_DEMO_MODEL", "INVALID_DEMO_KEY", "DEMO_NOT_CONFIGURED", "GEMINI_AUTH_FAILED",
    "GEMINI_RATE_LIMITED", "MODEL_UNSUPPORTED", "MODEL_LIMIT_EXCEEDED",
    "GEMINI_UNAVAILABLE", "GENERATION_TIMEOUT", "OUTPUT_LIMIT", "INVALID_ANSWER",
    "GEMINI_BLOCKED", "BUDGET_EXCEEDED", "STALE_PREVIEW", "KEY_NOT_CONFIGURED",
    "MODEL_NOT_FOUND", "GEMINI_INVALID_REQUEST", "GEMINI_PRECONDITION_FAILED",
    "GEMINI_QUOTA_EXCEEDED", "GEMINI_BILLING_REQUIRED", "GEMINI_SERVER_ERROR",
}


class VerificationFailure(Exception):
    pass


def consume_diagnostics(stream, emit, failed):
    """定型ログのみ公開し、読取障害の生例外をスレッド外へ漏らさない。"""
    pattern = re.compile(r"API_DIAGNOSTIC id=[0-9a-f-]{36} stage=(MODEL_INFO|GENERATE|JOB) "
                         r"event=(START|HEADERS|TIMEOUT|END) at=[0-9TZ:.+-]+ elapsedMs=[0-9]+ "
                         r"httpStatus=(null|[1-5][0-9]{2}) timeoutType=(NONE|CONNECT|RESPONSE_WAIT|JOB_DEADLINE)")
    try:
        for line in stream:
            text = line.decode("utf-8", errors="replace").strip()
            if pattern.fullmatch(text):
                emit(text)
    except Exception:
        failed.set()


def wait_for(read, complete, timeout):
    """状態取得だけを繰り返す。生成要求は再送しない。"""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = read()
        if complete(state):
            return state
        time.sleep(0.2)
    raise VerificationFailure("LOCAL_TIMEOUT")


def verify(args):
    if args.real and (not args.allow_paid_api or args.scenario != "normal"):
        raise VerificationFailure("EXPLICIT_PAID_API_FLAG_REQUIRED")
    if args.allow_paid_api and not args.real:
        raise VerificationFailure("INVALID_FLAGS")
    jar = Path(args.jar).resolve()
    if not jar.is_file():
        raise VerificationFailure("BUILD_REQUIRED")
    if args.real and not (ROOT / ".env").is_file():
        raise VerificationFailure("DEMO_NOT_CONFIGURED")
    if not 1 <= args.port <= 65535:
        raise VerificationFailure("INVALID_PORT")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", args.port))

    token = secrets.token_hex(32)
    environment = os.environ.copy()
    environment["CHEAPREVIEW_CONNECTION_TOKEN"] = token
    environment["CHEAPREVIEW_DEV_ORIGIN"] = ""
    java = str(Path(environment["JAVA_HOME"]) / "bin/java.exe")
    command = [java, "-Djdk.httpclient.disableRetryConnect=true", "-jar", str(jar), f"--server.port={args.port}",
               "--logging.level.root=OFF", "--spring.mvc.log-request-details=false",
               "--logging.level.jp.cheapreview.generation.service.GenerationDiagnostics=INFO",
               "--logging.pattern.console=%msg%n"]
    if args.real:
        command += ["--cheapreview.generation.provider=real",
                    f"--cheapreview.demo.config-path={ROOT / '.env'}"]
    else:
        command += ["--cheapreview.generation.provider=stub",
                    f"--cheapreview.stub.scenario={args.scenario}"]
    # 子プロセスの生ログを破棄し、APIキー・入力コード・応答本文を出力しない。
    process = subprocess.Popen(command, cwd=ROOT, env=environment,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               creationflags=subprocess.CREATE_NO_WINDOW)
    diagnostic_failed = threading.Event()
    diagnostics = threading.Thread(target=consume_diagnostics,
        args=(process.stdout, lambda text: print(text, flush=True), diagnostic_failed), daemon=True)
    diagnostics.start()
    opener = build_opener(ProxyHandler({}))

    def call(path, body=None, authenticated=True):
        headers = {"Content-Type": "application/json"}
        if authenticated:
            headers["X-CheapReview-Token"] = token
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = Request(f"http://127.0.0.1:{args.port}/api/v1" + path,
                          data=data, headers=headers)
        try:
            with opener.open(request, timeout=10) as response:
                raw = response.read(2_000_000)
                return json.loads(raw) if raw else None
        except HTTPError as error:
            try:
                code = json.loads(error.read(8192)).get("code")
            except Exception:
                code = None
            safe = code if code in SAFE_CODES else "LOCAL_HTTP_ERROR"
            raise VerificationFailure(f"HTTP_{error.code} {safe}") from None

    try:
        def ready():
            if process.poll() is not None:
                raise VerificationFailure("SERVER_START_FAILED")
            try:
                return call("/health", authenticated=False)["status"] == "UP"
            except URLError:
                return False

        wait_for(ready, bool, 45)
        configured = call("/settings/demo" if args.real else "/settings/stub", {})
        case = json.loads((ROOT / "samples/api-validation/case.json").read_text("utf-8"))
        bridge = call("/bridge/register", {"extensionVersion": "0.1.0", "windowId": "api-validation"})["bridgeId"]
        pick = call("/bridge/requests", {"kind": "FOLDER_PICK"})["requestId"]
        assert call("/bridge/requests/next?bridgeId=" + bridge)["requestId"] == pick
        picked = call(f"/bridge/requests/{pick}/result", {
            "bridgeId": bridge, "status": "completed",
            "payload": {"uri": (ROOT / "samples/api-validation").as_uri()},
        })
        workspace = picked["result"]["workspaceId"]
        scan = call(f"/workspaces/{workspace}/scan", {"includeTests": False})["scanJobId"]
        state = wait_for(lambda: call("/jobs/" + scan),
                         lambda job: job["state"] in {"SUCCEEDED", "FAILED"}, 80)
        assert state["state"] == "SUCCEEDED"
        snapshot = call(f"/workspaces/{workspace}/files")
        assert not snapshot["warnings"]
        files = {file["relativePath"]: file["fileId"] for file in snapshot["files"]}
        selected = [files[name] for name in case["selectedFiles"]]
        search = call(f"/workspaces/{workspace}/snapshots/{snapshot['snapshotId']}/code-search", {
            "question": case["question"], "mode": case["mode"], "selectedFileIds": selected,
        })
        assert set(case["expectedRelatedFiles"]) <= {
            candidate["relativePath"] for candidate in search["candidates"]}
        candidates = {candidate["relativePath"]: candidate for candidate in search["candidates"]}
        # 単なる候補一覧への存在だけでは、依存解析が壊れても検出できない。
        # このサンプルはServiceからRepositoryへの直接委譲なので1hopを期待する。
        for name in case["expectedRelatedFiles"]:
            candidate = candidates[name]
            if name in case["selectedFiles"]:
                assert candidate["primarySelected"] and candidate["dependencyDistance"] == 0
            else:
                assert candidate["dependencyDistance"] == 1
                assert candidate["score"]["dependencyContribution"] > 0
        input_data = {
            "workspaceId": workspace, "snapshotId": snapshot["snapshotId"],
            "question": case["question"], "mode": case["mode"], "selectedFileIds": selected,
            "inputBudgetTokens": case["inputBudgetTokens"], "modelId": configured["modelId"], "revision": 1,
        }
        preview = call("/analysis/preview", input_data)
        assert preview["canExecute"]
        assert set(case["expectedRelatedFiles"]) <= {
            chunk["relativePath"] for chunk in preview["selectedChunks"]}
        call(f"/analysis/preview/{preview['previewId']}/validate", input_data)
        # このPOSTは一度だけ。Geminiの再試行・別モデルへの切替も行わない。
        started = call("/analysis/jobs", {
            "previewId": preview["previewId"], "requestId": str(uuid.uuid4()),
            "settingsVersion": configured["version"], "input": input_data,
        })
        job = wait_for(lambda: call("/jobs/" + started["jobId"]),
                       lambda value: value["state"] in {"SUCCEEDED", "FAILED", "CANCELLED"}, 260)
        expected = {"invalid": "INVALID_ANSWER", "timeout": "GENERATION_TIMEOUT"}.get(args.scenario)
        if expected:
            assert job["state"] == "FAILED" and job["errorCode"] == expected
            print(f"STUB_CONFIRMED scenario={args.scenario} code={expected} generation_requests=1")
            return
        if job["state"] != "SUCCEEDED":
            code = job.get("errorCode")
            # enumも念のため許可リストを通す。生本文・model・キーは表示しない。
            details = job.get("errorDetails") or {}
            stage = details.get("stage")
            status = details.get("apiStatus")
            limit = details.get("limitType")
            http = details.get("httpStatus")
            timeout_type = details.get("timeoutType")
            elapsed = details.get("elapsedMs")
            if stage in {"MODEL_INFO", "GENERATE", "JOB"} and timeout_type in {"CONNECT", "RESPONSE_WAIT", "JOB_DEADLINE"}:
                print(f"stage={stage} timeoutType={timeout_type} elapsedMs={elapsed if type(elapsed) is int else 'UNKNOWN'}")
            if stage in {"MODEL_INFO", "GENERATE"} and type(http) is int and 100 <= http <= 599:
                statuses = {"INVALID_ARGUMENT", "FAILED_PRECONDITION", "PERMISSION_DENIED",
                            "UNAUTHENTICATED", "NOT_FOUND", "RESOURCE_EXHAUSTED", "UNAVAILABLE",
                            "INTERNAL", "DEADLINE_EXCEEDED", "UNIMPLEMENTED", "UNKNOWN"}
                print(f"stage={stage} HTTP={http} status={status if status in statuses else 'UNKNOWN'} "
                      f"limit={limit if limit in {'PER_MINUTE', 'PER_DAY', 'MULTIPLE'} else 'UNKNOWN'}")
            raise VerificationFailure(code if code in SAFE_CODES else "GENERATION_FAILED")
        result = job["result"]
        assert result["source"] == ("DEMO" if args.real else "STUB")
        assert result["answerEnglish"]["summary"] and result["references"]
        references = {reference["referenceId"] for reference in result["references"]}
        for section in result["answerEnglish"]["sections"]:
            assert set(section["referenceIds"]) <= references
        for finding in result["answerEnglish"]["findings"]:
            assert set(finding["referenceIds"]) <= references
        label = "REAL_API_CONFIRMED" if args.real else "STUB_CONFIRMED"
        print(f"{label} budget/schema/references=PASS generation_requests=1 translation=UNVERIFIED")
        print("usage=" + json.dumps(result["usage"]))
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        diagnostics.join(timeout=2)
        if diagnostic_failed.is_set() or diagnostics.is_alive():
            raise VerificationFailure("LOCAL_DIAGNOSTIC_ERROR")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--real", action="store_true")
    parser.add_argument("--allow-paid-api", action="store_true")
    parser.add_argument("--scenario", choices=["normal", "invalid", "timeout"], default="normal")
    parser.add_argument("--jar", default=str(ROOT / "dist/cheapreview-0.1.0.jar"))
    parser.add_argument("--port", type=int, default=18766)
    args = parser.parse_args()
    try:
        verify(args)
    except VerificationFailure as failure:
        print("VERIFICATION_FAILED " + str(failure))
        return 1
    except Exception:
        # 例外にはURLや本文が含まれ得るため、tracebackを出さない。
        print("VERIFICATION_FAILED LOCAL_VERIFICATION_ERROR")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
