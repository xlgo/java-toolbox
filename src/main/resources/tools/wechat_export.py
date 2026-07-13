import sys
import subprocess
import time
import json
import argparse
import tkinter as tk
import threading


# Force stdout/stderr to UTF-8 to prevent encoding issues on Windows
if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except Exception:
        pass

# Dependency check
try:
    import uiautomation as uia
except ImportError:
    print(json.dumps({"status": "正在后台安装依赖 uiautomation..."}))
    sys.stdout.flush()
    try:
        subprocess.check_call([sys.executable, "-m", "pip", "install", "uiautomation"])
        import uiautomation as uia
    except Exception as e:
        print(json.dumps({"error": f"无法自动安装依赖 uiautomation: {str(e)}"}))
        sys.stdout.flush()
        sys.exit(1)

class ExportState:
    def __init__(self):
        self.is_paused = False
        self.is_stopped = False
        self.count = 0

class FloatingControlWindow:
    def __init__(self, root, state, wechat_window):
        self.root = root
        self.state = state
        
        root.overrideredirect(True)
        root.attributes("-topmost", True)
        root.attributes("-alpha", 0.95)
        root.configure(bg="#2d2d2d")
        
        width = 240
        height = 36
        
        try:
            rect = wechat_window.BoundingRectangle
            if rect and not (rect.left == 0 and rect.top == 0 and rect.right == 0 and rect.bottom == 0):
                x = rect.left + 10
                y = rect.top + 10
            else:
                raise ValueError("Invalid rect")
        except Exception:
            screen_width = root.winfo_screenwidth()
            screen_height = root.winfo_screenheight()
            x = screen_width - width - 80
            y = screen_height - height - 160
            
        root.geometry(f"{width}x{height}+{x}+{y}")
        
        self.drag_data = {"x": 0, "y": 0}
        root.bind("<Button-1>", self.start_drag)
        root.bind("<B1-Motion>", self.drag)
        
        # Use a main frame to pack horizontally
        main_frame = tk.Frame(root, bg="#2d2d2d")
        main_frame.pack(fill="both", expand=True, padx=8, pady=4)
        
        self.progress_label = tk.Label(
            main_frame, text="已导出: 0 人",
            fg="#ffffff", bg="#2d2d2d", font=("Microsoft YaHei", 9, "bold")
        )
        self.progress_label.pack(side="left", padx=(5, 10))
        
        self.stop_btn = tk.Button(
            main_frame, text=" ⏹ ", command=self.stop_export,
            fg="#ffffff", bg="#d13438", activeforeground="#ffffff", activebackground="#a80000",
            font=("Segoe UI Symbol", 9), bd=0, relief="flat", padx=6, pady=1
        )
        self.stop_btn.pack(side="right", padx=3)
        
        self.pause_btn = tk.Button(
            main_frame, text=" ⏸ ", command=self.toggle_pause,
            fg="#ffffff", bg="#0078d4", activeforeground="#ffffff", activebackground="#106ebe",
            font=("Segoe UI Symbol", 9), bd=0, relief="flat", padx=6, pady=1
        )
        self.pause_btn.pack(side="right", padx=3)
        
        self.update_loop()
        
    def start_drag(self, event):
        self.drag_data["x"] = event.x
        self.drag_data["y"] = event.y
        
    def drag(self, event):
        dx = event.x - self.drag_data["x"]
        dy = event.y - self.drag_data["y"]
        x = self.root.winfo_x() + dx
        y = self.root.winfo_y() + dy
        self.root.geometry(f"+{x}+{y}")
        
    def toggle_pause(self):
        self.state.is_paused = not self.state.is_paused
        if self.state.is_paused:
            self.pause_btn.configure(text=" ▶ ", bg="#107c41", activebackground="#0b5930")
            self.progress_label.configure(text=f"已导出: {self.state.count} 人 (暂停)", fg="#ffaa00")
        else:
            self.pause_btn.configure(text=" ⏸ ", bg="#0078d4", activebackground="#106ebe")
            self.progress_label.configure(text=f"已导出: {self.state.count} 人", fg="#ffffff")
            
    def stop_export(self):
        self.state.is_stopped = True
        self.root.destroy()
        
    def update_loop(self):
        if not self.state.is_stopped:
            if not self.state.is_paused:
                self.progress_label.configure(text=f"已导出: {self.state.count} 人", fg="#ffffff")
            self.root.after(200, self.update_loop)

