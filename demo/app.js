const $ = (id) => document.getElementById(id);

const newClientId = () => (crypto.randomUUID ? crypto.randomUUID() : `client-${Date.now()}-${Math.random().toString(36).slice(2)}`);
let clientId = localStorage.getItem('p2pClientId') || newClientId();
localStorage.setItem('p2pClientId', clientId);

const state = {
  ws: null,
  id: clientId,
  group: null,
  selectedPeer: null,
  connections: new Map(),
};

const rtcConfig = { iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] };

function log(message, kind = 'info') {
  const line = document.createElement('div');
  line.className = `message ${kind}`;
  line.textContent = `[${new Date().toLocaleTimeString()}] ${message}`;
  $('messages').appendChild(line);
  $('messages').scrollTop = $('messages').scrollHeight;
}

function parseCandidate(candidateStr) {
  if (!candidateStr) return null;
  const parts = candidateStr.split(' ');
  if (parts.length < 8) return null;
  const protocol = parts[2];
  const ip = parts[4];
  const port = parts[5];
  const typeIndex = parts.indexOf('typ');
  const type = typeIndex !== -1 ? parts[typeIndex + 1] : 'unknown';
  
  let raddr = '';
  let rport = '';
  const raddrIndex = parts.indexOf('raddr');
  if (raddrIndex !== -1) {
    raddr = parts[raddrIndex + 1];
  }
  const rportIndex = parts.indexOf('rport');
  if (rportIndex !== -1) {
    rport = parts[rportIndex + 1];
  }
  
  return { protocol, ip, port, type, raddr, rport };
}

function logCandidate(prefix, candidateObj) {
  if (!candidateObj) return;
  const typeMap = {
    host: '主机内网 (host)',
    srflx: '公网反射/STUN映射 (srflx)',
    prflx: '对端反射 (prflx)',
    relay: '中继服务器 (relay)'
  };
  const typeDesc = typeMap[candidateObj.type] || candidateObj.type;
  let detail = `[${typeDesc}] IP: ${candidateObj.ip}, 端口: ${candidateObj.port}, 协议: ${candidateObj.protocol.toUpperCase()}`;
  if (candidateObj.raddr && candidateObj.rport) {
    detail += ` (关联局域网: ${candidateObj.raddr}:${candidateObj.rport})`;
  }
  log(`${prefix} ${detail}`, 'info');
}

async function showActiveConnectionInfo(peerId, pc) {
  try {
    // 等待一小会儿，确保统计数据更新
    await new Promise(resolve => setTimeout(resolve, 500));
    const stats = await pc.getStats();
    let activePair = null;
    stats.forEach(report => {
      if (report.type === 'candidate-pair' && report.selected) {
        activePair = report;
      }
    });
    
    if (activePair) {
      const localCandidate = stats.get(activePair.localCandidateId);
      const remoteCandidate = stats.get(activePair.remoteCandidateId);
      if (localCandidate && remoteCandidate) {
        const localTypeDesc = {
          host: '本机内网 IP',
          srflx: '公网反射 IP (STUN)',
          prflx: '对端反射 IP',
          relay: 'TURN 中继 IP'
        }[localCandidate.candidateType] || localCandidate.candidateType;
        
        const remoteTypeDesc = {
          host: '对端内网 IP',
          srflx: '公网反射 IP (STUN)',
          prflx: '对端反射 IP',
          relay: 'TURN 中继 IP'
        }[remoteCandidate.candidateType] || remoteCandidate.candidateType;

        log(`[打洞成功] 已激活物理连接通路：`, 'system');
        log(`-> 本地出口: [${localTypeDesc}] ${localCandidate.ip}:${localCandidate.port} (协议: ${localCandidate.protocol.toUpperCase()})`, 'system');
        log(`-> 远程入口: [${remoteTypeDesc}] ${remoteCandidate.ip}:${remoteCandidate.port} (协议: ${remoteCandidate.protocol.toUpperCase()})`, 'system');
      }
    }
  } catch (err) {
    log(`获取连接详细统计失败: ${err.message}`, 'warn');
  }
}

function setStatus(text) {
  $('status').textContent = text;
}

function sendSignal(payload) {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
    log('中间服务器未连接，无法发送信令。', 'error');
    return;
  }
  state.ws.send(JSON.stringify(payload));
}

function getPeer(peerId) {
  if (!state.connections.has(peerId)) {
    const pc = new RTCPeerConnection(rtcConfig);
    const entry = { pc, channels: {}, connected: false, tunnelOnly: false };

    pc.onicecandidate = ({ candidate }) => {
      if (candidate) {
        sendSignal({ type: 'ice', to: peerId, candidate });
        const parsed = parseCandidate(candidate.candidate);
        logCandidate(`[本地 ICE 候选]`, parsed);
      }
    };
    pc.onconnectionstatechange = () => {
      setStatus(`${peerId}: ${pc.connectionState}`);
      log(`与 ${peerId} 的连接状态变更: ${pc.connectionState}`, 'system');
      if (['failed', 'disconnected', 'closed'].includes(pc.connectionState)) {
        entry.tunnelOnly = true;
        log(`与 ${peerId} 打洞失败或断开，将使用服务器隧道。`, 'warn');
      }
    };
    pc.oniceconnectionstatechange = () => {
      log(`与 ${peerId} 的 ICE 连接状态变更: ${pc.iceConnectionState}`, 'system');
      if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
        showActiveConnectionInfo(peerId, pc);
      }
    };
    pc.ondatachannel = ({ channel }) => bindChannel(peerId, channel, entry);
    state.connections.set(peerId, entry);
  }
  return state.connections.get(peerId);
}

