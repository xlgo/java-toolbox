import hashlib
import hmac
import sqlite3
import struct
import sys
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import wechat_contacts as contacts
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


def encrypted_page(number, key, salt, plaintext):
    start = 16 if number == 1 else 0
    iv = bytes([number % 256]) * 16
    encryptor = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
    data = encryptor.update(plaintext[start:4016]) + encryptor.finalize()
    mac_key = hashlib.pbkdf2_hmac('sha512', key, bytes(b ^ 0x3a for b in salt), 2, 32)
    mac = hmac.new(mac_key, data + iv + struct.pack('<I', number), hashlib.sha512).digest()
    return (salt if number == 1 else b'') + data + iv + mac


def wal(frames):
    header = struct.pack('>IIIIII', 0x377f0682, 3007000, 4096, 0, 10, 20)
    state = contacts.wal_checksum(header, (0, 0), '<')
    result = header + struct.pack('>II', *state)
    for number, size, payload in frames:
        frame = struct.pack('>IIII', number, size, 10, 20)
        state = contacts.wal_checksum(frame[:8] + payload, state, '<')
        result += frame + struct.pack('>II', *state) + payload
    return result


def snapshot(rows):
    with sqlite3.connect(':memory:') as database:
        database.execute('CREATE TABLE contact(id INTEGER PRIMARY KEY,username TEXT,nick_name TEXT,alias TEXT,remark TEXT,local_type INTEGER,flag INTEGER,delete_flag INTEGER,verify_flag INTEGER,big_head_url TEXT,small_head_url TEXT,extra_buffer BLOB)')
        database.execute('CREATE TABLE contact_label(label_id_ INTEGER,label_name_ TEXT)')
        database.execute("INSERT INTO contact_label VALUES (7,'Work')")
        for index, row in enumerate(rows):
            values = {'username': 'id' + str(index), 'nick_name': 'Name', 'alias': '', 'remark': '',
                      'local_type': 1, 'flag': 3, 'delete_flag': 0, 'verify_flag': 0,
                      'big_head_url': '', 'small_head_url': '', 'extra_buffer': b''}
            values.update(row)
            database.execute('INSERT INTO contact VALUES (?,?,?,?,?,?,?,?,?,?,?,?)', [index] + list(values.values()))
        database.commit()
        return database.serialize()


class EncryptionTests(unittest.TestCase):
    def setUp(self):
        self.key = bytes(range(32))
        self.salt = bytes(range(16))
        self.first = b'SQLite format 3\0' + bytes([5]) * (4096 - 16 - 80) + bytes(80)

    def test_page_authentication_roundtrip_and_wrong_key(self):
        page = encrypted_page(1, self.key, self.salt, self.first)
        self.assertTrue(contacts.verify_key(self.key, page))
        self.assertFalse(contacts.verify_key(bytes(32), page))
        mac_key = hashlib.pbkdf2_hmac('sha512', self.key, bytes(b ^ 0x3a for b in self.salt), 2, 32)
        self.assertEqual(self.first, contacts.decrypt_page(page, 1, self.key, mac_key))
        corrupt = bytearray(page); corrupt[40] ^= 1
        with self.assertRaises(contacts.ContactReadError): contacts.decrypt_page(corrupt, 1, self.key, mac_key)

    def test_page_number_is_authenticated(self):
        plain = bytes([6]) * 4016 + bytes(80)
        page = encrypted_page(2, self.key, self.salt, plain)
        mac_key = hashlib.pbkdf2_hmac('sha512', self.key, bytes(b ^ 0x3a for b in self.salt), 2, 32)
        self.assertEqual(plain, contacts.decrypt_page(page, 2, self.key, mac_key))
        with self.assertRaises(contacts.ContactReadError): contacts.decrypt_page(page, 3, self.key, mac_key)

    def test_committed_wal_overlays_latest_pages_and_ignores_uncommitted_tail(self):
        p1 = b'a' * 4096; p2 = b'b' * 4096; p3 = b'c' * 4096
        pages, size = contacts.committed_wal_pages(wal([(2, 0, p1), (3, 3, p2), (2, 0, p3)]))
        self.assertEqual(3, size)
        self.assertEqual({2: p1, 3: p2}, pages)

    def test_wal_checksum_corruption_is_reported(self):
        data = bytearray(wal([(1, 1, b'a' * 4096)])); data[-1] ^= 1
        with self.assertRaises(contacts.ContactReadError): contacts.committed_wal_pages(data)

    def test_incomplete_frame_is_not_committed(self):
        complete = wal([(1, 1, b'a' * 4096)])
        self.assertEqual(({1: b'a' * 4096}, 1), contacts.committed_wal_pages(complete + b'partial'))

    def test_last_commit_can_shrink_database(self):
        page = encrypted_page(1, self.key, self.salt, self.first)
        raw = page + encrypted_page(2, self.key, self.salt, bytes([8]) * 4016 + bytes(80))
        with patch.object(contacts, 'stable_source', return_value=(raw, wal([(1, 1, page)]))):
            result = contacts.database_snapshot(Path('unused'), self.key)
        self.assertEqual(4096, len(result))
        self.assertEqual(b'\1\1', result[18:20])

    def test_changed_snapshot_does_not_change_live_files(self):
        page = encrypted_page(1, self.key, self.salt, self.first)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'contact.db'; path.write_bytes(page)
            contacts.database_snapshot(path, self.key)
            self.assertEqual(page, path.read_bytes())
            self.assertEqual(['contact.db'], [p.name for p in Path(directory).iterdir()])


