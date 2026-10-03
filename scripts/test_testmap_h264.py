"""Host-only transport regressions; no devices, server, or real Confirm calls."""
import ast
import io
import json
from pathlib import Path
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from collections import deque
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, unquote, urlparse

import testmap_h264 as video


def producer():
    # Deliberately bypass __init__: no producer thread or subprocess can start.
    p = video._VideoProducer.__new__(video._VideoProducer)
    p._cv = threading.Condition()
    p._packets = deque()
    p._packet_bytes = 0
    p._sps = p._pps = None
    p._seq = p._subs = 0
    p._stop = threading.Event()
    return p


def nal(kind):
    return video.ANNEXB4 + bytes([kind]) + b'fixture'


class QueueTests(unittest.TestCase):
    def test_burst_keeps_parameter_key_and_delta_order_for_both_subscribers(self):
        p = producer()
        a, b = p.subscribe(), p.subscribe()
        next(a); next(b)
        for kind in [7, 8, 5, 1]:
            p._publish(nal(kind))
        expected = [video.pack_frame(nal(k)) for k in [7, 8, 5, 1]]
        self.assertEqual([next(a) for _ in range(4)], expected)
        self.assertEqual([next(b) for _ in range(4)], expected)
        a.close(); b.close()
        self.assertEqual(p.subscriber_count(), 0)

    def test_cancel_after_each_initial_yield_releases_subscription(self):
        for count in [1, 2, 3]:
            p = producer()
            p._publish(nal(7)); p._publish(nal(8))
            stream = p.subscribe()
            for _ in range(count):
                next(stream)
            stream.close()
            self.assertEqual(p.subscriber_count(), 0)

    def test_count_overflow_ends_only_lagging_subscriber(self):
        p = producer()
        slow, fast = p.subscribe(), p.subscribe()
        next(slow); next(fast)
        with patch.object(video, 'MAX_PENDING_PACKETS', 2):
            for kind in [7, 8, 5]:
                p._publish(nal(kind))
                self.assertEqual(next(fast), video.pack_frame(nal(kind)))
        self.assertEqual(list(slow), [])
        self.assertEqual(p.subscriber_count(), 1)
        fast.close()

    def test_byte_bound_ends_stream_instead_of_sending_gap(self):
        p = producer()
        stream = p.subscribe(); next(stream)
        with patch.object(video, 'MAX_PENDING_BYTES', 8):
            p._publish(nal(5))
        self.assertLessEqual(p._packet_bytes, 8)
        self.assertEqual(list(stream), [])
        self.assertEqual(p.subscriber_count(), 0)

    def test_cleanup_takes_only_owned_resources_once(self):
        p = producer()
        calls = []
        class Child:
            def poll(self): return None
            def send_signal(self, signal): calls.append(('signal', signal))
            def wait(self, timeout): calls.append(('wait', timeout))
        p._kids = [Child()]
        p._forwards = [('owned-device', 12345)]
        with patch.object(video, 'adb_bin', return_value='mock-adb'), \
             patch.object(video.subprocess, 'run') as remove:
            p._cleanup()
            p._cleanup()
            self.assertEqual(len(calls), 2)
            remove.assert_called_once_with(
                ['mock-adb', '-s', 'owned-device', 'forward', '--remove', 'tcp:12345'],
                capture_output=True, timeout=8, check=False)
        self.assertEqual(p._kids, [])
        self.assertEqual(p._forwards, [])

    def test_stop_wakes_waiting_subscriber(self):
        p = producer()
        stream = p.subscribe(); next(stream)
        done = threading.Event()
        def consume():
            self.assertEqual(list(stream), [])
            done.set()
        worker = threading.Thread(target=consume, daemon=True)
        worker.start()
        with p._cv:
            p._stop.set(); p._cv.notify_all()
        self.assertTrue(done.wait(2))
        worker.join()
        self.assertEqual(p.subscriber_count(), 0)


class FakeSocket:
    def __init__(self, path, fail_body=False):
        self.input = io.BytesIO(f'GET {path} HTTP/1.1\r\nHost: localhost\r\n\r\n'.encode())
        self.output = bytearray()
        self.fail_body = fail_body
    def makefile(self, *_):
        return self.input
    def sendall(self, data):
        if self.fail_body and b'\r\n\r\n' in self.output:
            raise BrokenPipeError('fixture disconnect')
        self.output.extend(data)


