"""Read-only contact resolution and outgoing-message receipts for the local sender."""
from __future__ import annotations

from dataclasses import dataclass
from collections import Counter
import hashlib
from pathlib import Path
import sqlite3
import time

from wechat_contacts import (ContactReadError, ProcessMemory, contact_databases,
                             database_snapshot, verify_key, PAGE_SIZE)


class SendError(RuntimeError):
    pass


@dataclass(frozen=True)
class Recipient:
    username: str
    alias: str
    nickname: str
    remark: str

    @property
    def display_name(self):
        return self.remark or self.nickname or self.alias or self.username


def resolve_recipient(rows, query):
    query = query.strip()
    if query.endswith(')') and ' (' in query:
        query = query.rsplit(' (', 1)[1][:-1].strip()
    explicit = [row for row in rows if query in (row.username, row.alias) and query]
    matches = explicit or [row for row in rows if query in (row.remark, row.nickname) and query]
    if len(matches) != 1:
        raise SendError('联系人不存在或名称重复，请使用准确的微信号或 UserName。')
    return matches[0]


class MessageStore:
    def __init__(self, pid, cancelled=lambda: False):
        self.pid = pid
        self.cancelled = cancelled
        self.keys = {}
        self.message_paths = []
        self.own_username = ''
        self.contacts = []
        self.display_names = Counter()
        self.account_path = None
        self._databases = {}

    def prepare(self):
        contact_paths = contact_databases()
        paths = set(contact_paths)
        for path in contact_paths:
            paths.update((path.parents[1] / 'message').glob('message_[0-9]*.db'))
        headers = {}
        for path in paths:
            with path.open('rb') as source:
                headers[path] = source.read(PAGE_SIZE)
        memory = ProcessMemory(self.pid, cancelled=self.cancelled)
        try:
            for key in memory.key_candidates():
                for path, header in headers.items():
                    if path not in self.keys and verify_key(key, header):
                        self.keys[path] = key
        finally:
            memory.close()
        candidates = [path for path in contact_paths if path in self.keys]
        if len(candidates) != 1:
            raise SendError('无法唯一确认此微信窗口的账号数据库，请仅保留待发送账号的微信实例。')
        self.account_path = candidates[0].parents[1]
        database = self.open_database(candidates[0])
        rows = database.execute('SELECT username,alias,nick_name,remark,local_type,delete_flag FROM contact').fetchall()
        known = {row['username']: Recipient(row['username'], row['alias'] or '', row['nick_name'] or '', row['remark'] or '')
                 for row in rows if row['username'] and not row['delete_flag']}
        # Recent sessions can also contain nonfriends and official accounts with the same title.
        self.display_names = Counter(contact.display_name for contact in known.values())
        self.contacts = [Recipient(row['username'], row['alias'] or '', row['nick_name'] or '', row['remark'] or '')
                         for row in rows if row['username'] and not row['delete_flag']
                         and (row['local_type'] in (1, 5) or row['username'].endswith('@chatroom'))]
        folder = candidates[0].parents[2].name
        self_matches = [contact for contact in self.contacts if folder == contact.username
                        or folder.startswith(contact.username + '_')]
        if len(self_matches) != 1:
            raise SendError('无法确认当前账号的发送者标识。')
        self.own_username = self_matches[0].username
        self.message_paths = sorted(path for path in self.keys if path.parent == self.account_path / 'message')
        expected_paths = sorted((self.account_path / 'message').glob('message_[0-9]*.db'))
        if self.message_paths != expected_paths or not self.message_paths:
            raise SendError('未能验证当前账号的消息数据库，不能确认发送结果。')

    def resolve(self, query):
        person = resolve_recipient(self.contacts, query)
        if self.display_names[person.display_name] != 1:
            raise SendError('联系人显示名称重复，无法可靠核对聊天对象，已跳过。')
        return person

    @staticmethod
    def _stamp(path):
        main = path.stat()
        try:
            wal = Path(str(path) + '-wal').stat()
            return main.st_size, main.st_mtime_ns, wal.st_size, wal.st_mtime_ns
        except FileNotFoundError:
            return main.st_size, main.st_mtime_ns, 0, 0

    def open_database(self, path):
        if self.cancelled():
            raise InterruptedError('cancelled')
        stamp = self._stamp(path)
        cached = self._databases.get(path)
        if cached and cached[0] == stamp:
            return cached[1]
        image = database_snapshot(path, self.keys[path], self.cancelled)
        database = sqlite3.connect(':memory:')
        database.deserialize(image)
        database.execute('PRAGMA query_only=ON')
        database.row_factory = sqlite3.Row
        if cached:
            cached[1].close()
        self._databases[path] = stamp, database
        return database

    @staticmethod
    def table_name(username):
        return 'Msg_' + hashlib.md5(username.encode('utf8')).hexdigest()

    def baseline(self, username):
        table = self.table_name(username)
        baseline = {}
        for path in self.message_paths:
            database = self.open_database(path)
            exists = database.execute('SELECT 1 FROM sqlite_master WHERE type=\'table\' AND name=?', (table,)).fetchone()
            baseline[path] = database.execute(f'SELECT COALESCE(MAX(local_id),0) FROM "{table}"').fetchone()[0] if exists else 0
        return baseline

    def outgoing(self, username, baseline, text=None, message_type=1):
        table = self.table_name(username)
        matches = []
        for path in self.message_paths:
            database = self.open_database(path)
            exists = database.execute('SELECT 1 FROM sqlite_master WHERE type=\'table\' AND name=?', (table,)).fetchone()
            if not exists:
                continue
            columns = {row[1] for row in database.execute(f'PRAGMA table_info("{table}")')}
            if not {'local_id', 'real_sender_id', 'local_type', 'message_content', 'server_id', 'status', 'origin_source'}.issubset(columns):
                raise SendError('消息表结构不支持发送核验。')
            rows = database.execute(f'SELECT m.local_id,m.server_id,m.status,m.local_type,m.message_content '
                                    f'FROM "{table}" m JOIN Name2Id n ON m.real_sender_id=n.rowid '
                                    'WHERE m.local_id>? AND n.user_name=? AND m.origin_source=1 ORDER BY m.local_id',
                                    (baseline.get(path, 0), self.own_username))
            for row in rows:
                if row['local_type'] & 0xffffffff != message_type:
                    continue
                content = row['message_content']
                if isinstance(content, bytes):
                    try:
                        content = content.decode('utf8')
                    except UnicodeError:
                        content = None
                if text is not None and content != text:
                    continue
                matches.append({'local_id': row['local_id'], 'server_id': row['server_id'],
                                'status': row['status'], 'shard': path.name})
        return matches

    def wait_receipt(self, username, baseline, text=None, message_type=1, timeout=20):
        deadline = time.monotonic() + timeout
        latest = []
        while time.monotonic() < deadline:
            if self.cancelled():
                raise InterruptedError('cancelled')
            latest = self.outgoing(username, baseline, text, message_type)
            acknowledged = [record for record in latest if (record['server_id'] or 0) > 0 and record['status'] == 2]
            if len(latest) > 1:
                return {'outcome': 'uncertain', 'reason': '检测到多个匹配的新发送记录，未自动重复发送。'}
            if len(acknowledged) == 1:
                return {'outcome': 'acknowledged', **acknowledged[0]}
            time.sleep(.5)
        if latest:
            return {'outcome': 'pending', **latest[0]}
        return {'outcome': 'uncertain', 'reason': '未找到目标会话的新发送记录，未自动重复发送。'}

    def close(self):
        for _, database in self._databases.values():
            database.close()
        self._databases.clear()
        self.keys.clear()