function bindChannel(peerId, channel, entry) {
  entry.channels[channel.label] = channel;
  channel.onopen = () => {
    entry.connected = true;
    log(`${peerId} 的 ${channel.label.toUpperCase()} 通道已打开。`, 'system');
    setStatus(`${peerId}: P2P 已连接`);
  };
  channel.onclose = () => log(`${peerId} 的 ${channel.label.toUpperCase()} 通道已关闭。`, 'warn');
  channel.onerror = () => log(`${peerId} 的 ${channel.label.toUpperCase()} 通道错误。`, 'error');
  channel.onmessage = (event) => log(`${peerId} via ${channel.label.toUpperCase()}: ${event.data}`, 'remote');
}

async function callPeer(peerId) {
  state.selectedPeer = peerId;
  const entry = getPeer(peerId);
  if (!entry.channels.tcp) bindChannel(peerId, entry.pc.createDataChannel('tcp', { ordered: true }), entry);
  if (!entry.channels.udp) bindChannel(peerId, entry.pc.createDataChannel('udp', { ordered: false, maxRetransmits: 0 }), entry);
  const offer = await entry.pc.createOffer();
  await entry.pc.setLocalDescription(offer);
  sendSignal({ type: 'offer', to: peerId, sdp: entry.pc.localDescription });
  log(`已向 ${peerId} 发送 Local Offer 描述，等待对方响应。`, 'system');
}

async function handleOffer(data) {
  state.selectedPeer = data.from;
  const entry = getPeer(data.from);
  log(`收到来自 ${data.from} 的 Offer 请求。`, 'system');
  await entry.pc.setRemoteDescription(data.sdp);
  const answer = await entry.pc.createAnswer();
  await entry.pc.setLocalDescription(answer);
  sendSignal({ type: 'answer', to: data.from, sdp: entry.pc.localDescription });
  log(`已创建 Local Answer 描述并发送给 ${data.from}。`, 'system');
}

async function handleAnswer(data) {
  log(`收到来自 ${data.from} 的 Answer 响应，准备设置 Remote Description。`, 'system');
  await getPeer(data.from).pc.setRemoteDescription(data.sdp);
  log(`${data.from} 已接受连接。`, 'system');
}

async function handleIce(data) {
  try {
    const entry = getPeer(data.from);
    await entry.pc.addIceCandidate(data.candidate);
    const parsed = parseCandidate(data.candidate.candidate);
    logCandidate(`[接收远程 ICE 候选来自 ${data.from}]`, parsed);
  } catch (error) {
    log(`添加 ICE 候选失败：${error.message}`, 'error');
  }
}

function renderPeers(peers) {
  const list = $('peerList');
  list.innerHTML = '';
  if (!peers.length) {
    list.innerHTML = '<li class="empty">当前分组暂无其它节点。</li>';
    return;
  }
  for (const peer of peers) {
    const item = document.createElement('li');
    const button = document.createElement('button');
    button.textContent = `${peer.name} (${peer.id})`;
    button.onclick = () => callPeer(peer.id).catch((error) => log(error.message, 'error'));
    item.appendChild(button);
    list.appendChild(item);
  }
}

function connect() {
  const url = $('serverUrl').value.trim();
  const group = $('groupId').value.trim();
  if (!url || !group) return log('请填写中间服务器地址和分组标识。', 'error');

  state.ws = new WebSocket(url);
  state.group = group;
  state.ws.onopen = () => {
    sendSignal({ type: 'join', id: clientId, group, name: $('displayName').value.trim() });
    setStatus('已连接中间服务器');
  };
  state.ws.onmessage = (event) => {
    const data = JSON.parse(event.data);
    if (data.type === 'joined') {
      state.id = data.id;
      if (data.id !== clientId) {
        clientId = data.id;
        localStorage.setItem('p2pClientId', data.id);
      }
      log(`加入分组 ${data.group}，本机 ID: ${data.id}`, 'system');
    } else if (data.type === 'peers') {
      renderPeers(data.peers);
    } else if (data.type === 'offer') handleOffer(data);
    else if (data.type === 'answer') handleAnswer(data);
    else if (data.type === 'ice') handleIce(data);
    else if (data.type === 'tunnel') log(`${data.from} via TUNNEL: ${data.payload}`, 'remote');
    else if (data.type === 'error') log(data.message, 'error');
  };
  state.ws.onclose = () => setStatus('中间服务器已断开');
  state.ws.onerror = () => log('中间服务器连接错误。', 'error');
}

function sendMessage() {
  const peerId = state.selectedPeer;
  const payload = $('messageInput').value;
  const mode = $('mode').value;
  if (!peerId || !payload) return log('请先选择节点并输入消息。', 'error');
  const entry = getPeer(peerId);
  const channel = entry.channels[mode];
  if (mode === 'tunnel' || entry.tunnelOnly) {
    sendSignal({ type: 'tunnel', to: peerId, payload });
    log(`我 via TUNNEL: ${payload}`, 'local');
  } else if (channel?.readyState === 'open') {
    channel.send(payload);
    log(`我 via ${mode.toUpperCase()}: ${payload}`, 'local');
  } else {
    log(`${mode.toUpperCase()} 通道尚未打开；等待 P2P 打洞完成，失败后才会自动使用隧道。`, 'warn');
    return;
  }
  $('messageInput').value = '';
}

$('connectBtn').onclick = connect;
$('refreshBtn').onclick = () => sendSignal({ type: 'refresh' });
$('sendBtn').onclick = sendMessage;
$('messageInput').onkeydown = (event) => { if (event.key === 'Enter') sendMessage(); };
$('serverUrl').value = 'ws://170.106.158.103:21701';
$('groupId').value = 'arges';
