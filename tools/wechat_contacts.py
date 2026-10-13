"""Read the logged-in Weixin user's local contact snapshot without UI input or OCR.

Database keys stay in memory. Only contact.db and its committed WAL pages are read;
the live database and the Weixin process are never modified.
"""
from __future__ import annotations

import ctypes
from ctypes import wintypes
import hashlib
import hmac
import os
from pathlib import Path
import re
import sqlite3
import struct
import time
import subprocess

PAGE_SIZE = 4096
RESERVED = 80
CIPHER_NAME = b'com.Tencent.WCDB.Config.Cipher'
# WCDB's in-memory configuration obfuscation material, not a database key.
CONFIG_MASK = bytes.fromhex('d2c7442458020000004889442450488b450048844c2448488944254048584c24')
KEY_LITERAL = re.compile(rb"[xX]'([0-9a-fA-F]{64}(?:[0-9a-fA-F]{32})?)'")
MAX_DATABASE_BYTES = 128 * 1024 * 1024


class ContactReadError(RuntimeError):
    pass


def contact_databases():
    roots = {Path.home() / 'Documents' / 'xwechat_files'}
    if os.name == 'nt':
        # Documents may be redirected to OneDrive or another drive.
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                    r'Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders') as node:
                documents = os.path.expandvars(winreg.QueryValueEx(node, 'Personal')[0])
                roots.add(Path(documents) / 'xwechat_files')
        except OSError:
            pass
    config = Path(os.environ.get('APPDATA', '')) / 'Tencent' / 'xwechat' / 'config'
    for file in config.glob('*.ini'):
        if file.stat().st_size > 64 * 1024:
            continue
        raw = file.read_bytes()
        for encoding in ('utf-8-sig', 'utf-16', 'gb18030'):
            try:
                text = raw.decode(encoding)
            except UnicodeError:
                continue
            for line in text.splitlines():
                value = line.strip().strip('"').split('=', 1)[-1].strip()
                path = Path(value)
                if path.is_absolute() and path.is_dir():
                    roots.add(path)
                    roots.add(path / 'xwechat_files')
    result = set()
    for root in roots:
        if root.is_dir():
            result.update(root.glob('*/db_storage/contact/contact.db'))
            direct = root / 'db_storage' / 'contact' / 'contact.db'
            if direct.is_file():
                result.add(direct)
    return sorted(result)


def verify_key(key, first_page):
    if len(key) != 32 or len(first_page) != PAGE_SIZE:
        return False
    salt = bytes(value ^ 0x3a for value in first_page[:16])
    mac_key = hashlib.pbkdf2_hmac('sha512', key, salt, 2, 32)
    digest = hmac.new(mac_key, first_page[16:PAGE_SIZE - 64] + struct.pack('<I', 1), hashlib.sha512).digest()
    return hmac.compare_digest(digest, first_page[-64:])


class MemoryInfo(ctypes.Structure):
    _fields_ = [('base', ctypes.c_void_p), ('allocation', ctypes.c_void_p), ('allocation_protect', wintypes.DWORD),
                ('alignment', wintypes.DWORD), ('size', ctypes.c_size_t), ('state', wintypes.DWORD),
                ('protect', wintypes.DWORD), ('kind', wintypes.DWORD), ('padding', wintypes.DWORD)]


