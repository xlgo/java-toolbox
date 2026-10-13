import sqlite3
import sys
import struct
from pathlib import Path
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from wechat_send_store import MessageStore, Recipient, resolve_recipient, SendError
from wechat_send_ui import WeixinUI, find_accessibility_byte


class ResolveTests(unittest.TestCase):
    def setUp(self):
        self.rows = [Recipient('wxid_first', 'alias_first', 'Same', 'First Remark'),
                     Recipient('wxid_second', 'alias_second', 'Same', '')]

    def test_stable_id_alias_and_unique_remark_resolve_exactly(self):
        for query in ['wxid_first', 'alias_first', 'First Remark', 'Same (alias_first)']:
            self.assertEqual('wxid_first', resolve_recipient(self.rows, query).username)

    def test_duplicate_display_names_and_missing_contact_do_not_send(self):
        for query in ['Same', 'unknown', '']:
            with self.assertRaises(SendError): resolve_recipient(self.rows, query)

    def test_exact_alias_cannot_bypass_chat_title_collision_with_nonfriend(self):
        store = MessageStore(0)
        store.contacts = [self.rows[0]]
        store.display_names['First Remark'] = 2
        with self.assertRaises(SendError): store.resolve('alias_first')
        store.display_names['First Remark'] = 1
        self.assertEqual('wxid_first', store.resolve('alias_first').username)


class FakeStore(MessageStore):
    def __init__(self):
        super().__init__(0)
        self.message_paths = [Path('first.db'), Path('second.db')]
        self.own_username = 'wxid_self'
        self.databases = {}
        for path in self.message_paths:
            db = sqlite3.connect(':memory:'); db.row_factory = sqlite3.Row
            db.execute('CREATE TABLE Name2Id(user_name TEXT)')
            db.execute('INSERT INTO Name2Id(rowid,user_name) VALUES(42,?)', (self.own_username,))
            db.execute('INSERT INTO Name2Id(rowid,user_name) VALUES(7,?)', ('someone_else',))
            table = self.table_name('wxid_target')
            db.execute(f'CREATE TABLE "{table}"(local_id INTEGER,server_id INTEGER,local_type INTEGER,real_sender_id INTEGER,origin_source INTEGER,status INTEGER,message_content TEXT)')
            self.databases[path] = db

    def open_database(self, path): return self.databases[path]
    def add(self, path, local_id=1, server_id=123, sender=42, origin=1, status=2, text='message'):
        db = self.databases[Path(path)]; table = self.table_name('wxid_target')
        db.execute(f'INSERT INTO "{table}" VALUES(?,?,?,?,?,?,?)', (local_id,server_id,1,sender,origin,status,text)); db.commit()


class ReceiptTests(unittest.TestCase):
    def setUp(self): self.store = FakeStore()
    def tearDown(self):
        for db in self.store.databases.values(): db.close()

    def test_previous_message_does_not_confirm_new_attempt(self):
        self.store.add('first.db')
        baseline = self.store.baseline('wxid_target')
        self.assertEqual([], self.store.outgoing('wxid_target', baseline, 'message'))

    def test_incoming_or_synced_message_does_not_confirm_this_pc_send(self):
        baseline = self.store.baseline('wxid_target')
        self.store.add('first.db', sender=7)
        self.store.add('second.db', origin=7)
        self.assertEqual([], self.store.outgoing('wxid_target', baseline, 'message'))

    def test_sender_mapping_is_shard_local_and_server_id_required(self):
        baseline = self.store.baseline('wxid_target')
        self.store.add('second.db', server_id=0)
        records = self.store.outgoing('wxid_target', baseline, 'message')
        self.assertEqual(1, len(records)); self.assertEqual('second.db', records[0]['shard'])
        with patch('wechat_send_store.time.monotonic', side_effect=[0, 0, 2]), patch('wechat_send_store.time.sleep'):
            receipt = self.store.wait_receipt('wxid_target', baseline, 'message', timeout=1)
        self.assertEqual('pending', receipt['outcome'])

    def test_confirmed_status_and_positive_server_id(self):
        baseline = self.store.baseline('wxid_target'); self.store.add('first.db')
        receipt = self.store.wait_receipt('wxid_target', baseline, 'message', timeout=1)
        self.assertEqual('acknowledged', receipt['outcome']); self.assertEqual(123, receipt['server_id'])

    def test_wrong_content_or_status_cannot_be_claimed_successful(self):
        baseline = self.store.baseline('wxid_target'); self.store.add('first.db', text='different')
        self.assertEqual([], self.store.outgoing('wxid_target', baseline, 'message'))
        self.store.add('second.db', status=4)
        with patch('wechat_send_store.time.monotonic', side_effect=[0,0,2]), patch('wechat_send_store.time.sleep'):
            receipt=self.store.wait_receipt('wxid_target',baseline,'message',timeout=1)
        self.assertEqual('pending',receipt['outcome'])

    def test_duplicate_records_remain_uncertain_even_if_one_has_server_id(self):
        baseline=self.store.baseline('wxid_target'); self.store.add('first.db');self.store.add('second.db',server_id=0)
        self.assertEqual('uncertain',self.store.wait_receipt('wxid_target',baseline,'message',timeout=1)['outcome'])

    def test_cancellation_during_receipt_wait_stops_immediately(self):
        self.store.cancelled=lambda:True
        with self.assertRaises(InterruptedError): self.store.wait_receipt('wxid_target',{},'message')