def check_state(state):
    if state.is_stopped:
        print(json.dumps({"status": "用户已点击结束，导出停止。"}, ensure_ascii=False))
        sys.stdout.flush()
        sys.exit(0)
    while state.is_paused:
        time.sleep(0.1)
        if state.is_stopped:
            print(json.dumps({"status": "用户已点击结束，导出停止。"}, ensure_ascii=False))
            sys.stdout.flush()
            sys.exit(0)

def find_detail_panel(wechat_window):
    # 还原为原本 100% 可工作的全局遍历，不作任何深度限制或剪枝，保证兼容性
    wechat_keys = ["微信号", "WeChat ID", "微訊號", "微信號"]
    for child, depth in uia.WalkControl(wechat_window):
        if child.ControlType == uia.ControlType.TextControl:
            name = child.Name
            if name and any(k in name for k in wechat_keys):
                curr = child
                for _ in range(5):
                    curr = curr.GetParentControl()
                    if curr is None:
                        break
                    btn_names = [
                        "发消息", "进入公众号", "关注", "发送消息", "Send Message",
                        "發消息", "發送訊息", "傳送訊息", "進入公眾號", "關注", "發送消息"
                    ]
                    for btn_name in btn_names:
                        try:
                            if curr.ButtonControl(Name=btn_name).Exists(0.02):
                                return curr
                        except Exception:
                            pass
    return None