class ProcessMemory:
    def __init__(self, pid, timeout=60, cancelled=lambda: False):
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        self.kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        self.kernel.OpenProcess.restype = wintypes.HANDLE
        self.kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self.kernel.VirtualQueryEx.argtypes = [wintypes.HANDLE, ctypes.c_void_p, ctypes.POINTER(MemoryInfo), ctypes.c_size_t]
        self.kernel.VirtualQueryEx.restype = ctypes.c_size_t
        self.kernel.ReadProcessMemory.argtypes = [wintypes.HANDLE, ctypes.c_void_p, ctypes.c_void_p,
                                                  ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)]
        self.handle = self.kernel.OpenProcess(0x10 | 0x400, False, pid)
        if not self.handle:
            raise ContactReadError('无法只读访问微信进程，请确认微信与工具箱使用同一 Windows 用户。')
        self.deadline = time.monotonic() + timeout
        self.cancelled = cancelled

    def read(self, address, size):
        if not 0x10000 <= address < 0x800000000000 or size < 1 or size > 2 * 1024 * 1024:
            return b''
        buffer = ctypes.create_string_buffer(size)
        actual = ctypes.c_size_t()
        self.kernel.ReadProcessMemory(self.handle, address, buffer, size, ctypes.byref(actual))
        return buffer.raw[:actual.value]

    def chunks(self, include_images=False):
        address = 0x10000
        while address < 0x800000000000:
            if time.monotonic() > self.deadline:
                raise ContactReadError('微信本地通讯录读取超时，请确认客户端已完成登录。')
            if self.cancelled():
                raise InterruptedError('cancelled')
            info = MemoryInfo()
            if not self.kernel.VirtualQueryEx(self.handle, address, ctypes.byref(info), ctypes.sizeof(info)):
                break
            base = info.base or address
            end = base + info.size
            if end <= address:
                break
            if info.state == 0x1000 and (info.kind == 0x20000 or include_images) and not info.protect & 0x101:
                for offset in range(base, end, 1024 * 1024):
                    if self.cancelled():
                        raise InterruptedError('cancelled')
                    data = self.read(offset, min(1024 * 1024 + 128, end - offset))
                    if data:
                        yield offset, data
            address = end

    def key_candidates(self):
        names, plain = set(), set()
        for base, data in self.chunks(include_images=True):
            start = 0
            while True:
                offset = data.find(CIPHER_NAME, start)
                if offset < 0:
                    break
                names.add(base + offset)
                start = offset + 1
            for match in KEY_LITERAL.finditer(data):
                key = bytes.fromhex(match.group(1)[:64].decode('ascii'))
                if key not in plain:
                    plain.add(key)
                    yield key
        if not names:
            return
        references = [struct.pack('<QQ', address, len(CIPHER_NAME)) for address in names]
        for _, data in self.chunks():
            for reference in references:
                start = 0
                while True:
                    offset = data.find(reference, start)
                    if offset < 0:
                        break
                    start = offset + 1
                    # MSVC string/map entry -> WCDB cipher config -> small key blob.
                    if offset + 32 > len(data):
                        continue
                    config = struct.unpack_from('<Q', data, offset + 24)[0]
                    metadata = self.read(config + 0x88, 24)
                    if len(metadata) != 24:
                        continue
                    pointer, length = struct.unpack_from('<QQ', metadata, 8)
                    if not 1 <= length <= 1024:
                        continue
                    blob = self.read(pointer, length)
                    decoded = bytes(value ^ CONFIG_MASK[i % 32] for i, value in enumerate(blob))
                    for match in KEY_LITERAL.finditer(decoded):
                        key = bytes.fromhex(match.group(1)[:64].decode('ascii'))
                        if key not in plain:
                            plain.add(key)
                            yield key

    def close(self):
        if self.handle:
            self.kernel.CloseHandle(self.handle)
            self.handle = None


def decrypt_page(page, number, key, mac_key):
    if len(page) != PAGE_SIZE:
        raise ContactReadError('通讯录数据库页不完整。')
    start = 16 if number == 1 else 0
    authenticated = page[start:PAGE_SIZE - 64] + struct.pack('<I', number)
    if not hmac.compare_digest(hmac.new(mac_key, authenticated, hashlib.sha512).digest(), page[-64:]):
        raise ContactReadError('通讯录数据库页认证失败，未读取未经验证的数据。')
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    decryptor = Cipher(algorithms.AES(key), modes.CBC(page[PAGE_SIZE - RESERVED:PAGE_SIZE - 64])).decryptor()
    plaintext = decryptor.update(page[start:PAGE_SIZE - RESERVED]) + decryptor.finalize()
    return (b'SQLite format 3\0' if number == 1 else b'') + plaintext + bytes(RESERVED)


def wal_checksum(data, state, byte_order):
    if len(data) % 8:
        raise ContactReadError('Invalid WAL checksum input')
    s0, s1 = state
    for a, b in struct.iter_unpack(byte_order + 'II', data):
        s0 = (s0 + a + s1) & 0xffffffff
        s1 = (s1 + b + s0) & 0xffffffff
    return s0, s1


