"""central-portal.py の照会の失敗の扱いと、コマンド全体の締め切り（#934 の CodeRabbit の指摘）。"""
import contextlib
import http.client
import io
import unittest
import urllib.error
from unittest import mock

from hypothesis import given, settings, strategies as st

from central_portal_support import commands, portal


class FakeResponse:
    def __init__(self, status, read):
        self.status = status
        self.read = read

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


def truncated():
    raise http.client.IncompleteRead(b'{"deploymentState": "PUB', 40)


class HttpTest(unittest.TestCase):
    def test_a_body_cut_in_the_middle_is_a_failed_query(self):
        with mock.patch.object(commands.urllib.request, 'urlopen', return_value=FakeResponse(200, truncated)):
            self.assertEqual(commands.http('POST', 'http://127.0.0.1/x'), (None, b''))
        error = urllib.error.HTTPError('http://127.0.0.1/x', 503, 'x', {}, None)
        error.read = truncated
        with mock.patch.object(commands.urllib.request, 'urlopen', side_effect=error):
            self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (None, b''))

    def test_complete_answers_are_returned_as_they_are(self):
        with mock.patch.object(commands.urllib.request, 'urlopen', return_value=FakeResponse(200, lambda: b'ok')):
            self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (200, b'ok'))
        error = urllib.error.HTTPError('http://127.0.0.1/x', 404, 'x', {}, io.BytesIO(b'missing'))
        with mock.patch.object(commands.urllib.request, 'urlopen', side_effect=error):
            self.assertEqual(commands.http('GET', 'http://127.0.0.1/x'), (404, b'missing'))

    def test_a_truncated_status_answer_is_queried_again(self):
        # wait は切断を照会の失敗として数え、次の照会で PUBLISHED を読む
        answers = [FakeResponse(200, truncated), FakeResponse(200, lambda: b'{"deploymentState": "PUBLISHED"}')]
        with mock.patch.object(commands.urllib.request, 'urlopen', side_effect=answers), \
                mock.patch.object(commands, 'central_auth', return_value={}), contextlib.redirect_stdout(io.StringIO()):
            args = type('Args', (), {'id': 'x', 'attempts': 3, 'interval': 0})()
            self.assertEqual(commands.wait(args, sleep=lambda _: None), 0)


class DeadlineTest(unittest.TestCase):
    """照会に時間がかかっても、コマンドは --deadline 秒を超えて照会も待ちもしない。"""

    def run_command(self, command, args, seconds_per_query, answer):
        now = [0.0]
        timeouts = []

        def urlopen(request, timeout):
            timeouts.append(timeout)
            now[0] += min(seconds_per_query, timeout)
            if seconds_per_query > timeout:
                raise TimeoutError()
            return FakeResponse(*answer(request))

        def sleep(seconds):
            self.assertGreaterEqual(seconds, 0)
            now[0] += seconds

        with mock.patch.object(commands.urllib.request, 'urlopen', side_effect=urlopen), \
                mock.patch.object(commands, 'central_auth', return_value={}), contextlib.redirect_stdout(io.StringIO()):
            try:
                result = command(args, sleep=sleep, monotonic=lambda: now[0])
            except portal.Stop:
                result = 'stopped'
        return result, now[0], timeouts

    @settings(max_examples=60, deadline=None)
    @given(st.floats(0, 20), st.floats(0, 120), st.floats(1, 600))
    def test_confirm_stays_within_the_deadline(self, per_query, interval, limit):
        args = type('Args', (), {'version': '5.3.0', 'attempts': 1000, 'interval': interval, 'deadline': limit})()
        result, elapsed, timeouts = self.run_command(commands.confirm, args, per_query, lambda request: (404, lambda: b''))
        self.assertEqual(result, 'stopped')
        self.assertLessEqual(elapsed, limit + 1e-6)
        self.assertTrue(all(0 < timeout <= 60 for timeout in timeouts))

    @settings(max_examples=60, deadline=None)
    @given(st.floats(0, 20), st.floats(0, 120), st.floats(1, 600))
    def test_wait_stays_within_the_deadline(self, per_query, interval, limit):
        args = type('Args', (), {'id': 'x', 'attempts': 1000, 'interval': interval, 'deadline': limit})()
        pending = lambda request: (200, lambda: b'{"deploymentState": "PUBLISHING"}')
        result, elapsed, timeouts = self.run_command(commands.wait, args, per_query, pending)
        self.assertEqual(result, 'stopped')
        self.assertLessEqual(elapsed, limit + 1e-6)

    def test_an_answer_before_the_deadline_succeeds_at_once(self):
        args = type('Args', (), {'id': 'x', 'attempts': 1000, 'interval': 30, 'deadline': 3600})()
        published = lambda request: (200, lambda: b'{"deploymentState": "PUBLISHED"}')
        result, elapsed, _ = self.run_command(commands.wait, args, 5, published)
        self.assertEqual((result, elapsed), (0, 5))

    def test_listing_the_tags_stays_within_the_deadline(self):
        # precheck の git ls-remote も、残り時間でタイムアウトを切り、締め切りを過ぎたら動かさない（#934 の CodeRabbit の指摘）
        now = [0.0]
        calls = []

        def run(command, **kwargs):
            calls.append(kwargs['timeout'])
            return type('Result', (), {'returncode': 0, 'stdout': '', 'stderr': ''})()

        with mock.patch.object(commands.subprocess, 'run', side_effect=run):
            for left, expected in ((3600, 120), (30, 30), (0.5, 0.5)):
                with self.subTest(left=left):
                    commands.DEADLINE = commands.Deadline(left, lambda: now[0])
                    try:
                        commands.remote_tags('origin')
                    finally:
                        commands.DEADLINE = None
                    self.assertEqual(calls[-1], expected)
            commands.DEADLINE = commands.Deadline(0, lambda: now[0])
            try:
                with self.assertRaises(portal.Stop):
                    commands.remote_tags('origin')
            finally:
                commands.DEADLINE = None
        self.assertEqual(len(calls), 3)
        # 締め切りの外（関門の入力集めなど）では、今までどおり 120 秒
        with mock.patch.object(commands.subprocess, 'run', side_effect=run):
            commands.remote_tags('origin')
        self.assertEqual(calls[-1], 120)

    def test_the_default_deadline_is_sixty_minutes(self):
        parser_args = ['confirm', '--version', '5.3.0']
        with mock.patch.object(commands, 'confirm', side_effect=lambda args: args.deadline) as fake:
            fake.__name__ = 'confirm'
            self.assertEqual(commands.main(['central-portal.py'] + parser_args), 3600)


if __name__ == '__main__':
    unittest.main()