def parse_profile_panel(panel):
    texts = []
    all_controls_info = []
    try:
        for child, depth in uia.WalkControl(panel):
            if child.ControlType == uia.ControlType.TextControl:
                texts.append(child)
            
            rect = child.BoundingRectangle
            rect_list = [rect.left, rect.top, rect.right, rect.bottom] if rect else None
            control_info = {
                "depth": depth,
                "name": child.Name or "",
                "type": getattr(child, "ControlTypeName", str(child.ControlType)),
                "class": child.ClassName or "",
                "rect": rect_list
            }
            all_controls_info.append(control_info)
    except Exception:
        pass
    text_names = [t.Name for t in texts if t.Name]
    
    print("[DEBUG_PANEL_CONTENT] " + json.dumps({"text_names": text_names, "controls": all_controls_info}, ensure_ascii=False))
    sys.stdout.flush()
    
    details = {
        'nickname': '',
        'wechat_id': '',
        'remark': '',
        'region': '',
        'tag': '',
        'gender': 0
    }
    
    # 统一定义不含冒号的基础标签
    wechat_keys = ["微信号", "WeChat ID", "微訊號", "微信號"]
    remark_keys = ["备注名", "备注", "Remark", "備註名", "備註"]
    nickname_keys = ["昵称", "Nickname", "暱稱"]
    region_keys = ["地区", "Region", "地區"]
    tag_keys = ["标签", "標籤", "Tag", "Tags"]
    
    for idx, name in enumerate(text_names):
        name_clean = name.strip()
        # 移除全角和半角冒号以及空格来进行规范化比对
        name_normalized = name_clean.replace("：", "").replace(":", "").replace(" ", "").strip()
        
        # 1. 匹配微信号
        for k in wechat_keys:
            k_norm = k.replace(" ", "")
            if name_normalized.startswith(k_norm):
                val = name_normalized[len(k_norm):].strip()
                if not val and idx + 1 < len(text_names):
                    val = text_names[idx+1].strip()
                if val:
                    details['wechat_id'] = val
                break
                
        # 2. 匹配备注名
        for k in remark_keys:
            k_norm = k.replace(" ", "")
            if name_normalized.startswith(k_norm):
                val = name_normalized[len(k_norm):].strip()
                if not val and idx + 1 < len(text_names):
                    val = text_names[idx+1].strip()
                if val:
                    details['remark'] = val
                break
                
        # 3. 匹配昵称
        for k in nickname_keys:
            k_norm = k.replace(" ", "")
            if name_normalized.startswith(k_norm):
                val = name_normalized[len(k_norm):].strip()
                if not val and idx + 1 < len(text_names):
                    val = text_names[idx+1].strip()
                if val:
                    details['nickname'] = val
                break
                
        # 4. 匹配地区
        for k in region_keys:
            k_norm = k.replace(" ", "")
            if name_normalized.startswith(k_norm):
                val = name_normalized[len(k_norm):].strip()
                if not val and idx + 1 < len(text_names):
                    val = text_names[idx+1].strip()
                if val:
                    details['region'] = val
                break
                
        # 5. 匹配标签
        for k in tag_keys:
            k_norm = k.replace(" ", "")
            if name_normalized.startswith(k_norm):
                val = name_normalized[len(k_norm):].strip()
                if not val and idx + 1 < len(text_names):
                    val = text_names[idx+1].strip()
                if val:
                    details['tag'] = val
                break

    # If nickname not found via label
    if not details['nickname']:
        excluded = [
            "微信号", "备注", "地区", "标签", "发消息", "进入公众号", "关注", "发送消息", "功能介绍", "帐号主体", "Send Message", "WeChat ID", "Remark", "Region", "Tag", "Tags",
            "微訊號", "微信號", "備註", "地區", "標籤", "發消息", "發送訊息", "傳送訊息", "進入公眾號", "關注", "發送消息", "功能介紹", "帳號主體"
        ]
        for name in text_names:
            name_clean = name.strip()
            name_norm = name_clean.replace("：", "").replace(":", "").replace(" ", "").strip()
            if name_clean and not any(k.replace(" ", "") in name_norm for k in excluded) and len(name_clean) < 40:
                details['nickname'] = name_clean
                break
                
    # If first text is different from nickname and we got nickname, it is the remark
    if text_names and not details['remark']:
        first_text = text_names[0].strip()
        excluded = [
            "微信号", "备注", "地区", "标签", "发消息", "进入公众号", "关注", "发送消息", "功能介绍", "帐号主体", "Send Message", "WeChat ID", "Remark", "Region", "Tag", "Tags",
            "微訊號", "微信號", "備註", "地區", "標籤", "發消息", "發送訊息", "傳送訊息", "進入公眾號", "關注", "發送消息", "功能介紹", "帳号主體"
        ]
        first_norm = first_text.replace("：", "").replace(":", "").replace(" ", "").strip()
        if first_text and not any(k.replace(" ", "") in first_norm for k in excluded) and len(first_text) < 40:
            if details['nickname'] and details['nickname'] != first_text:
                details['remark'] = first_text
                
    # Detect gender
    for child, depth in uia.WalkControl(panel):
        name = child.Name
        if name:
            name_clean = name.strip()
            if child.ControlType in [uia.ControlType.ImageControl, uia.ControlType.CustomControl] or (child.ControlType == uia.ControlType.TextControl and name_clean in ["男", "女", "Male", "Female"] and name_clean != details['nickname']):
                if name_clean in ["男", "Male"]:
                    details['gender'] = 1
                    break
                elif name_clean in ["女", "Female"]:
                    details['gender'] = 2
                    break
                    
    return details

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--limit', type=int, default=1000, help='Maximum number of contacts to fetch')
    parser.add_argument('--delay', type=float, default=0.3, help='Delay between down-presses')
    parser.add_argument('--mode', type=str, default='keyboard', choices=['keyboard', 'mouse'], help='Traversal mode')
    args = parser.parse_args()

    # Find WeChat Window with multiple fallback strategies
    wechat_window = uia.WindowControl(ClassName='WeChatMainWndForPC')
    if not wechat_window.Exists(1):
        wechat_window = uia.WindowControl(ClassName='QMainWindow', Name='微信')
        if not wechat_window.Exists(0.5):
            wechat_window = uia.WindowControl(ClassName='QMainWindow', Name='WeChat')
            if not wechat_window.Exists(0.5):
                wechat_window = uia.WindowControl(Name='微信')
                if not wechat_window.Exists(0.5):
                    wechat_window = uia.WindowControl(Name='WeChat')
                    if not wechat_window.Exists(0.5):
                        # Get list of top-level windows for debugging
                        win_list = []
                        try:
                            for w in uia.GetRootControl().GetChildren():
                                if w.Name or w.ClassName:
                                    win_list.append(f"- Name: {w.Name} | Class: {w.ClassName}")
                        except Exception as e:
                            win_list.append(f"获取窗口列表失败: {str(e)}")
                        
                        debug_info = "\n".join(win_list[:15])
                        err_msg = f"未找到微信窗口，请确认微信已启动并置于可见状态！\n\n系统当前顶级窗口列表 (前15个)：\n{debug_info}"
                        print(json.dumps({"error": err_msg}))
                        sys.stdout.flush()
                        sys.exit(1)