def committed_wal_pages(wal):
    if not wal:
        return {}, None
    if len(wal) < 32:
        raise ContactReadError('通讯录 WAL 头不完整。')
    magic, version, size = struct.unpack_from('>III', wal)
    if magic not in (0x377f0682, 0x377f0683) or version != 3007000 or size != PAGE_SIZE:
        raise ContactReadError('不支持的通讯录 WAL 格式。')
    byte_order = '<' if magic == 0x377f0682 else '>'
    state = wal_checksum(wal[:24], (0, 0), byte_order)
    if state != struct.unpack_from('>II', wal, 24):
        raise ContactReadError('通讯录 WAL 头校验失败。')
    salt = wal[16:24]
    pages, committed, database_size = {}, {}, None
    for offset in range(32, len(wal) - PAGE_SIZE - 23, PAGE_SIZE + 24):
        frame = wal[offset:offset + 24]
        if frame[8:16] != salt:
            break  # Older frames from the previous WAL generation are irrelevant.
        number, size = struct.unpack_from('>II', frame)
        if number < 1 or number * PAGE_SIZE > MAX_DATABASE_BYTES or size * PAGE_SIZE > MAX_DATABASE_BYTES:
            raise ContactReadError('通讯录 WAL 页码超出读取范围。')
        payload = wal[offset + 24:offset + 24 + PAGE_SIZE]
        state = wal_checksum(frame[:8] + payload, state, byte_order)
        if state != struct.unpack_from('>II', frame, 16):
            raise ContactReadError('通讯录 WAL 帧校验失败，请重试读取。')
        pages[number] = payload
        if size:
            committed.update(pages)
            pages.clear()
            database_size = size
    return committed, database_size


def stable_source(path):
    wal_path = Path(str(path) + '-wal')
    for _ in range(3):
        before = path.stat()
        if before.st_size > MAX_DATABASE_BYTES:
            raise ContactReadError('通讯录数据库超过 128 MiB 读取上限。')
        raw = path.read_bytes()
        try:
            with wal_path.open('rb') as stream:
                wal_size = os.fstat(stream.fileno()).st_size
                if wal_size > MAX_DATABASE_BYTES:
                    raise ContactReadError('通讯录 WAL 超过 128 MiB 读取上限。')
                wal = stream.read(wal_size)
                stream.seek(0)
                same_wal = stream.read(32) == wal[:32] and os.fstat(stream.fileno()).st_size >= wal_size
        except FileNotFoundError:
            wal = b''
            same_wal = not wal_path.exists()
        after = path.stat()
        if (before.st_size, before.st_mtime_ns) == (after.st_size, after.st_mtime_ns) and same_wal:
            return raw, wal
        time.sleep(.1)
    raise ContactReadError('微信正在更新通讯录，未取得一致快照，请稍后重试。')


