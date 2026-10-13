"""One-click, read-only Weixin 4.x contacts export. UTF-8 JSON-lines protocol."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import subprocess
import sys
import threading


def emit(event):
    print(json.dumps(event, ensure_ascii=False), flush=True)


def dependencies():
    if importlib.util.find_spec('cryptography') is not None:
        return
    emit({'type': 'status', 'status': '正在安装本地数据库解密依赖…'})
    result = subprocess.run([sys.executable, '-m', 'pip', 'install', '--disable-pip-version-check', 'cryptography>=43'],
                            capture_output=True, timeout=180, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    if result.returncode != 0:
        raise RuntimeError('无法准备数据库读取依赖，请安装 cryptography 后重试。')


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description='只读读取本机已登录微信 4.x 的通讯录，不使用 OCR 或模拟点击。')
    parser.add_argument('--all-contacts', action='store_true', help='Include groups and public accounts stored in the contact table')
    args = parser.parse_args()
    stopped = threading.Event()
    def commands():
        for line in sys.stdin:
            if line.strip().lower() == 'stop':
                stopped.set()
                break
    threading.Thread(target=commands, name='wechat-stop-command', daemon=True).start()
    try:
        if os.name != 'nt' or sys.version_info < (3, 11):
            raise RuntimeError('自动读取需要 Windows 和 64 位 Python 3.11 或更新版本。')
        dependencies()
        from wechat_contacts import collect_contacts
        collect_contacts(emit, stopped.is_set, friends_only=not args.all_contacts)
        return 0
    except InterruptedError:
        emit({'type': 'cancelled', 'status': '读取已停止。'})
        return 0
    except Exception as error:
        emit({'type': 'error', 'error': str(error)})
        return 1


if __name__ == '__main__':
    sys.exit(main())
