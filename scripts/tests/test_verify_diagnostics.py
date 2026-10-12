import importlib.util
import io
from pathlib import Path
import threading
import unittest

spec = importlib.util.spec_from_file_location("verify_api", Path(__file__).resolve().parents[1] / "verify_api.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class DiagnosticReaderTest(unittest.TestCase):
    def test_only_fixed_diagnostic_lines_are_published(self):
        line = ("API_DIAGNOSTIC id=11111111-1111-1111-1111-111111111111 stage=GENERATE "
                "event=TIMEOUT at=2026-10-12T00:00:00Z elapsedMs=120000 "
                "httpStatus=200 timeoutType=RESPONSE_WAIT")
        output = []
        failed = threading.Event()
        verifier.consume_diagnostics(io.BytesIO(("secret-sentinel\n" + line + "\n" + line + " body=secret\n").encode()), output.append, failed)
        self.assertEqual([line], output)
        self.assertFalse(failed.is_set())

    def test_reader_failure_sets_fixed_state_without_exposing_exception(self):
        class BrokenStream:
            def __iter__(self):
                raise RuntimeError("secret-sentinel")
        failed = threading.Event()
        output = []
        verifier.consume_diagnostics(BrokenStream(), output.append, failed)
        self.assertTrue(failed.is_set())
        self.assertEqual([], output)


if __name__ == "__main__":
    unittest.main()
