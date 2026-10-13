"""Bounded Windows UI operations for sending through the logged-in Weixin client.

Text is written and read back through UI Automation. The final key is posted only
to the selected Weixin window; no global keyboard shortcuts or OCR are used.
"""
from __future__ import annotations

import ctypes
from ctypes import wintypes as wt
import mmap
import os
from pathlib import Path
import re
import struct
import time

from wechat_send_store import SendError


class Module(ctypes.Structure):
    _fields_ = [('size', wt.DWORD), ('module', wt.DWORD), ('pid', wt.DWORD), ('global_count', wt.DWORD),
                ('process_count', wt.DWORD), ('base', ctypes.c_void_p), ('length', wt.DWORD),
                ('handle', wt.HMODULE), ('name', wt.WCHAR * 256), ('path', wt.WCHAR * 260)]


class Placement(ctypes.Structure):
    _fields_ = [('length', wt.UINT), ('flags', wt.UINT), ('show', wt.UINT),
                ('minimum', wt.POINT), ('maximum', wt.POINT), ('normal', wt.RECT)]


def find_accessibility_byte(data):
    if len(data) < 64 or data[:2] != b'MZ':
        raise SendError('微信客户端文件格式不支持。')
    pe = struct.unpack_from('<I', data, 60)[0]
    if pe + 24 > len(data) or data[pe:pe + 4] != b'PE\0\0':
        raise SendError('微信客户端文件格式不支持。')
    machine, count = struct.unpack_from('<HH', data, pe + 4)
    optional = struct.unpack_from('<H', data, pe + 20)[0]
    if machine != 0x8664 or not 1 <= count <= 96 or pe + 24 + optional + 40 * count > len(data):
        raise SendError('仅支持已验证的 64 位 Windows 微信客户端。')
    sections = []
    for i in range(count):
        offset = pe + 24 + optional + i * 40
        size, rva, raw_size, raw = struct.unpack_from('<IIII', data, offset + 8)
        flags = struct.unpack_from('<I', data, offset + 36)[0]
        if raw + raw_size > len(data):
            raise SendError('微信客户端文件不完整。')
        sections.append((rva, max(size, raw_size), raw, raw_size, flags))
    marker = data.find(b'qt.accessibility.core\0')
    marker_rva = next((rva + marker - raw for rva, _, raw, size, _ in sections if raw <= marker < raw + size), None)
    references, candidates = [], []
    for rva, _, raw, size, flags in sections:
        if not flags & 0x20000000:
            continue
        code = data[raw:raw + size]
        for m in re.finditer(rb'[\x40-\x4f]\x8d[\x05\x0d\x15\x1d\x25\x2d\x35\x3d]....', code, re.DOTALL):
            if rva + m.start() + 7 + struct.unpack('<i', m.group()[3:7])[0] == marker_rva:
                references.append(rva + m.start())
        for m in re.finditer(rb'\x48\x85\xc9\x0f\x84....\x80\x3d....\x00\x0f\x84', code, re.DOTALL):
            instruction = rva + m.start()
            target = instruction + 16 + struct.unpack('<i', m.group()[11:15])[0]
            writable = any(base <= target < base + length and section_flags & 0x80000000
                           and not section_flags & 0x20000000 for base, length, _, _, section_flags in sections)
            if writable:
                candidates.append((target, instruction, m.group()))
    matches = [candidate for candidate in candidates if any(abs(candidate[1] - ref) <= 0x2000 for ref in references)]
    if len({candidate[0] for candidate in matches}) != 1:
        raise SendError('此微信版本的无障碍状态尚未验证，未执行发送。')
    return matches[0]