def run_export_logic(wechat_window, args, state):
    # 5-second countdown for user preparation
    for i in range(5, 0, -1):
        check_state(state)
        print(json.dumps({"status": f"自动获取即将在 {i} 秒后开始，请确保微信已启动，并在列表中点击选中了首个好友..."}))
        sys.stdout.flush()
        time.sleep(1)

    # Bring to front and restore if minimized
    try:
        if wechat_window.IsMinimized():
            wechat_window.ShowWindow(9)  # SW_RESTORE
    except Exception:
        pass
    wechat_window.SwitchToThisWindow()
    time.sleep(0.5)

    # 检测权限隔离（如果微信以管理员权限运行，而本工具不是，将无法读取到任何子元素）
    try:
        children = wechat_window.GetChildren()
        if not children or len(children) == 0:
            walk_count = 0
            for _, _ in uia.WalkControl(wechat_window, maxDepth=2):
                walk_count += 1
                break
            if walk_count == 0:
                print(json.dumps({"error": "无法读取微信界面元素，可能是由于权限不足。如果微信是以“管理员身份”运行的，本群发助手也必须以“管理员身份”运行。请右键本助手，选择“以管理员身份运行”重新试试。"}))
                sys.stdout.flush()
                sys.exit(1)
    except Exception as e:
        print(json.dumps({"error": f"访问微信窗口受阻: {str(e)}。请尝试以管理员身份运行本群发助手。"}))
        sys.stdout.flush()
        sys.exit(1)

    if args.mode == 'keyboard':
        # Keyboard Mode Loop
        try:
            list_ctrl = None
            for name in ["联系人", "Contacts", "聯絡人", "通讯录", "通訊錄"]:
                list_ctrl = wechat_window.ListControl(Name=name)
                if list_ctrl.Exists(0.1):
                    break
            if not list_ctrl or not list_ctrl.Exists(0.01):
                list_ctrl = wechat_window.ListControl()
            if list_ctrl.Exists(0.5):
                list_ctrl.SetFocus()
                try:
                    list_ctrl.Click(simulateMove=False)
                except Exception:
                    pass
        except Exception:
            pass

        last_id = ""
        consecutive_duplicates = 0
        consecutive_none = 0
        max_duplicates = 3
        max_none = 10
        count = 0

        print(json.dumps({"status": "已成功激活微信，正在初始化定位详情面板..."}))
        sys.stdout.flush()

        panel = find_detail_panel(wechat_window)
        if panel:
            print(json.dumps({"status": "面板定位成功，开始通过 Down 键遍历好友..."}))
        else:
            print(json.dumps({"status": "警告：未能在微信右侧定位到详情面板，将在遍历时重试..."}))
        sys.stdout.flush()

        while count < args.limit:
            check_state(state)
            time.sleep(0.05)
            
            details = None
            if panel:
                try:
                    details = parse_profile_panel(panel)
                except Exception:
                    panel = None
            
            if not panel or not details or not (details.get('wechat_id') or details.get('nickname')):
                panel = find_detail_panel(wechat_window)
                if panel:
                    try:
                        details = parse_profile_panel(panel)
                    except Exception:
                        details = None
            
            if panel and details:
                consecutive_none = 0
                current_id = details.get('wechat_id') or details.get('nickname')
                
                if current_id:
                    if current_id == last_id:
                        consecutive_duplicates += 1
                    else:
                        consecutive_duplicates = 0
                        last_id = current_id
                        
                        # Print contact as JSON line
                        print(json.dumps(details, ensure_ascii=False))
                        sys.stdout.flush()
                        count += 1
                        state.count = count
                else:
                    consecutive_duplicates += 1
            else:
                consecutive_none += 1
                print(json.dumps({"status": f"未检测到详情面板 (连续 {consecutive_none} 次)"}, ensure_ascii=False))
                print(f"[DEBUG_PANEL_NONE] 详情面板未找到 (consecutive_none={consecutive_none})")
                sys.stdout.flush()
                
            if consecutive_duplicates >= max_duplicates:
                print(json.dumps({"status": f"已连续 {max_duplicates} 次获取到重复数据，获取完成！"}))
                sys.stdout.flush()
                break

            if consecutive_none >= max_none:
                print(json.dumps({"status": f"已连续 {max_none} 次未检测到好友详情面板，获取完成！"}))
                sys.stdout.flush()
                break
                
            # Send DOWN arrow key
            wechat_window.SendKeys('{DOWN}')
            check_state(state)
            time.sleep(args.delay)
            
    else:
        # Mouse Mode Loop (Click + Wheel)
        list_ctrl = None
        for child, depth in uia.WalkControl(wechat_window):
            if child.ControlType == uia.ControlType.ListControl:
                if child.Name in ["联系人", "Contacts", "聯絡人", "通讯录", "通訊錄"] or len(child.GetChildren()) > 3:
                    list_ctrl = child
                    break
                    
        if not list_ctrl:
            list_ctrl = wechat_window.ListControl()
            
        if not list_ctrl.Exists(1):
            print(json.dumps({"error": "未找到联系人列表控件，请确保您已切换至微信『通讯录』界面！"}))
            sys.stdout.flush()
            sys.exit(1)

        print(json.dumps({"status": "已成功绑定微信通讯录列表，开始使用鼠标自动抓取..."}))
        sys.stdout.flush()

        visited = set()
        consecutive_no_new = 0
        max_no_new_attempts = 4
        count = 0
        panel = None

        while count < args.limit:
            check_state(state)
            wechat_window.SwitchToThisWindow()
            time.sleep(0.05)
            
            children = list_ctrl.GetChildren()
            new_items_found = False
            
            for item in children:
                if count >= args.limit:
                    break
                check_state(state)
                    
                if item.ControlType != uia.ControlType.ListItemControl:
                    continue
                    
                item_name = item.Name
                # Skip group headers and special system items
                if not item_name or item_name in ["新的朋友", "仅聊天的朋友", "群聊", "标签", "公众号", "Enterprise Contacts"]:
                    continue
                    
                rect = item.BoundingRectangle
                # 过滤不可见/没有实际渲染出大小的虚拟化元素，防止坐标全为 0 时点击报错
                if rect.left == 0 and rect.top == 0 and rect.right == 0 and rect.bottom == 0:
                    continue
                if (rect.right - rect.left <= 0) or (rect.bottom - rect.top <= 0):
                    continue
                    
                item_key = f"{item_name}_{rect.left}_{rect.top}"
                if item_key in visited:
                    continue
                    
                visited.add(item_key)
                new_items_found = True
                consecutive_no_new = 0
                
                try:
                    try:
                        item.ScrollIntoView()
                    except Exception:
                        pass
                    time.sleep(0.05)
                    check_state(state)
                    
                    item.Click(simulateMove=False)
                    check_state(state)
                    time.sleep(args.delay)
                    
                    details = None
                    if panel:
                        try:
                            details = parse_profile_panel(panel)
                        except Exception:
                            panel = None
                    
                    if not panel or not details or not (details.get('wechat_id') or details.get('nickname')):
                        panel = find_detail_panel(wechat_window)
                        if panel:
                            try:
                                details = parse_profile_panel(panel)
                            except Exception:
                                details = None
                    
                    if panel and details:
                        current_id = details.get('wechat_id') or details.get('nickname')
                        if current_id:
                            print(json.dumps(details, ensure_ascii=False))
                            sys.stdout.flush()
                            count += 1
                            state.count = count
                except Exception:
                    pass

            if not new_items_found:
                consecutive_no_new += 1
                try:
                    list_ctrl.WheelDown(wheelTimes=3)
                except Exception:
                    list_ctrl.SendKeys('{PAGEDOWN}')
                time.sleep(0.5)
                
            if consecutive_no_new >= max_no_new_attempts:
                print(json.dumps({"status": "已到达通讯录底部，获取完成！"}))
                sys.stdout.flush()
                break

    print(json.dumps({"status": f"自动获取完成，本次共成功获取到 {count} 个联系人！"}))
    sys.stdout.flush()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--limit', type=int, default=1000, help='Maximum number of contacts to fetch')
    parser.add_argument('--delay', type=float, default=0.3, help='Delay between down-presses')
    parser.add_argument('--mode', type=str, default='keyboard', choices=['keyboard', 'mouse'], help='Traversal mode')
    args = parser.parse_args()

    # Find WeChat Window with multiple fallback strategies
    wechat_window = uia.WindowControl(ClassName='WeChatMainWndForPC')
    if not wechat_window.Exists(1):
        wechat_window = uia.WindowControl(ClassName='QMainWindow', Name='微信')
        if not wechat_window.Exists(0.5):
            wechat_window = uia.WindowControl(ClassName='QMainWindow', Name='WeChat')
            if not wechat_window.Exists(0.5):
                wechat_window = uia.WindowControl(Name='微信')
                if not wechat_window.Exists(0.5):
                    wechat_window = uia.WindowControl(Name='WeChat')
                    if not wechat_window.Exists(0.5):
                        # Get list of top-level windows for debugging
                        win_list = []
                        try:
                            for w in uia.GetRootControl().GetChildren():
                                if w.Name or w.ClassName:
                                    win_list.append(f"- Name: {w.Name} | Class: {w.ClassName}")
                        except Exception as e:
                            win_list.append(f"获取窗口列表失败: {str(e)}")
                        
                        debug_info = "\n".join(win_list[:15])
                        err_msg = f"未找到微信窗口，请确认微信已启动并置于可见状态！\n\n系统当前顶级窗口列表 (前15个)：\n{debug_info}"
                        print(json.dumps({"error": err_msg}))
                        sys.stdout.flush()
                        sys.exit(1)

    state = ExportState()

    def export_thread_func():
        try:
            run_export_logic(wechat_window, args, state)
        except Exception as e:
            print(json.dumps({"error": f"后台导出线程意外中止: {str(e)}"}, ensure_ascii=False))
            sys.stdout.flush()
        finally:
            state.is_stopped = True
            try:
                root.after(0, root.destroy)
            except Exception:
                pass

    t = threading.Thread(target=export_thread_func)
    t.daemon = True
    t.start()

    root = tk.Tk()
    app = FloatingControlWindow(root, state, wechat_window)
    root.mainloop()

if __name__ == '__main__':
    main()
