"""Exercise the packaged local server without VS Code UI or any external AI API."""
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, ProxyHandler, build_opener

ROOT = Path(__file__).resolve().parent.parent
PORT = 8765
STARTUP_TIMEOUT_SECONDS = 30
SCAN_TIMEOUT_SECONDS = 70
POLL_INTERVAL_SECONDS = 0.2
BASE_URL = f'http://127.0.0.1:{PORT}'
opener = build_opener(ProxyHandler({}))


def main():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', PORT))
    token = secrets.token_hex(32)
    environment = os.environ.copy()
    environment['CHEAPREVIEW_CONNECTION_TOKEN'] = token
    environment['CHEAPREVIEW_DEV_ORIGIN'] = ''
    java = str(Path(environment['JAVA_HOME']) / 'bin' / 'java.exe')
    jar = ROOT / 'dist/cheapreview-0.1.0.jar'
    process = subprocess.Popen(
        [java, '-jar', str(jar)], cwd=ROOT, env=environment,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )

    def call(path, body=None, authenticated=True):
        headers = {'Content-Type': 'application/json'}
        if authenticated:
            headers['X-CheapReview-Token'] = token
        payload = None if body is None else json.dumps(body).encode('utf-8')
        request = Request(BASE_URL + path, data=payload, headers=headers)
        with opener.open(request, timeout=5) as response:
            raw = response.read()
            return None if not raw else json.loads(raw)

    try:
        deadline = time.monotonic() + STARTUP_TIMEOUT_SECONDS
        while True:
            if process.poll() is not None:
                raise RuntimeError('Server exited before becoming ready')
            try:
                assert call('/api/v1/health', authenticated=False)['status'] == 'UP'
                break
            except URLError:
                if time.monotonic() > deadline:
                    raise RuntimeError('Server startup timed out') from None
                time.sleep(POLL_INTERVAL_SECONDS)
        with opener.open(BASE_URL + '/', timeout=5) as response:
            assert '<div id="root">' in response.read().decode('utf-8')
        try:
            call('/api/v1/status', authenticated=False)
            raise AssertionError('Unauthenticated status was accepted')
        except HTTPError as error:
            assert error.code == 401
        bridge_id = call('/api/v1/bridge/register', {
            'extensionVersion': '0.1.0', 'windowId': 'smoke-test',
        })['bridgeId']
        request_id = call('/api/v1/bridge/requests', {'kind': 'FOLDER_PICK'})['requestId']
        claimed = call('/api/v1/bridge/requests/next?bridgeId=' + bridge_id)
        assert claimed['requestId'] == request_id
        assert call('/api/v1/bridge/requests/next?bridgeId=' + bridge_id) is None
        result = call('/api/v1/bridge/requests/' + request_id + '/result', {
            'bridgeId': bridge_id, 'status': 'completed',
            'payload': {'uri': (ROOT / 'samples/scan-demo').as_uri()},
        })
        workspace_id = result['result']['workspaceId']
        for include_tests, expected_count in [(False, 1), (True, 2)]:
            job_id = call('/api/v1/workspaces/' + workspace_id + '/scan', {
                'includeTests': include_tests,
            })['scanJobId']
            deadline = time.monotonic() + SCAN_TIMEOUT_SECONDS
            while True:
                state = call('/api/v1/jobs/' + job_id)['state']
                if state == 'SUCCEEDED':
                    break
                if state == 'FAILED' or time.monotonic() > deadline:
                    raise AssertionError('Scan failed or timed out')
                time.sleep(POLL_INTERVAL_SECONDS)
            snapshot = call('/api/v1/workspaces/' + workspace_id + '/files')
            assert len(snapshot['files']) == expected_count
            assert not snapshot['warnings']
            analysis = call(
                '/api/v1/workspaces/' + workspace_id + '/snapshots/'
                + snapshot['snapshotId'] + '/java-analysis'
            )
            assert analysis['snapshotId'] == snapshot['snapshotId']
            assert len(analysis['files']) == expected_count
            assert all(file['parseStatus'] == 'PARSED' for file in analysis['files'])
            assert all(file['types'] for file in analysis['files'])
            assert 'root' not in analysis
        print('PASS: packaged Web, health, token, bridge claim, folder result, scan, test option, Java analysis')
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


if __name__ == '__main__':
    main()
