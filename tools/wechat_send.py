"""Verified Weixin bulk sender. Receives one JSON plan through stdin, then stop commands."""
from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import threading
import time


def emit(event):
    print(json.dumps(event, ensure_ascii=False), flush=True)


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf8'); sys.stderr.reconfigure(encoding='utf8')
    if os.name != 'nt' or sys.version_info < (3, 11):
        emit({'type': 'error', 'error': '需要 Windows 和 64 位 Python 3.11+。'}); return 1
    request = json.loads(sys.stdin.readline())
    jobs = request.get('jobs', [])
    if not 1 <= len(jobs) <= 1000 or any(not isinstance(j.get('recipient'), str) or not isinstance(j.get('text'), str)
            or not j['recipient'].strip() or not j['text'] or len(j['text']) > 10000 for j in jobs):
        emit({'type': 'error', 'error': '发送计划无效：需要联系人和非空文本，每条最多 10,000 字符。'}); return 1
    interval = request.get('interval_ms', 2000)
    if not isinstance(interval, int) or not 500 <= interval <= 60000:
        emit({'type': 'error', 'error': '发送间隔应为 500–60,000 毫秒。'}); return 1
    stopped = threading.Event()
    def commands():
        for line in sys.stdin:
            if line.strip().lower() == 'stop': stopped.set(); break
    threading.Thread(target=commands, daemon=True, name='wechat-send-stop').start()
    missing = [package for module, package in [('uiautomation', 'uiautomation>=2.0'), ('cryptography', 'cryptography>=43')]
               if importlib.util.find_spec(module) is None]
    if missing:
        emit({'type': 'status', 'status': '正在准备微信控件和数据库读取依赖…'})
        result = subprocess.run([sys.executable, '-m', 'pip', 'install', '--disable-pip-version-check', *missing],
                                capture_output=True, timeout=180, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        if result.returncode:
            emit({'type': 'error', 'error': '微信发送依赖准备失败。'}); return 1
    from wechat_send_ui import WeixinUI
    from wechat_send_store import MessageStore
    ui, store = None, None
    confirmed = 0
    try:
        emit({'type': 'status', 'status': '正在验证微信控件和当前账号的发送记录…'})
        ui = WeixinUI(stopped.is_set); ui.prepare()
        store = MessageStore(ui.pid, stopped.is_set); store.prepare()
        for index, job in enumerate(jobs):
            if stopped.is_set(): break
            triggered = False
            try:
                person = store.resolve(job['recipient'])
                emit({'type': 'progress', 'index': index, 'phase': 'opening', 'status': '正在核对联系人…'})
                if request.get('dry_run'):
                    ui.open_chat(person)
                    emit({'type': 'result', 'index': index, 'outcome': 'dry_run', 'reason': '仅定位验证，未发送。'}); continue
                edit = ui.stage_text(person, job['text'])
                baseline = store.baseline(person.username)
                emit({'type': 'progress', 'index': index, 'phase': 'sending', 'status': '输入已核对，正在等待发送记录…'})
                # From this point a timeout/cancellation is uncertain; never automatically resend it.
                triggered = True
                ui.dispatch(edit, person, job['text'])
                receipt = store.wait_receipt(person.username, baseline, job['text'])
                if receipt['outcome'] == 'acknowledged': confirmed += 1
                emit({'type': 'result', 'index': index, **receipt})
            except InterruptedError:
                emit({'type': 'result', 'index': index, 'outcome': 'uncertain' if triggered else 'cancelled',
                      'reason': '发送触发后已停止，结果需要核验。' if triggered else '发送前已停止。'})
                break
            except Exception as error:
                emit({'type': 'result', 'index': index, 'outcome': 'uncertain' if triggered else 'failed', 'reason': str(error)})
            if index < len(jobs) - 1 and stopped.wait(interval / 1000): break
        emit({'type': 'complete', 'confirmed': confirmed, 'total': len(jobs), 'cancelled': stopped.is_set()})
        return 0
    except InterruptedError:
        emit({'type': 'complete', 'confirmed': confirmed, 'total': len(jobs), 'cancelled': True}); return 0
    except Exception as error:
        emit({'type': 'error', 'error': str(error)}); return 1
    finally:
        if store: store.close()
        if ui: ui.close()


if __name__ == '__main__': sys.exit(main())
