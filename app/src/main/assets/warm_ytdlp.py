"""Hamstr's warm yt-dlp: one Python that keeps yt-dlp imported between links.

Started as `python warm_ytdlp.py <yt-dlp zipapp>`. Reads one JSON request per line on stdin
and answers each with one JSON line on stdout, once it is done:

    {"id": 1, "args": ["-J", "URL"]}  ->  {"id": 1, "code": 0, "out": "...", "err": "..."}
    {"id": 2, "match": "URL"}         ->  {"id": 2, "code": 0, "out": "Youtube"}

`args` is a yt-dlp command line; `code`, `out` and `err` are what the yt-dlp executable would
have exited with and printed. `match` names the extractor yt-dlp would use, `Generic` when no
site extractor knows the link. Requests run side by side, each with its own stdout and
stderr. Closing stdin ends the process once the running requests are answered.
"""

import importlib
import io
import json
import os
import shutil
import sys
import threading
import traceback
import zipfile


def _unpacked(zipapp):
    """yt-dlp unpacked next to this script, where Python can keep its compiled bytecode.
    Imported straight from the zipapp, every process compiles every module again."""
    try:
        here = os.path.dirname(os.path.abspath(__file__))
        stat = os.stat(zipapp)
        name = f'yt-dlp-{stat.st_size}-{int(stat.st_mtime)}'
        target = os.path.join(here, name)
        if not os.path.isdir(target):
            partial = target + '.partial'
            shutil.rmtree(partial, ignore_errors=True)
            with zipfile.ZipFile(zipapp) as z:
                z.extractall(partial, [n for n in z.namelist() if n.startswith('yt_dlp/')])
            os.rename(partial, target)
        for old in os.listdir(here):
            if old.startswith('yt-dlp-') and old != name:
                shutil.rmtree(os.path.join(here, old), ignore_errors=True)
        return target
    except Exception:
        traceback.print_exc()
        return zipapp


sys.path.insert(0, _unpacked(sys.argv[1]))

_channel = sys.stdout
_channel_lock = threading.Lock()
_local = threading.local()


class _Route:
    """sys.stdout or sys.stderr for whichever request runs on this thread."""

    encoding = 'utf-8'
    errors = 'replace'

    def __init__(self, name):
        self._name = name

    def write(self, s):
        target = getattr(_local, self._name, None)
        # Output outside a request goes to the log, never into the answers.
        return (sys.__stderr__ if target is None else target).write(s)

    def flush(self):
        pass

    def isatty(self):
        return False


sys.stdout = _Route('out')
sys.stderr = _Route('err')

import yt_dlp  # noqa: E402
from yt_dlp.extractor import gen_extractor_classes  # noqa: E402
from yt_dlp.version import __version__  # noqa: E402


def _send(message):
    line = json.dumps(message) + '\n'
    with _channel_lock:
        _channel.write(line)
        _channel.flush()


def _match(url):
    for ie in gen_extractor_classes():
        if ie.ie_key() != 'Generic' and ie.suitable(url):
            return ie.ie_key()
    return 'Generic'


def _run(request):
    out, err = io.StringIO(), io.StringIO()
    _local.out, _local.err = out, err
    code = 0
    try:
        if 'match' in request:
            out.write(_match(request['match']))
        else:
            yt_dlp.main(request['args'])
    except SystemExit as e:
        if isinstance(e.code, str):
            err.write(e.code)
        code = e.code if isinstance(e.code, int) else int(e.code is not None)
    except BaseException:
        err.write(traceback.format_exc())
        code = 1
    finally:
        _local.out = _local.err = None
    _send({'id': request['id'], 'code': code, 'out': out.getvalue(), 'err': err.getvalue()})


def _preload():
    # Otherwise the first link pays for these: most links are for these sites, and the
    # first match compiles every extractor's URL pattern.
    for name in ('youtube', 'instagram', 'tiktok', 'twitter', 'generic'):
        try:
            importlib.import_module(f'yt_dlp.extractor.{name}')
        except Exception:
            pass
    _match('https://example.com/')


def main():
    _send({'ready': True, 'version': __version__})
    threading.Thread(target=_preload, daemon=True).start()
    requests = []
    for raw in sys.stdin.buffer:
        if raw.strip():
            requests.append(threading.Thread(target=_run, args=(json.loads(raw),), daemon=True))
            requests[-1].start()
            requests = [r for r in requests if r.is_alive()]
    for r in requests:
        r.join()
    os._exit(0)


main()