class ContactTests(unittest.TestCase):
    def test_personal_contacts_include_enterprise_but_not_self_public_accounts_groups_or_strangers(self):
        data = snapshot([
            {'username': 'wxid_self'}, {'username': 'friend'}, {'username': 'enterprise', 'local_type': 5, 'flag': 1},
            {'username': 'removed', 'delete_flag': 1}, {'username': 'official', 'verify_flag': 8},
            {'username': 'group@chatroom', 'local_type': 2, 'flag': 2}, {'username': 'group_member', 'local_type': 3, 'flag': 4},
        ])
        rows = contacts.read_snapshot(data, account_folder='wxid_self_1234')
        self.assertEqual(['friend', 'enterprise'], [row['username'] for row in rows])

    def test_same_names_are_preserved_with_stable_usernames(self):
        rows = contacts.read_snapshot(snapshot([{'username': 'first'}, {'username': 'second'}]))
        self.assertEqual(2, len(rows))
        self.assertEqual('Name', rows[0]['nickname'])
        self.assertEqual('Name', rows[1]['nickname'])

    def test_personal_contacts_without_flag_bit_two_are_not_silently_dropped(self):
        rows = contacts.read_snapshot(snapshot([
            {'username': 'wxid_first', 'flag': 1, 'remark': 'A Remark'},
            {'username': 'wxid_second', 'flag': 1, 'alias': 'searchable_alias', 'nick_name': 'A Nickname'},
            {'username': 'wxid_new_format', 'flag': 0},
        ]))
        self.assertEqual(['wxid_first', 'wxid_second', 'wxid_new_format'], [r['username'] for r in rows])
        self.assertEqual('A Remark', rows[0]['remark'])
        self.assertEqual('searchable_alias', rows[1]['wechat_id'])

    def test_saved_group_and_official_account_are_excluded_even_as_personal_type(self):
        rows = contacts.read_snapshot(snapshot([
            {'username': 'saved@chatroom', 'local_type': 1, 'flag': 3},
            {'username': 'gh_public', 'local_type': 1, 'flag': 3, 'verify_flag': 0},
            {'username': 'normal', 'local_type': 1, 'flag': 1},
        ]))
        self.assertEqual(['normal'], [r['username'] for r in rows])

    def test_alias_spaces_labels_gender_and_avatar(self):
        # Protobuf field 2 (normal-contact gender), field 30 (comma-separated label IDs).
        data = snapshot([{'username': 'friend', 'alias': 'custom_id', 'remark': 'First Last',
                          'extra_buffer': b'\x10\x02\xf2\x01\x01\x37', 'small_head_url': 'https://avatar.invalid/a'}])
        row = contacts.read_snapshot(data)[0]
        self.assertEqual('custom_id', row['wechat_id'])
        self.assertEqual('First Last', row['remark'])
        self.assertEqual(2, row['gender'])
        self.assertEqual('Work', row['tag'])
        self.assertEqual('https://avatar.invalid/a', row['avatar_url'])

    def test_enterprise_contact_uses_its_own_metadata_format(self):
        row = contacts.read_snapshot(snapshot([{'local_type': 5, 'flag': 1, 'extra_buffer': b'\x28\x01'}]))[0]
        self.assertEqual(1, row['gender'])

    def test_unknown_metadata_does_not_discard_contact(self):
        row = contacts.read_snapshot(snapshot([{'extra_buffer': b'\xff\xff'}]))[0]
        self.assertEqual(0, row['gender'])
        self.assertEqual('', row['tag'])

    def test_unknown_schema_is_failure_instead_of_successful_empty_list(self):
        with sqlite3.connect(':memory:') as database:
            database.execute('CREATE TABLE other(n INTEGER)')
            with self.assertRaises(contacts.ContactReadError): contacts.read_snapshot(database.serialize())


if __name__ == '__main__': unittest.main()