def database_snapshot(path, key):
    raw, wal = stable_source(path)
    if len(raw) < PAGE_SIZE or len(raw) % PAGE_SIZE:
        raise ContactReadError('通讯录文件不是完整的 SQLCipher 4 数据库。')
    mac_key = hashlib.pbkdf2_hmac('sha512', key, bytes(value ^ 0x3a for value in raw[:16]), 2, 32)
    result = bytearray()
    for offset in range(0, len(raw), PAGE_SIZE):
        result.extend(decrypt_page(raw[offset:offset + PAGE_SIZE], offset // PAGE_SIZE + 1, key, mac_key))
    pages, size = committed_wal_pages(wal)
    if size is not None:
        del result[size * PAGE_SIZE:]
        if len(result) < size * PAGE_SIZE:
            result.extend(bytes(size * PAGE_SIZE - len(result)))
        for number, page in pages.items():
            if number <= size:
                result[(number - 1) * PAGE_SIZE:number * PAGE_SIZE] = decrypt_page(page, number, key, mac_key)
    # A deserialized in-memory database cannot open the source's on-disk WAL file.
    result[18:20] = b'\x01\x01'
    return result


def protobuf_fields(data):
    if not data or len(data) > 64 * 1024:
        return {}
    values, position = {}, 0
    def varint():
        nonlocal position
        value = 0
        for shift in range(0, 70, 7):
            if position >= len(data):
                raise ValueError('truncated protobuf')
            byte = data[position]
            position += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value
        raise ValueError('invalid varint')
    try:
        while position < len(data):
            tag = varint()
            field, wire = tag >> 3, tag & 7
            if not field:
                raise ValueError('invalid field')
            if wire == 0:
                value = varint()
            elif wire == 2:
                length = varint()
                if position + length > len(data):
                    raise ValueError('truncated field')
                value = data[position:position + length]
                position += length
            elif wire in (1, 5):
                position += 8 if wire == 1 else 4
                if position > len(data):
                    raise ValueError('truncated field')
                continue
            else:
                raise ValueError('unsupported protobuf wire type')
            values[field, wire] = value
    except ValueError:
        return {}
    return values


def read_snapshot(snapshot, friends_only=True, account_folder=''):
    with sqlite3.connect(':memory:') as database:
        database.deserialize(snapshot)
        database.execute('PRAGMA query_only=ON')
        database.row_factory = sqlite3.Row
        columns = {column[1] for column in database.execute('PRAGMA table_info(contact)')}
        required = {'username', 'nick_name', 'alias', 'remark', 'local_type', 'flag', 'delete_flag', 'verify_flag'}
        if not required.issubset(columns):
            raise ContactReadError('当前微信通讯录表结构尚不支持，未按空通讯录处理。')
        labels = {}
        if database.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='contact_label'").fetchone():
            label_columns = {c[1] for c in database.execute('PRAGMA table_info(contact_label)')}
            if {'label_id_', 'label_name_'}.issubset(label_columns):
                labels = dict(database.execute('SELECT label_id_, label_name_ FROM contact_label'))
        contacts = []
        for row in database.execute('SELECT * FROM contact WHERE delete_flag=0 ORDER BY id'):
            username = row['username'] or ''
            if not username:
                continue
            is_self = account_folder == username or account_folder.startswith(username + '_')
            # local_type identifies personal address-book records. A missing flag bit 2
            # does not mean the record is absent from the address book (observed flag=1).
            # Also exclude saved group chats, which can have local_type=1.
            is_friend = row['local_type'] in (1, 5) and not row['verify_flag'] & 8 \
                and not username.endswith('@chatroom') and not username.startswith('gh_')
            if is_self:
                continue
            if friends_only and not is_friend:
                continue
            extra = protobuf_fields(row['extra_buffer']) if 'extra_buffer' in columns else {}
            gender = extra.get((5 if row['local_type'] == 5 else 2, 0), 0)
            gender = gender if gender in (1, 2) else 0
            try:
                tag_ids = extra.get((30, 2), b'').decode('utf-8').split(',')
                tag = ', '.join(str(labels[int(value)]) for value in tag_ids if value.isdigit() and int(value) in labels)
            except UnicodeError:
                tag = ''
            contacts.append({'type': 'contact', 'username': username, 'wechat_id': row['alias'] or '',
                             'nickname': row['nick_name'] or '', 'remark': row['remark'] or '',
                             'gender': gender, 'tag': tag, 'avatar_url': (row['big_head_url'] or row['small_head_url'] or '')
                             if 'big_head_url' in columns and 'small_head_url' in columns else '',
                             'contact_type': 3 if is_friend else 2 if '@chatroom' in username else 8 if row['verify_flag'] & 8 else 0})
        return contacts


def weixin_processes():
    result = subprocess.run(['tasklist.exe', '/FI', 'IMAGENAME eq Weixin.exe', '/FO', 'CSV', '/NH'],
                            capture_output=True, timeout=10, encoding='utf-8', errors='replace',
                            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    import csv
    return [int(row[1]) for row in csv.reader(result.stdout.splitlines())
            if len(row) > 1 and row[0].lower() == 'weixin.exe' and row[1].isdigit()]


def collect_contacts(emit, cancelled=lambda: False, friends_only=True):
    if os.name != 'nt' or ctypes.sizeof(ctypes.c_void_p) != 8:
        raise ContactReadError('自动读取需要 Windows 和 64 位 Python 3.11 或更新版本。')
    if not hasattr(sqlite3.Connection, 'deserialize'):
        raise ContactReadError('当前 Python 缺少 SQLite 内存快照支持，请使用 Python 3.11 或更新版本。')
    paths = contact_databases()
    if not paths:
        raise ContactReadError('未找到本机微信 4.x 通讯录数据库，请先完成微信登录。')
    processes = weixin_processes()
    if not processes:
        raise ContactReadError('微信未运行，请先完成登录。')
    headers = {}
    for path in paths:
        with path.open('rb') as stream:
            headers[path] = stream.read(PAGE_SIZE)
    errors = []
    for pid in processes:
        emit({'type': 'status', 'status': '正在只读识别已登录账号的本地通讯录…'})
        memory = None
        try:
            memory = ProcessMemory(pid, cancelled=cancelled)
            for key in memory.key_candidates():
                for path, first in headers.items():
                    if not verify_key(key, first):
                        continue
                    emit({'type': 'status', 'status': '正在验证数据库和最新已提交数据…'})
                    contacts = read_snapshot(database_snapshot(path, key), friends_only, path.parents[2].name)
                    for contact in contacts:
                        if cancelled():
                            raise InterruptedError('cancelled')
                        emit(contact)
                    emit({'type': 'complete', 'count': len(contacts), 'status': f'已自动读取 {len(contacts)} 个联系人。'})
                    return len(contacts)
        except (OSError, ContactReadError) as error:
            errors.append(str(error))
        finally:
            if memory:
                memory.close()
    raise ContactReadError(errors[-1] if errors else '未识别到已登录账号的通讯录读取参数，当前微信版本可能尚未适配。')
