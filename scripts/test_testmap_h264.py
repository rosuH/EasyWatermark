"""Host-only transport regressions; loopback HTTP only, no devices or Confirm calls."""
import ast
import io
import json
import socket
import struct
import time
from pathlib import Path
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

import testmap_h264 as video
import testmap_stream as mjpeg


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
    return video.ANNEXB4 + bytes([kind]) + (bytes.fromhex('42c032') if kind == 7 else b'fixture')


class QueueTests(unittest.TestCase):
    def test_android_server_receives_native_keyframe_interval(self):
        p = producer()
        p._forwards = []
        # Stop at the mocked spawn boundary: no subprocess/socket/device runs.
        with patch.object(video, 'scrcpy_server_jar', return_value=Path('/mock/scrcpy-server')), \
             patch.object(video, 'adb_bin', return_value='mock-adb'), \
             patch.object(video.subprocess, 'run', return_value=SimpleNamespace(returncode=0)), \
             patch.object(video.socket, 'socket') as socket_factory, \
             patch.object(video.subprocess, 'Popen', side_effect=RuntimeError('mock spawn boundary')) as spawn:
            socket_factory.return_value.getsockname.return_value = ('127.0.0.1', 12345)
            with self.assertRaisesRegex(RuntimeError, 'mock spawn boundary'):
                p._run_android('fixture-device')
            args = spawn.call_args.args[0]
        self.assertEqual(args[:4], ['mock-adb', '-s', 'fixture-device', 'shell'])
        options = args[4].split()
        self.assertIn('video_codec_options=i-frame-interval=1', options)
        self.assertIn('max_fps=30', options)
        self.assertIn('raw_stream=true', options)
        self.assertIn('control=false', options)

    def test_actual_sps_codec_for_both_annexb_start_codes_and_late_subscriber(self):
        # Real Android capture: video-packets.json, SPS header 000000016742c032.
        for prefix in [video.ANNEXB4, b'\x00\x00\x01']:
            sps = prefix + bytes.fromhex('6742c0328d68044012de5e42')
            self.assertEqual(video.avc_codec(sps), 'avc1.42C032')
            p = producer(); p._publish(sps)
            stream = p.subscribe()
            self.assertEqual(next(stream), video.pack_frame(video.config_payload(codec='avc1.42C032')))
            self.assertEqual(next(stream), video.pack_frame(sps))
            stream.close()
        self.assertIsNone(video.avc_codec(video.ANNEXB4 + bytes.fromhex('6742c0')))
        self.assertIsNone(video.avc_codec(nal(8)))

    def test_late_join_replays_latest_retained_idr_and_every_delta_then_live(self):
        p = producer()
        sps, pps = nal(7), nal(8)
        old_key, old_delta = nal(5) + b'old', nal(1) + b'old'
        key, first, second = nal(5) + b'new', nal(1) + b'one', nal(1) + b'two'
        for payload in [sps, pps, old_key, old_delta, key, first, second]:
            p._publish(payload)
        stream = p.subscribe()
        self.assertEqual(next(stream), video.pack_frame(video.config_payload(codec='avc1.42C032')))
        self.assertEqual(next(stream), video.pack_frame(sps))
        self.assertEqual(next(stream), video.pack_frame(pps))
        self.assertEqual([next(stream) for _ in range(3)],
                         [video.pack_frame(payload) for payload in [key, first, second]])
        live = nal(1) + b'live'
        p._publish(live)
        self.assertEqual(next(stream), video.pack_frame(live))
        stream.close()
        self.assertEqual(p.subscriber_count(), 0)

    def test_late_join_never_borrows_an_evicted_keyframe(self):
        p = producer()
        with patch.object(video, 'MAX_PENDING_PACKETS', 2):
            for payload in [nal(7), nal(8), nal(5), nal(1) + b'one', nal(1) + b'two']:
                p._publish(payload)
        stream = p.subscribe()
        for _ in range(3):
            next(stream)  # Current config/SPS/PPS only, no cached old IDR.
        new_key = nal(5) + b'new'
        p._publish(new_key)
        self.assertEqual(next(stream), video.pack_frame(new_key))
        stream.close()

    def test_late_join_does_not_pair_old_gop_with_new_parameters(self):
        for new_parameter in [video.ANNEXB4 + bytes.fromhex('6742c033'), nal(8) + b'new']:
            p = producer()
            for payload in [nal(7), nal(8), nal(5), nal(1), new_parameter, nal(1)]:
                p._publish(payload)
            stream = p.subscribe()
            for _ in range(3):
                next(stream)
            new_key = nal(5) + b'new-parameters'
            p._publish(new_key)
            self.assertEqual(next(stream), video.pack_frame(new_key))
            stream.close()

    def test_burst_keeps_parameter_key_and_delta_order_for_both_subscribers(self):
        p = producer()
        a, b = p.subscribe(), p.subscribe()
        next(a); next(b)
        for kind in [7, 8, 5, 1]:
            p._publish(nal(kind))
        expected = [video.pack_frame(video.config_payload(codec='avc1.42C032'))]
        expected += [video.pack_frame(nal(k)) for k in [7, 8, 5, 1]]
        self.assertEqual([next(a) for _ in expected], expected)
        self.assertEqual([next(b) for _ in expected], expected)
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
                if kind == 7:
                    self.assertEqual(next(fast), video.pack_frame(video.config_payload(codec='avc1.42C032')))
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

    def test_producer_ends_subscription_when_cleanup_fails(self):
        p = producer()
        p.target = {'platform': 'host-fixture'}
        stream = p.subscribe(); next(stream)
        with patch.object(p, '_cleanup', side_effect=OSError('fixture cleanup failure')):
            with self.assertRaisesRegex(OSError, 'fixture cleanup failure'):
                p._run()
        self.assertTrue(p._stop.is_set())
        self.assertEqual(list(stream), [])
        self.assertEqual(p.subscriber_count(), 0)

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
    def getsockopt(self, *_):
        return 0
    def settimeout(self, value):
        self.timeout = value
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
                and n.name in {'Handler', '_run_is_live', '_watch_preferred', '_stream_should_stop'}]
    ns = dict(BaseHTTPRequestHandler=BaseHTTPRequestHandler, json=json, socket=socket,
              time=time, STREAM_IDLE_TIMEOUT_S=12.0,
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
        def stream(*_, **kwargs):
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
        ns['H264_HUB'] = SimpleNamespace(iter_packets=lambda *_, **kwargs: p.subscribe(**kwargs))
        sock = FakeSocket('/api/device-video?platform=android', fail_body=True)
        ns['Handler'](sock, ('fixture', 0), None)
        self.assertEqual(p.subscriber_count(), 0)

    def test_mjpeg_sibling_still_emits_unchanged_multipart_body(self):
        ns = handler_namespace()
        closed = []
        def frames(*args, **kwargs):
            try:
                yield b'jpeg'
            finally:
                closed.append(True)
        ns['HUB'] = SimpleNamespace(iter_frames=frames)
        sock = FakeSocket('/api/device-stream?platform=android')
        ns['Handler'](sock, ('fixture', 0), None)
        head, body = bytes(sock.output).split(b'\r\n\r\n', 1)
        self.assertIn(b'multipart/x-mixed-replace; boundary=fixture', head)
        self.assertEqual(body, b'--fixture\r\njpeg')
        self.assertEqual(closed, [True])

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



class SilentHttpTests(unittest.TestCase):
    def check_silent_release(self, transport, ending):
        ns = handler_namespace()
        if ending in {'graceful', 'half-close', 'active-half-close', 'blocked-write'}:
            ns['STREAM_IDLE_TIMEOUT_S'] = 0.4
        state = {'state': 'running', 'id': 'owned-run'}
        ns['MANAGER'] = SimpleNamespace(snapshot=lambda: dict(state))
        if transport == 'video':
            p = producer()
            p._kids, p._forwards = [], []
            module, hub = video, video.H264Hub()
            ns['H264_HUB'] = hub
        else:
            p = mjpeg._Producer.__new__(mjpeg._Producer)
            p._cv = threading.Condition()
            p._frame = b'jpeg'
            p._seq = 1
            p._subs = 0
            p._stop = threading.Event()
            module, hub = mjpeg, mjpeg.StreamHub()
            ns['HUB'] = hub
        p._thread = SimpleNamespace(is_alive=lambda: True)
        hub._producers['android:mock'] = p
        target = patch.object(module, 'resolve_watch_target', return_value={'platform': 'android', 'id': 'mock'})
        target.start()
        # A second viewer must survive cancellation of this HTTP subscriber.
        other = p.subscribe()
        next(other)
        finished = threading.Event()
        write_entered = threading.Event()
        write_timeouts, send_buffers = [], []
        class Handler(ns['Handler']):
            def setup(self):
                super().setup()
                if ending == 'blocked-write':
                    self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
                    send_buffers.append(self.connection.getsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF))
                    write = self.wfile.write
                    def observed_write(data):
                        if len(data) > 1024 * 1024:
                            write_entered.set()
                        try:
                            return write(data)  # Real socket sendall; never inject an error.
                        except TimeoutError as exc:
                            write_timeouts.append(exc)
                            raise
                    self.wfile.write = observed_write
            def finish(self):
                try:
                    super().finish()
                finally:
                    finished.set()
        # Local transport tests must not wait for the host's reverse DNS resolver.
        with patch.object(socket, 'getfqdn', return_value='localhost'):
            server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.05), daemon=True)
        worker.start()
        client = socket.socket()
        client.settimeout(2)
        if ending == 'blocked-write':
            client.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
        client.connect(server.server_address)
        try:
            client.sendall(f'GET /api/device-{transport}?platform=android HTTP/1.0\r\nHost: localhost\r\n\r\n'.encode())
            received = b''
            while b'\r\n\r\n' not in received or not received.split(b'\r\n\r\n', 1)[1]:
                chunk = client.recv(65536)
                self.assertTrue(chunk, 'HTTP stream ended before its first body packet')
                received += chunk
            self.assertEqual(p.subscriber_count(), 2)
            if ending == 'blocked-write':
                payload = nal(1) + b'x' * (4 * 1024 * 1024)
                self.assertLess(send_buffers[0], len(payload) // 4)
                self.assertLess(client.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF), len(payload) // 4)
                p._publish(payload)
                # Keep the client open without another recv until handler cleanup.
                self.assertTrue(write_entered.wait(2), 'large body write never started')
                self.assertFalse(finished.wait(0.05), 'write did not encounter real backpressure')
            elif ending == 'reset':
                client.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
                client.close()
            elif ending == 'graceful':
                client.close()
            elif ending in {'half-close', 'active-half-close'}:
                client.shutdown(socket.SHUT_WR)
                if ending == 'active-half-close':
                    for _ in range(6):
                        p._publish(nal(1) if transport == 'video' else b'jpeg-next')
                        self.assertTrue(client.recv(65536))
                        self.assertFalse(finished.wait(0.15), 'request half-close was mistaken for abort')
                    self.assertEqual(p.subscriber_count(), 2)
                    state['state'] = 'passed'
            elif ending == 'terminal':
                state['state'] = 'passed'
            else:
                state['id'] = 'replacement-run'
            self.assertTrue(finished.wait(2.5), f'{transport}/{ending}: silent handler did not release')
            if ending == 'blocked-write':
                self.assertEqual(len(write_timeouts), 1, 'release was not a real write timeout')
                self.assertGreaterEqual(client.fileno(), 0, 'client was closed before release')
            self.assertEqual(p.subscriber_count(), 1)
            hub.reap_idle()
            self.assertIn('android:mock', hub._producers)
            self.assertFalse(p._stop.is_set(), 'other viewer was stopped')
        finally:
            if ending == 'blocked-write':
                # Only after assertions: abort the intentionally unread test socket
                # so a failing pre-fix handler cannot hold server_close forever.
                client.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
            client.close()
            p._stop.set()
            with p._cv:
                p._cv.notify_all()
            finished.wait(2)
            other.close()
            server.shutdown()
            server.server_close()
            worker.join(2)
            target.stop()
        self.assertEqual(p.subscriber_count(), 0)
        hub.reap_idle()
        self.assertEqual(hub._producers, {})

    def test_silent_client_reset_releases_only_its_subscription(self):
        for transport in ('video', 'stream'):
            with self.subTest(transport=transport):
                self.check_silent_release(transport, 'reset')

    def test_silent_graceful_close_and_half_close_have_bounded_idle_release(self):
        for transport in ('video', 'stream'):
            for ending in ('graceful', 'half-close'):
                with self.subTest(transport=transport, ending=ending):
                    self.check_silent_release(transport, ending)

    def test_request_half_close_does_not_interrupt_continuing_frames(self):
        for transport in ('video', 'stream'):
            with self.subTest(transport=transport):
                self.check_silent_release(transport, 'active-half-close')

    def test_silent_run_end_or_replacement_releases_subscription(self):
        for transport in ('video', 'stream'):
            for ending in ('terminal', 'replacement'):
                with self.subTest(transport=transport, ending=ending):
                    self.check_silent_release(transport, ending)

    def test_nonreading_client_write_timeout_releases_only_its_subscription(self):
        for transport in ('video', 'stream'):
            with self.subTest(transport=transport):
                self.check_silent_release(transport, 'blocked-write')

if __name__ == '__main__':
    unittest.main()