def handler_namespace():
    # Execute only the real Handler + pure gates. Module-level RunManager and
    # hub creation are excluded; every external endpoint dependency is mocked.
    source = Path(__file__).with_name('testmap_console.py').read_text()
    tree = ast.parse(source)
    selected = [n for n in tree.body if isinstance(n, (ast.ClassDef, ast.FunctionDef))
                and n.name in {'Handler', '_run_is_live', '_watch_preferred'}]
    ns = dict(BaseHTTPRequestHandler=BaseHTTPRequestHandler, json=json,
              parse_qs=parse_qs, unquote=unquote, urlparse=urlparse,
              MANAGER=SimpleNamespace(snapshot=lambda: {'state': 'running'}),
              resolve_watch_target=lambda *_: {'platform': 'android', 'id': 'mock'},
              BOUNDARY='fixture', multipart_part=lambda frame: b'--fixture\r\n'+frame)
    exec(compile(ast.Module(body=selected, type_ignores=[]), '<real Handler AST>', 'exec'), ns)
    ns['Handler'].log_message = lambda *_: None
    return ns


class HttpTests(unittest.TestCase):
    def test_http10_body_contains_only_application_packets_and_closes_iterator(self):
        ns = handler_namespace()
        closed = []
        packets = [video.pack_frame(video.config_payload()), video.pack_frame(nal(5))]
        def stream(*_):
            try:
                yield from packets
            finally:
                closed.append(True)
        ns['H264_HUB'] = SimpleNamespace(iter_packets=stream)
        sock = FakeSocket('/api/device-video?platform=android')
        ns['Handler'](sock, ('fixture', 0), None)
        head, body = bytes(sock.output).split(b'\r\n\r\n', 1)
        self.assertTrue(head.startswith(b'HTTP/1.0 200'))
        self.assertIn(b'Connection: close', head)
        self.assertNotIn(b'Transfer-Encoding', head)
        self.assertEqual(body, b''.join(packets))
        self.assertEqual(closed, [True])

    def test_disconnect_on_first_body_write_closes_subscription(self):
        ns = handler_namespace()
        p = producer()
        ns['H264_HUB'] = SimpleNamespace(iter_packets=lambda *_: p.subscribe())
        sock = FakeSocket('/api/device-video?platform=android', fail_body=True)
        ns['Handler'](sock, ('fixture', 0), None)
        self.assertEqual(p.subscriber_count(), 0)

    def test_mjpeg_sibling_still_emits_unchanged_multipart_body(self):
        ns = handler_namespace()
        ns['HUB'] = SimpleNamespace(iter_frames=lambda *_: iter([b'jpeg']))
        sock = FakeSocket('/api/device-stream?platform=android')
        ns['Handler'](sock, ('fixture', 0), None)
        head, body = bytes(sock.output).split(b'\r\n\r\n', 1)
        self.assertIn(b'multipart/x-mixed-replace; boundary=fixture', head)
        self.assertEqual(body, b'--fixture\r\njpeg')

    def test_run_start_failure_returns_json_instead_of_dropping_connection(self):
        ns = handler_namespace()
        ns['BusyError'] = type('BusyError', (Exception,), {})
        ns['StopForbiddenError'] = type('StopForbiddenError', (Exception,), {})
        def fail(*args, **kwargs):
            raise RuntimeError('runner did not report a run id')
        ns['MANAGER'] = SimpleNamespace(start=fail)
        replies = []
        request = SimpleNamespace(path='/api/run',
            _read_json=lambda: {'tasks': ['fixture']},
            _json=lambda code, body: replies.append((code, body)))
        ns['Handler'].do_POST(request)
        self.assertEqual(replies, [(500, {'error': 'runner did not report a run id'})])

    def test_inactive_run_never_starts_producer(self):
        ns = handler_namespace()
        ns['MANAGER'] = SimpleNamespace(snapshot=lambda: {'state': 'passed'})
        sock = FakeSocket('/api/device-video?platform=android')
        ns['Handler'](sock, ('fixture', 0), None)
        self.assertTrue(sock.output.startswith(b'HTTP/1.0 204'))


if __name__ == '__main__':
    unittest.main()
