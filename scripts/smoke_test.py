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
PORT = int(os.environ.get('CHEAPREVIEW_SMOKE_PORT', '8765'))
if not 1 <= PORT <= 65535:
    raise ValueError('Invalid smoke test port')
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
        [java, '-jar', str(jar), f'--server.port={PORT}'], cwd=ROOT, env=environment,
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
        settings = call('/api/v1/settings')
        assert not settings['configured']
        assert 'apiKey' not in settings
        assert call('/api/v1/analysis/jobs/active')['jobId'] is None
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
            search = call(
                '/api/v1/workspaces/' + workspace_id + '/snapshots/'
                + snapshot['snapshotId'] + '/code-search', {
                    'question': 'OrderServiceの処理を確認したい',
                    'selectedFileIds': [snapshot['files'][0]['fileId']],
                    'mode': 'SERVICE_REVIEW',
                }
            )
            assert search['snapshotId'] == snapshot['snapshotId']
            assert len(search['candidates']) == expected_count
            assert any(candidate['primarySelected'] for candidate in search['candidates'])
            for candidate in search['candidates']:
                score = candidate['score']
                assert abs(score['total'] - sum(score[key] for key in (
                    'cosineContribution', 'dependencyContribution', 'roleContribution'
                ))) < 1e-12
            budget_request = {
                'workspaceId': workspace_id, 'snapshotId': snapshot['snapshotId'],
                'question': 'OrderServiceの処理を確認したい',
                'selectedFileIds': [snapshot['files'][0]['fileId']],
                'mode': 'SERVICE_REVIEW', 'inputBudgetTokens': 8192,
                'modelId': 'unknown-model', 'revision': 1,
            }
            preview = call('/api/v1/analysis/preview', budget_request)
            assert preview['canExecute']
            assert preview['estimatedCost'] is None
            assert preview['minimumBudgetTokens'] == preview['counts']['tokens'] + preview['marginTokens']
            assert preview['selectedChunks'][0]['mandatory']
            call('/api/v1/analysis/preview/' + preview['previewId'] + '/validate', budget_request)
            changed = dict(budget_request, question='別の質問')
            try:
                call('/api/v1/analysis/preview/' + preview['previewId'] + '/validate', changed)
                raise AssertionError('Stale preview was accepted')
            except HTTPError as error:
                assert error.code == 409
            insufficient = dict(budget_request, inputBudgetTokens=1024)
            blocked = call('/api/v1/analysis/preview', insufficient)
            assert not blocked['canExecute']
            try:
                call('/api/v1/analysis/preview/' + blocked['previewId'] + '/validate', insufficient)
                raise AssertionError('Insufficient budget was accepted')
            except HTTPError as error:
                assert error.code == 422
        print('PASS: packaged Web, bridge, scan, Java analysis, code search, budget preview, stale rejection, budget rejection')
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


if __name__ == '__main__':
    main()