def pe_image(writable=True):
    data=bytearray(0x1600); data[:2]=b'MZ';struct.pack_into('<I',data,60,0x80)
    data[0x80:0x84]=b'PE\0\0';struct.pack_into('<HH',data,0x84,0x8664,3);struct.pack_into('<H',data,0x94,0)
    sections=[(0x1000,0x1000,0x1000,0x400,0x60000000),(0x2000,0x100,0x100,0x1400,0x40000000),
              (0x3000,0x100,0x100,0x1500,0xc0000000 if writable else 0x40000000)]
    for i,(rva,vsize,size,raw,flags) in enumerate(sections):
        off=0x98+i*40;struct.pack_into('<IIII',data,off+8,vsize,rva,size,raw);struct.pack_into('<I',data,off+36,flags)
    data[0x1400:0x1400+22]=b'qt.accessibility.core\0'
    data[0x400:0x407]=b'\x48\x8d\x0d'+struct.pack('<i',0x2000-0x1007)
    gate=b'\x48\x85\xc9\x0f\x84'+bytes(4)+b'\x80\x3d'+struct.pack('<i',0x3000-0x1090)+b'\x00\x0f\x84'
    data[0x480:0x480+len(gate)]=gate
    return data


class UiGuardTests(unittest.TestCase):
    def test_search_uses_unique_result_click_and_maps_internal_id_to_title(self):
        person = Recipient('wxid_internal', '', 'Expected', '')
        class Value:
            Value = ''
            def SetValue(self, value, **kwargs): self.Value = value
        value = Value()
        class Control:
            def __init__(self, kind, name, aid='', classname=''):
                self.ControlTypeName, self.Name, self.AutomationId, self.ClassName = kind, name, aid, classname
            def GetValuePattern(self): return value
        tab = Control('ButtonControl', '微信', classname='mmui::XTabBarItem')
        search = Control('EditControl', '搜索')
        result = Control('ListItemControl', 'Expected', 'search_item_Expected')
        edit = Control('EditControl', 'Expected', 'chat_input_field')
        ui = object.__new__(WeixinUI); ui.cancelled = lambda: False; ui.show = lambda: None
        clicked = []
        ui.click = clicked.append; ui.root = lambda: None
        ui.walk = lambda root: iter([(result, 1)])
        ui.find = lambda predicate, root=None: next((c for c in (tab, search) if predicate(c)), None)
        ui.input = lambda: edit if result in clicked else None
        self.assertIs(edit, ui.open_chat(person))
        self.assertEqual('Expected', value.Value)
        self.assertEqual([tab, result], clicked)

    def test_existing_user_draft_is_preserved_without_send(self):
        class Value: Value = 'user draft'
        class Edit:
            def GetValuePattern(self): return Value()
        ui = object.__new__(WeixinUI); ui.open_chat = lambda person: Edit()
        with self.assertRaisesRegex(SendError, '草稿'):
            ui.stage_text(Recipient('id', 'alias', 'Expected', ''), 'message')
        self.assertEqual('user draft', Value.Value)

    def test_accessibility_target_is_uniquely_validated_writable_data(self):
        self.assertEqual(0x3000,find_accessibility_byte(pe_image())[0])
        with self.assertRaises(SendError):find_accessibility_byte(pe_image(False))
        with self.assertRaises(SendError):find_accessibility_byte(b'not executable')

    def test_target_and_content_are_checked_before_any_send_event(self):
        class Value: Value='different'
        class Edit:
            Name='Expected'
            def GetValuePattern(self):return Value()
        ui=object.__new__(WeixinUI);ui.cancelled=lambda:False
        with self.assertRaises(SendError):ui.dispatch(Edit(),Recipient('id','alias','Expected',''),'message')


if __name__ == '__main__': unittest.main()
