import sys
import subprocess
import time
import json
import argparse

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

def find_detail_panel(wechat_window):
    # Search the window for any text control containing "微信号" or "WeChat ID" (Simplified/Traditional/English)
    wechat_keys = ["微信号", "WeChat ID", "微訊號", "微信號"]
    for child, depth in uia.WalkControl(wechat_window):
        if child.ControlType == uia.ControlType.TextControl:
            name = child.Name
            if name and any(k in name for k in wechat_keys):
                # Climb up to find the container that holds both this text and the send message button
                curr = child
                for _ in range(5):
                    curr = curr.GetParentControl()
                    if curr is None:
                        break
                    # Check if this container contains the send button (Simplified/Traditional/English)
                    btn_names = [
                        "发消息", "进入公众号", "关注", "发送消息", "Send Message",
                        "發消息", "發送訊息", "傳送訊息", "進入公眾號", "關注", "發送消息"
                    ]
                    for btn_name in btn_names:
                        if curr.ButtonControl(Name=btn_name).Exists(0.02):
                            return curr
    return None

def parse_profile_panel(panel):
    texts = []
    try:
        # WalkControl is a robust way to traverse all children under a control
        for child, depth in uia.WalkControl(panel):
            if child.ControlType == uia.ControlType.TextControl:
                texts.append(child)
    except Exception:
        pass
    text_names = [t.Name for t in texts if t.Name]
    
    details = {
        'nickname': '',
        'wechat_id': '',
        'remark': '',
        'region': ''
    }
    
    # Matching labels (Simplified/Traditional/English)
    wechat_labels = [
        "微信号：", "微信号:", "WeChat ID:", "WeChat ID：",
        "微訊號：", "微訊號:", "微信號：", "微信號:"
    ]
    remark_labels = [
        "备注名：", "备注名:", "备注：", "备注:", "Remark:", "Remark：",
        "備註名：", "備註名:", "備註：", "備註:"
    ]
    nickname_labels = [
        "昵称：", "昵称:", "Nickname:", "Nickname：",
        "暱稱：", "暱稱:", "暱稱"
    ]
    region_labels = [
        "地区：", "地区:", "Region:", "Region：",
        "地區：", "地區:"
    ]
    
    for idx, name in enumerate(text_names):
        name_clean = name.strip()
        
        # Check WeChat ID
        for wl in wechat_labels:
            if wl in name_clean:
                parts = name_clean.split(wl)
                if len(parts) > 1 and parts[1].strip():
                    details['wechat_id'] = parts[1].strip()
                elif idx + 1 < len(text_names):
                    details['wechat_id'] = text_names[idx+1].strip()
                break
                
        # Check Remark
        for rl in remark_labels:
            if rl in name_clean:
                parts = name_clean.split(rl)
                if len(parts) > 1 and parts[1].strip():
                    details['remark'] = parts[1].strip()
                elif idx + 1 < len(text_names):
                    details['remark'] = text_names[idx+1].strip()
                break
                
        # Check Nickname
        for nl in nickname_labels:
            if nl in name_clean:
                parts = name_clean.split(nl)
                if len(parts) > 1 and parts[1].strip():
                    details['nickname'] = parts[1].strip()
                elif idx + 1 < len(text_names):
                    details['nickname'] = text_names[idx+1].strip()
                break
                
        # Check Region
        for rgl in region_labels:
            if rgl in name_clean:
                parts = name_clean.split(rgl)
                if len(parts) > 1 and parts[1].strip():
                    details['region'] = parts[1].strip()
                elif idx + 1 < len(text_names):
                    details['region'] = text_names[idx+1].strip()
                break

    # If nickname not found via "昵称：" label
    if not details['nickname']:
        excluded = [
            "微信号", "备注", "地区", "发消息", "进入公众号", "关注", "发送消息", "功能介绍", "帐号主体", "Send Message", "WeChat ID", "Remark", "Region",
            "微訊號", "微信號", "備註", "地區", "發消息", "發送訊息", "傳送訊息", "進入公眾號", "關注", "發送消息", "功能介紹", "帳號主體"
        ]
        for name in text_names:
            name_clean = name.strip()
            if name_clean and not any(k in name_clean for k in excluded) and len(name_clean) < 40:
                details['nickname'] = name_clean
                break
                
    # If first text is different from nickname and we got nickname, it is the remark
    if text_names and not details['remark']:
        first_text = text_names[0].strip()
        excluded = [
            "微信号", "备注", "地区", "发消息", "进入公众号", "关注", "发送消息", "功能介绍", "帐号主体", "Send Message", "WeChat ID", "Remark", "Region",
            "微訊號", "微信號", "備註", "地區", "發消息", "發送訊息", "傳送訊息", "進入公眾號", "關注", "發送消息", "功能介紹", "帳號主體"
        ]
        if first_text and not any(k in first_text for k in excluded) and len(first_text) < 40:
            if details['nickname'] and details['nickname'] != first_text:
                details['remark'] = first_text
                
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

    # 5-second countdown for user preparation
    for i in range(5, 0, -1):
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
        except Exception:
            pass

        last_id = ""
        consecutive_duplicates = 0
        consecutive_none = 0
        max_duplicates = 3
        max_none = 10
        count = 0

        print(json.dumps({"status": "已成功激活微信，开始通过 Down 键遍历好友..."}))
        sys.stdout.flush()

        while count < args.limit:
            time.sleep(0.05)
            
            panel = find_detail_panel(wechat_window)
            if panel:
                consecutive_none = 0
                details = parse_profile_panel(panel)
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
                else:
                    consecutive_duplicates += 1
            else:
                consecutive_none += 1
                
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

        while count < args.limit:
            wechat_window.SwitchToThisWindow()
            time.sleep(0.05)
            
            children = list_ctrl.GetChildren()
            new_items_found = False
            
            for item in children:
                if count >= args.limit:
                    break
                    
                if item.ControlType != uia.ControlType.ListItemControl:
                    continue
                    
                item_name = item.Name
                # Skip group headers and special system items
                if not item_name or item_name in ["新的朋友", "仅聊天的朋友", "群聊", "标签", "公众号", "Enterprise Contacts"]:
                    continue
                    
                rect = item.BoundingRectangle
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
                    
                    item.Click(simulateMove=False)
                    time.sleep(args.delay)
                    
                    panel = find_detail_panel(wechat_window)
                    if panel:
                        details = parse_profile_panel(panel)
                        current_id = details.get('wechat_id') or details.get('nickname')
                        if current_id:
                            print(json.dumps(details, ensure_ascii=False))
                            sys.stdout.flush()
                            count += 1
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

if __name__ == '__main__':
    main()