class WeixinUI:
    def __init__(self, cancelled=lambda: False):
        import uiautomation
        self.uia = uiautomation
        self.cancelled = cancelled
        self.user = ctypes.WinDLL('user32', use_last_error=True)
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        user_signatures = {
            'GetForegroundWindow': ([], wt.HWND), 'IsWindowVisible': ([wt.HWND], wt.BOOL),
            'IsIconic': ([wt.HWND], wt.BOOL), 'GetWindowThreadProcessId': ([wt.HWND, ctypes.POINTER(wt.DWORD)], wt.DWORD),
            'GetWindowTextW': ([wt.HWND, wt.LPWSTR, ctypes.c_int], ctypes.c_int),
            'GetClassNameW': ([wt.HWND, wt.LPWSTR, ctypes.c_int], ctypes.c_int),
            'GetWindowPlacement': ([wt.HWND, ctypes.POINTER(Placement)], wt.BOOL),
            'SetWindowPlacement': ([wt.HWND, ctypes.POINTER(Placement)], wt.BOOL),
            'GetWindowRect': ([wt.HWND, ctypes.POINTER(wt.RECT)], wt.BOOL),
            'GetCursorPos': ([ctypes.POINTER(wt.POINT)], wt.BOOL),
            'SetCursorPos': ([ctypes.c_int, ctypes.c_int], wt.BOOL),
            'ShowWindow': ([wt.HWND, ctypes.c_int], wt.BOOL),
            'SetWindowPos': ([wt.HWND, wt.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wt.UINT], wt.BOOL),
            'SetForegroundWindow': ([wt.HWND], wt.BOOL), 'BringWindowToTop': ([wt.HWND], wt.BOOL),
            'GetWindowLongW': ([wt.HWND, ctypes.c_int], wt.LONG),
            'SetWindowLongW': ([wt.HWND, ctypes.c_int, wt.LONG], wt.LONG),
            'PostMessageW': ([wt.HWND, wt.UINT, wt.WPARAM, wt.LPARAM], wt.BOOL),
            'AttachThreadInput': ([wt.DWORD, wt.DWORD, wt.BOOL], wt.BOOL),
        }
        for name, (args, result) in user_signatures.items():
            function = getattr(self.user, name); function.argtypes = args; function.restype = result
        kernel_signatures = {
            'CreateToolhelp32Snapshot': ([wt.DWORD, wt.DWORD], wt.HANDLE),
            'Module32FirstW': ([wt.HANDLE, ctypes.POINTER(Module)], wt.BOOL),
            'Module32NextW': ([wt.HANDLE, ctypes.POINTER(Module)], wt.BOOL),
            'OpenProcess': ([wt.DWORD, wt.BOOL, wt.DWORD], wt.HANDLE),
            'CloseHandle': ([wt.HANDLE], wt.BOOL),
            'ReadProcessMemory': ([wt.HANDLE, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)], wt.BOOL),
            'WriteProcessMemory': ([wt.HANDLE, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)], wt.BOOL),
            'CreateMutexW': ([ctypes.c_void_p, wt.BOOL, wt.LPCWSTR], wt.HANDLE),
            'WaitForSingleObject': ([wt.HANDLE, wt.DWORD], wt.DWORD), 'ReleaseMutex': ([wt.HANDLE], wt.BOOL),
        }
        for name, (args, result) in kernel_signatures.items():
            function = getattr(self.kernel, name); function.argtypes = args; function.restype = result
        self.hwnd, self.pid = self.find_window()
        self.original_foreground = self.user.GetForegroundWindow()
        self.was_visible = bool(self.user.IsWindowVisible(self.hwnd))
        self.placement = Placement(); self.placement.length = ctypes.sizeof(Placement)
        self.user.GetWindowPlacement(self.hwnd, ctypes.byref(self.placement))
        self.normal_rect = wt.RECT()
        self.user.GetWindowRect(self.hwnd, ctypes.byref(self.normal_rect))
        self.memory = None; self.accessibility_address = None; self.original_byte = None
        self.mutex = self.kernel.CreateMutexW(None, False, 'Local\\JavaToolbox.WeixinSend.' + str(self.pid))
        if not self.mutex or self.kernel.WaitForSingleObject(self.mutex, 0) not in (0, 0x80):
            if self.mutex: self.kernel.CloseHandle(self.mutex)
            self.mutex = None
            raise SendError('此微信账号已有群发任务正在执行。')

    def check_cancelled(self):
        if self.cancelled():
            raise InterruptedError('cancelled')

    def find_window(self):
        windows = []
        callback_type = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
        def visit(hwnd, _):
            title = ctypes.create_unicode_buffer(256); classname = ctypes.create_unicode_buffer(256)
            self.user.GetWindowTextW(hwnd, title, 256); self.user.GetClassNameW(hwnd, classname, 256)
            if title.value in ('微信', 'Weixin', 'WeChat') and ('Qt' in classname.value or 'WeChatMainWnd' in classname.value):
                pid = wt.DWORD(); self.user.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
                windows.append((int(hwnd), pid.value))
            return True
        self.user.EnumWindows(callback_type(visit), 0)
        if len(windows) != 1:
            raise SendError('未找到唯一的微信主窗口，请完成登录并仅保留待发送账号实例。')
        return windows[0]

    def prepare(self):
        self.show()
        # Prefer the existing provider; only activate a uniquely validated data flag when needed.
        if any(c.ClassName.startswith('mmui::') for c, _ in self.walk(self.root(), 7)):
            return
        snapshot = self.kernel.CreateToolhelp32Snapshot(0x18, self.pid)
        if snapshot == ctypes.c_void_p(-1).value:
            raise ctypes.WinError(ctypes.get_last_error())
        module = Module(); module.size = ctypes.sizeof(module)
        selected = None
        try:
            found = self.kernel.Module32FirstW(snapshot, ctypes.byref(module))
            while found:
                if module.name.lower() == 'weixin.dll':
                    selected = module.path, module.base, module.length; break
                found = self.kernel.Module32NextW(snapshot, ctypes.byref(module))
        finally: self.kernel.CloseHandle(snapshot)
        if selected is None:
            raise SendError('此微信客户端尚未支持控件级发送。')
        path, base, size = selected
        with Path(path).open('rb') as file, mmap.mmap(file.fileno(), 0, access=mmap.ACCESS_READ) as data:
            target, instruction, signature = find_accessibility_byte(data)
        if target >= size or instruction + len(signature) > size:
            raise SendError('微信无障碍状态地址验证失败。')
        self.memory = self.kernel.OpenProcess(0x38 | 0x1000, False, self.pid)
        if not self.memory: raise ctypes.WinError(ctypes.get_last_error())
        if self.read_memory(base + instruction, len(signature)) != signature:
            raise SendError('运行中的微信模块与已验证文件不一致。')
        self.accessibility_address = base + target
        self.original_byte = self.read_memory(self.accessibility_address, 1)
        if self.original_byte not in (b'\0', b'\1'):
            raise SendError('微信无障碍状态值不支持。')
        if self.original_byte == b'\0': self.write_byte(b'\1')
        if not any(c.ClassName == 'mmui::XTabBarItem' for c, _ in self.walk(self.root(), 8)):
            raise SendError('微信控件树未就绪，未执行发送。')

    def read_memory(self, address, size):
        buffer = ctypes.create_string_buffer(size); actual = ctypes.c_size_t()
        if not self.kernel.ReadProcessMemory(self.memory, address, buffer, size, ctypes.byref(actual)) or actual.value != size:
            raise ctypes.WinError(ctypes.get_last_error())
        return buffer.raw

    def write_byte(self, value):
        actual = ctypes.c_size_t()
        if not self.kernel.WriteProcessMemory(self.memory, self.accessibility_address, value, 1, ctypes.byref(actual)) or actual.value != 1:
            raise ctypes.WinError(ctypes.get_last_error())

    def root(self):
        return self.uia.ControlFromHandle(self.hwnd)

    def walk(self, root, depth=28):
        count = 0
        for control, level in self.uia.WalkControl(root, maxDepth=depth):
            self.check_cancelled(); count += 1
            if count > 5000: raise SendError('微信控件数量超出定位范围。')
            yield control, level

    def find(self, predicate, root=None):
        return next((control for control, _ in self.walk(root or self.root()) if predicate(control)), None)

    def show(self):
        self.check_cancelled()
        if not self.user.IsWindowVisible(self.hwnd):
            os.startfile('weixin://'); time.sleep(.6)
        self.user.ShowWindow(self.hwnd, 9)
        # Some Weixin builds keep a minimized location after raw Win32 restore.
        rect = wt.RECT(); self.user.GetWindowRect(self.hwnd, ctypes.byref(rect))
        if rect.left < -30000 or rect.top < -30000:
            normal = self.normal_rect
            if normal.left < -30000 or normal.right - normal.left < 800:
                self.user.SetWindowPos(self.hwnd, None, 100, 80, 1100, 760, 0x54)
            else:
                self.user.SetWindowPos(self.hwnd, None, normal.left, normal.top,
                                       normal.right - normal.left, normal.bottom - normal.top, 0x54)
        time.sleep(.1)

    def foreground(self, hwnd):
        if not hwnd or self.user.GetForegroundWindow() == hwnd: return
        thread = self.user.GetWindowThreadProcessId(self.user.GetForegroundWindow(), None)
        current = self.kernel.GetCurrentThreadId()
        attached = thread and self.user.AttachThreadInput(current, thread, True)
        try:
            self.user.BringWindowToTop(hwnd); self.user.SetForegroundWindow(hwnd)
        finally:
            if attached: self.user.AttachThreadInput(current, thread, False)

    def click(self, control):
        self.check_cancelled(); self.foreground(self.hwnd); time.sleep(.05)
        if self.user.GetForegroundWindow() != self.hwnd:
            raise SendError('微信窗口焦点确认失败，未执行点击。')
        rect = control.BoundingRectangle
        if rect.left < -30000 or rect.right <= rect.left or rect.bottom <= rect.top:
            raise SendError('微信控件尚不可点击。')
        saved = []
        for child in self.root().GetChildren():
            hwnd = child.NativeWindowHandle
            if hwnd:
                style = self.user.GetWindowLongW(hwnd, -20)
                if style & 0x20:
                    self.user.SetWindowLongW(hwnd, -20, style & ~0x20); saved.append((hwnd, style))
        cursor = wt.POINT(); saved_cursor = self.user.GetCursorPos(ctypes.byref(cursor))
        try: control.Click(simulateMove=False, waitTime=.1)
        finally:
            for hwnd, style in saved: self.user.SetWindowLongW(hwnd, -20, style)
            # Do not move the user's pointer back if they moved it during navigation.
            current = wt.POINT()
            if saved_cursor and self.user.GetCursorPos(ctypes.byref(current)) \
                    and abs(current.x - (rect.left + rect.right) // 2) <= 1 \
                    and abs(current.y - (rect.top + rect.bottom) // 2) <= 1:
                self.user.SetCursorPos(cursor.x, cursor.y)

    def input(self):
        return self.find(lambda c: c.AutomationId.split('.')[-1] == 'chat_input_field')

    def open_chat(self, person):
        self.show()
        chat_tab = self.find(lambda c: c.ClassName == 'mmui::XTabBarItem' and c.Name in ('微信', 'WeChat', 'Chats'))
        if chat_tab is None: raise SendError('未找到微信聊天页。')
        self.click(chat_tab)
        current = self.input()
        if current is not None and current.Name == person.display_name:
            return current
        recent = self.find(lambda c: c.ClassName == 'mmui::ChatSessionCell'
                           and c.AutomationId.split('.')[-1] == 'session_item_' + person.display_name)
        if recent is not None:
            self.click(recent)
            current = self.input()
            if current is not None and current.Name == person.display_name:
                return current
        search = self.find(lambda c: c.ControlTypeName == 'EditControl' and '搜索' in c.Name)
        if search is None:
            entrance = self.find(lambda c: c.Name in ('搜索', 'Search') and c.ControlTypeName in ('ButtonControl', 'GroupControl'))
            if entrance is None: raise SendError('未找到微信搜索入口。')
            self.click(entrance)
        deadline = time.monotonic() + 4
        while search is None and time.monotonic() < deadline:
            search = self.find(lambda c: c.ControlTypeName == 'EditControl' and '搜索' in c.Name)
            if search is None: time.sleep(.1)
        if search is None: raise SendError('微信搜索框未就绪。')
        # Weixin does not search internal wxid values; map them to a unique UI title.
        query = person.alias or person.display_name
        search.GetValuePattern().SetValue(query, waitTime=.1)
        if search.GetValuePattern().Value != query: raise SendError('搜索内容核对失败。')
        deadline = time.monotonic() + 5
        results = []
        while time.monotonic() < deadline:
            results = [c for c, _ in self.walk(self.root()) if c.ControlTypeName == 'ListItemControl'
                       and 'search_item_' in c.AutomationId and
                       (c.Name == person.display_name or c.AutomationId.split('search_item_', 1)[-1] == person.display_name)]
            if results: break
            time.sleep(.15)
        if len(results) != 1: raise SendError('微信搜索结果无法唯一对应目标联系人，未执行发送。')
        # Qt's SelectionItem.Select is a no-op here, and Enter may open search history.
        # Click the uniquely matched result, then verify the actual conversation editor.
        self.click(results[0])
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            current = self.input()
            if current is not None and current.Name == person.display_name:
                return current
            time.sleep(.1)
        raise SendError('聊天对象核对失败，未执行发送。')

    def stage_text(self, person, message):
        edit = self.open_chat(person)
        value = edit.GetValuePattern()
        if value.Value: raise SendError('该联系人输入框已有草稿，已跳过并保留原草稿。')
        edit.SetFocus(); value.SetValue(message, waitTime=.05)
        if edit.Name != person.display_name or value.Value != message:
            raise SendError('接收人或待发内容读回核对失败，未执行发送。')
        return edit

    def dispatch(self, edit, person, message):
        self.check_cancelled()
        if edit.Name != person.display_name or edit.GetValuePattern().Value != message:
            raise SendError('发送前内容或聊天对象发生变化，未执行发送。')
        self.user.ShowWindow(self.hwnd, 6)
        edit.SetFocus()
        if self.original_foreground and self.original_foreground != self.hwnd:
            self.foreground(self.original_foreground)
        # Targeted messages enter this Weixin window's event queue, even when another app is active.
        if not self.user.PostMessageW(self.hwnd, 0x100, 13, 0x001c0001):
            raise SendError('微信发送事件投递失败。')
        self.user.PostMessageW(self.hwnd, 0x101, 13, 0xc01c0001)

    def close(self):
        try:
            if self.memory:
                if self.original_byte == b'\0' and self.read_memory(self.accessibility_address, 1) == b'\1':
                    self.write_byte(b'\0')
        finally:
            if self.memory: self.kernel.CloseHandle(self.memory); self.memory = None
            if self.mutex: self.kernel.ReleaseMutex(self.mutex); self.kernel.CloseHandle(self.mutex); self.mutex = None
            self.user.SetWindowPlacement(self.hwnd, ctypes.byref(self.placement))
            if not self.was_visible: self.user.ShowWindow(self.hwnd, 0)
            if self.original_foreground and self.original_foreground != self.hwnd:
                self.foreground(self.original_foreground)
