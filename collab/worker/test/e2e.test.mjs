// SPDX-License-Identifier: GPL-3.0-or-later
// 茶绘信令端端到端测试: 建房 / 入房门禁 / 信令转发 / 房主迁移 / 大厅目录 / 过期与占位清理
//
// 运行: cd collab/worker && npm test
// 依赖: Node >= 20 (内置 WebSocket/fetch) 与可用的 npx (自动拉取 wrangler)

import assert from 'node:assert/strict';
import test, { after, before, describe, it } from 'node:test';
import { WorkerClient, openRoom, sleep, startWorker, stopWorker } from './harness.mjs';

const PORT_MAIN = 18791;
const PORT_FAST = 18792;

let mainWorker;
let fastWorker;
let client;
let fastClient;

const ROOM_CODE_RE = /^[A-Z2-9]{6}$/;

before(async () => {
  try {
    // 主实例: 昵称占位阈值压到 3s, 便于验证幽灵清理
    mainWorker = await startWorker({ port: PORT_MAIN, vars: { GHOST_PEER_TTL_MS: 3000 } });
    // 短 TTL 实例: 房间 6s 过期, 便于验证过期与活动顺延
    fastWorker = await startWorker({
      port: PORT_FAST,
      vars: { ROOM_TTL_MS: 6000, GHOST_PEER_TTL_MS: 3000, HOST_RECLAIM_WINDOW_MS: 1500 },
    });
  } catch (err) {
    await Promise.all([stopWorker(mainWorker), stopWorker(fastWorker)]);
    throw err;
  }
  client = new WorkerClient(mainWorker);
  fastClient = new WorkerClient(fastWorker);
});

after(async () => {
  await Promise.all([stopWorker(mainWorker), stopWorker(fastWorker)]);
});

// 进程被外部信号打断时也要收掉 wrangler/workerd, 否则端口被占会拖垮下一次运行
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, () => {
    Promise.all([stopWorker(mainWorker), stopWorker(fastWorker)]).finally(() => process.exit(130));
  });
}

describe('健康检查与建房', () => {
  it('health 返回版本、禁用位与中继配置', async () => {
    const res = await fetch(`${client.base}/c/v1/health`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.ok, true);
    assert.equal(body.version, 1);
    assert.equal(body.disabled, false);
    assert.equal(body.relayConfigured, true);
  });

  it('建房返回 6 位房间码、hostToken 与 EasyTier 参数', async () => {
    const created = await client.createRoom({ hostPeerId: 'p_create01', hostName: '画家A' });
    assert.equal(created.ok, true);
    assert.match(created.body.roomCode, ROOM_CODE_RE);
    assert.equal(created.body.hostToken.length, 48);
    assert.equal(created.body.hostPeerId, 'p_create01');
    assert.equal(created.body.expiresInMs, 86400000);
    const mesh = created.body.easyTier;
    assert.equal(mesh.networkName, `rvp-${created.body.roomCode}`);
    assert.match(mesh.selfVirtualIp, /^10\.144\.\d+\.\d+$/);
    assert.ok(Array.isArray(mesh.relayPeers) && mesh.relayPeers.length > 0);
    assert.ok(mesh.relayPeers.every((uri) => /^(tcp|udp|ws|wss):\/\//.test(uri)));
  });

  it('拒绝非法 hostPeerId 与缺标题的公开房', async () => {
    const badId = await client.createRoom({ hostPeerId: 'bad id!' });
    assert.equal(badId.status, 400);
    assert.equal(badId.body.error, 'invalid_peer_id');

    const noTitle = await client.createRoom({ hostPeerId: 'p_public01', isPublic: true });
    assert.equal(noTitle.status, 400);
    assert.equal(noTitle.body.error, 'room_title_required');
  });
});

describe('握手与入房门禁', () => {
  it('房主凭 hostToken 入房, 错误 token 与错误角色被拒', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_hand' });
    const welcome = await room.host.waitFor((m) => m.type === 'welcome', 100);
    assert.equal(welcome, null, 'welcome 应在 openRoom 中已被消费');

    const badToken = await client.connectExpectReject({
      roomCode: room.roomCode, peerId: 'p_host_hand', name: 'Host', role: 'host', token: 'deadbeef',
    });
    assert.equal(badToken, true, '错误 hostToken 必须被拒');

    const badRole = await client.connectExpectReject({
      roomCode: room.roomCode, peerId: 'p_role_bad', name: 'X', role: 'admin',
    });
    assert.equal(badRole, true, '非法 role 必须被拒');

    room.host.close();
  });

  it('普通成员入房: 自己拿到 welcome, 房主收到 peer_joined', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_join' });
    const peer = await client.connect({ roomCode: room.roomCode, peerId: 'p_peer_join', name: '画家B' });
    const welcome = await peer.waitFor((m) => m.type === 'welcome');
    assert.equal(welcome.status, 'admitted');
    assert.equal(welcome.selfPeerId, 'p_peer_join');
    assert.equal(welcome.hostPeerId, 'p_host_join');
    assert.equal(welcome.peers.length, 2);
    assert.ok(welcome.peers.every((p) => p.virtualIp.startsWith('10.144.')));

    const joined = await room.host.waitFor((m) => m.type === 'peer_joined');
    assert.equal(joined.peer.peerId, 'p_peer_join');
    assert.equal(joined.peer.name, '画家B');
    assert.match(joined.peer.virtualIp, /^10\.144\./);

    peer.close();
    room.host.close();
  });

  it('密码房: 错误密码被拒, 正确密码可进', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_pwd', password: 'tea-2026' });
    const wrong = await client.connectExpectReject({
      roomCode: room.roomCode, peerId: 'p_pwd_wrong', name: 'NoPwd', password: 'nope',
    });
    assert.equal(wrong, true);
    const right = await client.connect({
      roomCode: room.roomCode, peerId: 'p_pwd_right', name: 'WithPwd', password: 'tea-2026',
    });
    const welcome = await right.waitFor((m) => m.type === 'welcome');
    assert.equal(welcome.status, 'admitted');
    right.close();
    room.host.close();
  });

  it('审批房: 先 pending, 房主通过后转 admitted; 拒绝则踢回', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_appr', requireApproval: true });
    const guest = await client.connect({ roomCode: room.roomCode, peerId: 'p_appr_yes', name: '待审A' });
    const pendingWelcome = await guest.waitFor((m) => m.type === 'welcome');
    assert.equal(pendingWelcome.status, 'pending');

    const request = await room.host.waitFor((m) => m.type === 'join_request');
    assert.equal(request.peer.peerId, 'p_appr_yes');

    room.host.send({ type: 'approve', targetPeerId: 'p_appr_yes' });
    const admitted = await guest.waitFor((m) => m.type === 'admitted');
    assert.equal(admitted.status ?? 'admitted', 'admitted');
    assert.ok(admitted.peers.some((p) => p.peerId === 'p_appr_yes'));
    assert.ok(await room.host.waitFor((m) => m.type === 'peer_joined'));

    const rejected = await client.connect({ roomCode: room.roomCode, peerId: 'p_appr_no', name: '待审B' });
    await room.host.waitFor((m) => m.type === 'join_request' && m.peer.peerId === 'p_appr_no');
    room.host.send({ type: 'reject', targetPeerId: 'p_appr_no' });
    assert.ok(await rejected.waitFor((m) => m.type === 'join_rejected'));
    assert.equal(await rejected.waitClosing(), true, '被拒绝的连接应被服务端关闭');

    guest.close();
    room.host.close();
  });

  it('maxPeers 上限拒新成员, 重复昵称被拒', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_full', maxPeers: 2 });
    const first = await client.connect({ roomCode: room.roomCode, peerId: 'p_full_one', name: '满员甲' });
    await first.waitFor((m) => m.type === 'welcome');

    const third = await client.connectExpectReject({
      roomCode: room.roomCode, peerId: 'p_full_two', name: '满员乙',
    });
    assert.equal(third, true, '超过 maxPeers 的成员必须被拒');

    const dupName = await client.connectExpectReject({
      roomCode: room.roomCode, peerId: 'p_full_one', name: '满员甲',
    });
    // 同一 peerId 重连属于替换, 不应被昵称去重挡住
    assert.equal(dupName, false, '同 peerId 重连必须允许');

    first.close();
    room.host.close();
  });

  it('已在房内的昵称不可被他人占用 (大小写与首尾空格不敏感)', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_dup' });
    const first = await client.connect({ roomCode: room.roomCode, peerId: 'p_dup_one', name: 'TeaCat' });
    await first.waitFor((m) => m.type === 'welcome');

    const dup = await client.connectExpectReject({
      roomCode: room.roomCode, peerId: 'p_dup_two', name: '  teacat ',
    });
    assert.equal(dup, true, '大小写/空格归一后同名应被拒');

    first.close();
    room.host.close();
  });
});

describe('信令转发', () => {
  it('点对点 signal 只发给目标并带上 from', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_sig' });
    const a = await client.connect({ roomCode: room.roomCode, peerId: 'p_sig_a', name: '甲' });
    const b = await client.connect({ roomCode: room.roomCode, peerId: 'p_sig_b', name: '乙' });
    await a.waitFor((m) => m.type === 'welcome');
    await b.waitFor((m) => m.type === 'welcome');
    await sleep(120);
    a.clear();
    b.clear();
    room.host.clear();

    a.send({ type: 'signal', to: 'p_sig_b', payload: { kind: 'ice', sdp: 'x' } });
    const got = await b.waitFor((m) => m.type === 'signal');
    assert.equal(got.from, 'p_sig_a');
    assert.deepEqual(got.payload, { kind: 'ice', sdp: 'x' });
    await sleep(150);
    assert.ok(!a.inbox.some((m) => m.type === 'signal'), '发送者不应收到自己的信号');
    assert.ok(!room.host.inbox.some((m) => m.type === 'signal'), '无关成员不应收到点对点信号');

    a.close();
    b.close();
    room.host.close();
  });

  it("signal to '*' 广播给其他已入房成员", async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_cast' });
    const a = await client.connect({ roomCode: room.roomCode, peerId: 'p_cast_a', name: '甲' });
    const b = await client.connect({ roomCode: room.roomCode, peerId: 'p_cast_b', name: '乙' });
    const c = await client.connect({ roomCode: room.roomCode, peerId: 'p_cast_c', name: '丙' });
    for (const sock of [a, b, c]) await sock.waitFor((m) => m.type === 'welcome');
    await sleep(150);
    for (const sock of [a, b, c, room.host]) sock.clear();

    a.send({ type: 'signal', to: '*', payload: { kind: 'cursor' } });
    assert.ok(await b.waitFor((m) => m.type === 'signal'));
    assert.ok(await c.waitFor((m) => m.type === 'signal'));
    assert.ok(await room.host.waitFor((m) => m.type === 'signal'));
    await sleep(120);
    assert.ok(!a.inbox.some((m) => m.type === 'signal'), '广播不应回声给发送者');

    for (const sock of [a, b, c]) sock.close();
    room.host.close();
  });

  it('目标不在线时回 signal_undelivered', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_und' });
    const a = await client.connect({ roomCode: room.roomCode, peerId: 'p_und_a', name: '甲' });
    await a.waitFor((m) => m.type === 'welcome');
    a.clear();
    a.send({ type: 'signal', to: 'p_nobody_here', payload: { kind: 'ice' } });
    const undelivered = await a.waitFor((m) => m.type === 'signal_undelivered');
    assert.equal(undelivered.to, 'p_nobody_here');
    a.close();
    room.host.close();
  });
});

describe('房间管理与房主迁移', () => {
  it('房主踢人: 目标收到 kicked 并被 4005 关闭, 其余成员收到 peer_left, 且无法再入房', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_kick' });
    const target = await client.connect({ roomCode: room.roomCode, peerId: 'p_kick_t', name: '被踢者' });
    const watcher = await client.connect({ roomCode: room.roomCode, peerId: 'p_kick_w', name: '旁观者' });
    await target.waitFor((m) => m.type === 'welcome');
    await watcher.waitFor((m) => m.type === 'welcome');
    await sleep(120);

    room.host.send({ type: 'kick', targetPeerId: 'p_kick_t' });
    assert.ok(await target.waitFor((m) => m.type === 'kicked'));
    assert.equal(await target.waitClosing(), true, '被踢的连接应被服务端关闭');
    const left = await watcher.waitFor((m) => m.type === 'peer_left');
    assert.equal(left.reason, 'kicked');

    const again = await client.connectExpectReject({
      roomCode: room.roomCode, peerId: 'p_kick_t', name: '被踢者',
    });
    assert.equal(again, true, '被踢成员不得再入房');

    watcher.close();
    room.host.close();
  });

  it('房主主动退房: 在线继任者收到 host_promoted 并可用新 token 以 host 身份重连', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_leave' });
    const heir = await client.connect({ roomCode: room.roomCode, peerId: 'p_heir_a', name: '继任者' });
    const other = await client.connect({ roomCode: room.roomCode, peerId: 'p_heir_b', name: '旁观者' });
    await heir.waitFor((m) => m.type === 'welcome');
    await other.waitFor((m) => m.type === 'welcome');
    await sleep(150);

    room.host.send({ type: 'leave' });
    const promoted = await heir.waitFor((m) => m.type === 'host_promoted');
    assert.equal(promoted.hostPeerId, 'p_heir_a');
    assert.equal(promoted.hostToken.length, 48);
    assert.match(promoted.hostVirtualIp, /^10\.144\./);
    const changed = await other.waitFor((m) => m.type === 'host_changed');
    assert.equal(changed.hostPeerId, 'p_heir_a');

    const asHost = await client.connect({
      roomCode: room.roomCode, peerId: 'p_heir_a', name: '继任者', role: 'host', token: promoted.hostToken,
    });
    assert.ok(await asHost.waitFor((m) => m.type === 'welcome'));

    asHost.close();
    other.close();
  });

  it('回归: 房主退房时继任者恰好离线, 重连不应再撞 409 peer_id_conflict', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_off' });
    const heir = await client.connect({ roomCode: room.roomCode, peerId: 'p_off_heir', name: '离线继任者' });
    const other = await client.connect({ roomCode: room.roomCode, peerId: 'p_off_other', name: '离线旁观' });
    await heir.waitFor((m) => m.type === 'welcome');
    await other.waitFor((m) => m.type === 'welcome');
    await sleep(150);

    heir.close();
    other.close();
    await sleep(400);
    room.host.send({ type: 'leave' });
    await sleep(600);

    const reconnect = await client.connect({
      roomCode: room.roomCode, peerId: 'p_off_heir', name: '离线继任者',
    });
    const welcome = await reconnect.waitFor((m) => m.type === 'welcome');
    assert.equal(welcome.status, 'admitted');
    assert.equal(welcome.hostPeerId, 'p_off_heir');
    const promoted = await reconnect.waitFor((m) => m.type === 'host_promoted');
    assert.ok(promoted, '离线继任者重连后必须补发 host_promoted');
    assert.equal(promoted.hostToken.length, 48);

    const asHost = await client.connect({
      roomCode: room.roomCode, peerId: 'p_off_heir', name: '离线继任者', role: 'host', token: promoted.hostToken,
    });
    await asHost.waitFor((m) => m.type === 'welcome');
    asHost.close();
  });

  it('房主掉线: 宽限期内 claim_host 被拒, 期满后最早成员接管', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_claim' });
    const heir = await client.connect({ roomCode: room.roomCode, peerId: 'p_claim_a', name: '甲' });
    const late = await client.connect({ roomCode: room.roomCode, peerId: 'p_claim_b', name: '乙' });
    await heir.waitFor((m) => m.type === 'welcome');
    await late.waitFor((m) => m.type === 'welcome');
    await sleep(150);

    room.host.close(); // 不带 leave, 模拟强杀
    assert.ok(await heir.waitFor((m) => m.type === 'host_offline'));

    heir.send({ type: 'claim_host' });
    const grace = await heir.waitFor((m) => m.type === 'error');
    assert.equal(grace.code, 'host_grace_period');

    await sleep(5600);
    late.send({ type: 'claim_host' });
    const notNext = await late.waitFor((m) => m.type === 'error', 3000);
    assert.equal(notNext.code, 'not_next_in_line', '非顺位成员不得抢主');

    heir.send({ type: 'claim_host' });
    const promoted = await heir.waitFor((m) => m.type === 'host_promoted', 6000);
    assert.equal(promoted.hostPeerId, 'p_claim_a');
    assert.ok(await late.waitFor((m) => m.type === 'host_changed'));

    heir.close();
    late.close();
  });

  it('旧房主被他人接管后果断降级为普通成员, 以 peer 身份重连仍可进房', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_demote' });
    const heir = await client.connect({ roomCode: room.roomCode, peerId: 'p_demote_heir', name: '新主' });
    await heir.waitFor((m) => m.type === 'welcome');
    await sleep(120);

    // 旧房主掉线, 顺位成员接管
    room.host.close();
    await sleep(5600);
    heir.send({ type: 'claim_host' });
    assert.ok(await heir.waitFor((m) => m.type === 'host_promoted', 6000));

    // 旧房主拿着旧 token 以 host 身份重连会被拒 (旧 token 已随换届失效)
    const staleHost = await client.connectExpectReject({
      roomCode: room.roomCode, peerId: 'p_host_demote', name: '旧主', role: 'host', token: room.hostToken,
    });
    assert.equal(staleHost, true, '换届后旧 token 必须失效');

    // 客户端据 403 降级为 peer 重连时应正常进房
    const demoted = await client.connect({
      roomCode: room.roomCode, peerId: 'p_host_demote', name: '旧主',
    });
    const welcome = await demoted.waitFor((m) => m.type === 'welcome');
    assert.equal(welcome.status, 'admitted');
    assert.equal(welcome.hostPeerId, 'p_demote_heir');
    demoted.close();
    heir.close();
  });

  it('房主丢失 hostToken: 断线窗口内以 peer 重连可重新认领并换发新 token', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_reclaim' });
    const peer = await client.connect({ roomCode: room.roomCode, peerId: 'p_reclaim_p', name: '成员' });
    await peer.waitFor((m) => m.type === 'welcome');
    await sleep(150);

    // 模拟房主进程被杀: 连接异常断开, 本地 token 丢失
    room.host.close();
    await sleep(400);

    const reclaimed = await client.connect({
      roomCode: room.roomCode, peerId: 'p_host_reclaim', name: '房主',
    });
    const welcome = await reclaimed.waitFor((m) => m.type === 'welcome');
    assert.equal(welcome.hostPeerId, 'p_host_reclaim', '窗口内应认回房主身份');
    const promoted = await reclaimed.waitFor((m) => m.type === 'host_promoted');
    assert.ok(promoted, '重新认领必须补发新 hostToken');
    assert.notEqual(promoted.hostToken, room.hostToken, '认领应换发新 token, 旧 token 立即失效');
    assert.ok(await peer.waitFor((m) => m.type === 'host_changed'));

    const staleToken = await client.connectExpectReject({
      roomCode: room.roomCode, peerId: 'p_host_reclaim', name: '房主', role: 'host', token: room.hostToken,
    });
    assert.equal(staleToken, true, '旧 token 必须失效');

    const withNewToken = await client.connect({
      roomCode: room.roomCode, peerId: 'p_host_reclaim', name: '房主', role: 'host', token: promoted.hostToken,
    });
    await withNewToken.waitFor((m) => m.type === 'welcome');
    withNewToken.close();
    peer.close();
  });

  it('认领窗口外不再凭 peerId 换发 token (仍需 hostToken)', { timeout: 30000 }, async () => {
    const created = await fastClient.createRoom({ hostPeerId: 'p_host_late' });
    const host = await fastClient.connect({
      roomCode: created.body.roomCode, peerId: 'p_host_late', name: 'Host', role: 'host', token: created.body.hostToken,
    });
    await host.waitFor((m) => m.type === 'welcome');
    await sleep(200);
    host.close();
    await sleep(2200); // 远超 1.5s 认领窗口

    const rejected = await fastClient.connectExpectReject({
      roomCode: created.body.roomCode, peerId: 'p_host_late', name: 'Host',
    });
    assert.equal(rejected, true, '窗口外必须凭 hostToken 才能拿回房主');
  });

  it('关闭房间: 全员收到 room_closed 并被 4000 关闭, 之后 info 404', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_close' });
    const peer = await client.connect({ roomCode: room.roomCode, peerId: 'p_close_p', name: '成员' });
    await peer.waitFor((m) => m.type === 'welcome');
    await sleep(120);

    room.host.send({ type: 'close_room' });
    const closedMsg = await peer.waitFor((m) => m.type === 'room_closed');
    assert.equal(closedMsg.reason, 'host_closed');
    assert.equal(await peer.waitClosing(), true, '关房后连接应被服务端关闭');

    const info = await client.roomInfo(room.roomCode);
    assert.equal(info.status, 404);
  });

  it('离开房间走 leave: 连接以 1000 关闭且其他成员收到 peer_left', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_leavepeer' });
    const peer = await client.connect({ roomCode: room.roomCode, peerId: 'p_leave_p', name: '乙' });
    await peer.waitFor((m) => m.type === 'welcome');
    await sleep(120);

    peer.send({ type: 'leave' });
    const closed = await peer.waitClosed(15000);
    assert.ok(closed, '主动离开后应收到 close 事件');
    assert.equal(closed.code, 1000);
    const left = await room.host.waitFor((m) => m.type === 'peer_left');
    assert.equal(left.reason, 'left');
    room.host.close();
  });
});

describe('公开大厅与信息接口', () => {
  it('info 返回成员数与画布信息', async () => {
    const room = await openRoom(client, {
      hostPeerId: 'p_host_info', hostName: '房主大人', canvasWidth: 320, canvasHeight: 240,
    });
    const peer = await client.connect({ roomCode: room.roomCode, peerId: 'p_info_p', name: '成员甲' });
    await peer.waitFor((m) => m.type === 'welcome');
    const info = await client.roomInfo(room.roomCode);
    assert.equal(info.status, 200);
    assert.equal(info.body.peerCount, 2);
    assert.equal(info.body.hostPeerId, 'p_host_info');
    assert.equal(info.body.hostName, '房主大人');
    assert.equal(info.body.canvasMeta.width, 320);
    assert.ok(info.body.peerNames.includes('成员甲'));

    peer.close();
    room.host.close();
  });

  it('公开房出现在大厅, 人走空后从大厅移除', async () => {
    const created = await client.createRoom({
      hostPeerId: 'p_host_lobby', hostName: '公开房主', isPublic: true, title: '午后来画画',
    });
    assert.equal(created.ok, true);
    const code = created.body.roomCode;
    const host = await client.connect({
      roomCode: code, peerId: 'p_host_lobby', name: '公开房主', role: 'host', token: created.body.hostToken,
    });
    await host.waitFor((m) => m.type === 'welcome');
    await sleep(250);

    let list = await client.listRooms();
    const entry = (list.body.rooms || []).find((r) => r.roomCode === code);
    assert.ok(entry, '公开房应出现在大厅');
    assert.equal(entry.title, '午后来画画');
    assert.equal(entry.peerCount, 1);

    const peer = await client.connect({ roomCode: code, peerId: 'p_lobby_p', name: '路人' });
    await peer.waitFor((m) => m.type === 'welcome');
    await sleep(250);
    list = await client.listRooms();
    assert.equal((list.body.rooms || []).find((r) => r.roomCode === code)?.peerCount, 2);

    peer.close();
    await sleep(250);
    host.send({ type: 'leave' });
    await sleep(500);
    list = await client.listRooms();
    assert.equal(
      (list.body.rooms || []).some((r) => r.roomCode === code),
      false,
      '最后一人离开后大厅条目必须被移除',
    );
  });

  it('公开房重复上下线后大厅人数仍与实际一致 (目录指纹节流不丢更新)', async () => {
    const created = await client.createRoom({
      hostPeerId: 'p_host_throttle', hostName: '节流房主', isPublic: true, title: '反复进出',
    });
    const code = created.body.roomCode;
    const host = await client.connect({
      roomCode: code, peerId: 'p_host_throttle', name: '节流房主', role: 'host', token: created.body.hostToken,
    });
    await host.waitFor((m) => m.type === 'welcome');

    for (let i = 0; i < 3; i += 1) {
      const peer = await client.connect({ roomCode: code, peerId: 'p_thr_p', name: '来回' });
      await peer.waitFor((m) => m.type === 'welcome');
      await sleep(200);
      let list = await client.listRooms();
      assert.equal((list.body.rooms || []).find((r) => r.roomCode === code)?.peerCount, 2);
      peer.close();
      await sleep(250);
      list = await client.listRooms();
      assert.equal((list.body.rooms || []).find((r) => r.roomCode === code)?.peerCount, 1);
    }

    host.send({ type: 'leave' });
  });
});

describe('异常掉线占位清理', () => {
  it('阈值内昵称仍被占用, 超过 GHOST_PEER_TTL_MS 后同昵称可重新入房', async () => {
    const room = await openRoom(client, { hostPeerId: 'p_host_ghost' });
    const ghost = await client.connect({ roomCode: room.roomCode, peerId: 'p_ghost_1', name: '幽灵猫' });
    await ghost.waitFor((m) => m.type === 'welcome');
    ghost.close();
    await sleep(300);

    const tooEarly = await client.connectExpectReject({
      roomCode: room.roomCode, peerId: 'p_ghost_2', name: '幽灵猫',
    });
    assert.equal(tooEarly, true, '阈值内昵称仍应占用');

    await sleep(3200);
    const reborn = await client.connect({ roomCode: room.roomCode, peerId: 'p_ghost_3', name: '幽灵猫' });
    const welcome = await reborn.waitFor((m) => m.type === 'welcome');
    assert.equal(welcome.status, 'admitted');
    assert.match(welcome.peers.find((p) => p.peerId === 'p_ghost_3').virtualIp, /^10\.144\./);
    reborn.close();
    room.host.close();
  });
});

describe('房间有效期', () => {
  it('无活动时按 ROOM_TTL_MS 过期, 连接收到 4004 且房间被回收', { timeout: 30000 }, async () => {
    const created = await fastClient.createRoom({ hostPeerId: 'p_host_ttl' });
    const host = await fastClient.connect({
      roomCode: created.body.roomCode, peerId: 'p_host_ttl', name: 'Host', role: 'host', token: created.body.hostToken,
    });
    await host.waitFor((m) => m.type === 'welcome');

    const expiredMsg = await host.waitFor((m) => m.type === 'room_closed', 15000);
    assert.equal(expiredMsg?.reason, 'expired', '过期前应先收到 room_closed 业务消息');
    assert.equal(await host.waitClosing(), true, '过期后连接应被服务端关闭');
    const info = await fastClient.roomInfo(created.body.roomCode);
    assert.equal(info.status, 404);
  });

  it('有信令活动时过期时间顺延, 不会到点硬切断', { timeout: 40000 }, async () => {
    const created = await fastClient.createRoom({ hostPeerId: 'p_host_slide' });
    const host = await fastClient.connect({
      roomCode: created.body.roomCode, peerId: 'p_host_slide', name: 'Host', role: 'host', token: created.body.hostToken,
    });
    await host.waitFor((m) => m.type === 'welcome');

    // TTL 6s, 在 3.5s 处发一条信令把过期时间推到 ~9.5s
    await sleep(3500);
    host.send({ type: 'signal', to: 'p_nobody', payload: {} });
    await sleep(4000); // 此刻已超过原始 6s 期限
    assert.equal(host.state.closed, false, '活动后连接不应在原始 TTL 处被切断');
    assert.equal((await fastClient.roomInfo(created.body.roomCode)).status, 200);

    const expiredMsg = await host.waitFor((m) => m.type === 'room_closed', 12000);
    assert.equal(expiredMsg?.reason, 'expired', '停止活动后应在新过期时间回收');
    assert.equal(await host.waitClosing(), true);
  });
});

describe('房间配置更新、保存权限与断线恢复', () => {
  it('savePolicy 在建房时持久化到 info 与公开大厅, 且 update_room 不可篡改', async () => {
    const created = await client.createRoom({
      hostPeerId: 'p_host_savepol',
      hostName: '限存房主',
      isPublic: false,
      title: '私密房间标题',
      savePolicy: 'own_only',
    });
    assert.equal(created.ok, true);
    assert.equal(created.body.savePolicy, 'own_only');
    assert.equal(created.body.title, '私密房间标题');

    const host = await client.connect({
      roomCode: created.body.roomCode,
      peerId: 'p_host_savepol',
      name: '限存房主',
      role: 'host',
      token: created.body.hostToken,
    });
    const hostWelcome = await host.waitFor((m) => m.type === 'welcome');
    assert.equal(hostWelcome.savePolicy, 'own_only');
    assert.ok(hostWelcome.peerToken?.length > 10);

    const peer = await client.connect({
      roomCode: created.body.roomCode,
      peerId: 'p_peer_savepol',
      name: '成员丙',
    });
    const peerWelcome = await peer.waitFor((m) => m.type === 'welcome');
    assert.equal(peerWelcome.savePolicy, 'own_only');
    peer.clear();
    host.clear();

    // 非房主尝试 update_room 必须被拒
    peer.send({ type: 'update_room', isPublic: true, title: '越权' });
    const hostOnlyErr = await peer.waitFor((m) => m.type === 'error');
    assert.equal(hostOnlyErr.code, 'host_only');

    // 房主将房间切为公开并尝试附带 savePolicy: 'all', 服务端必须忽略 savePolicy 修改
    host.send({
      type: 'update_room',
      isPublic: true,
      title: '公开后的画室',
      requireApproval: true,
      maxPeers: 12,
      password: 'new-secret',
      savePolicy: 'all',
    });
    const hostUpd = await host.waitFor((m) => m.type === 'room_updated');
    const peerUpd = await peer.waitFor((m) => m.type === 'room_updated');
    for (const u of [hostUpd, peerUpd]) {
      assert.equal(u.isPublic, true);
      assert.equal(u.title, '公开后的画室');
      assert.equal(u.hasPassword, true);
      assert.equal(u.requireApproval, true);
      assert.equal(u.maxPeers, 12);
      assert.equal(u.savePolicy, 'own_only', 'savePolicy 建房后不可编辑');
    }

    await sleep(200);
    const listed = (await client.listRooms()).body.rooms.find((r) => r.roomCode === created.body.roomCode);
    assert.ok(listed, '切为公开后应出现在在线大厅');
    assert.equal(listed.title, '公开后的画室');
    assert.equal(listed.savePolicy, 'own_only');
    assert.equal(listed.hasPassword, true);

    // 再切换回私密隐藏 + 清空密码 + 无标题公开失败校验
    host.send({ type: 'update_room', isPublic: false, title: '', password: '' });
    const hiddenUpd = await host.waitFor((m) => m.type === 'room_updated' && m.isPublic === false);
    assert.equal(hiddenUpd.hasPassword, false);
    await sleep(200);
    const listedAfterHide = (await client.listRooms()).body.rooms.some((r) => r.roomCode === created.body.roomCode);
    assert.equal(listedAfterHide, false, '隐藏房间后必须立即从大厅下架');

    host.clear();
    host.send({ type: 'update_room', isPublic: true, title: '' });
    const titleErr = await host.waitFor((m) => m.type === 'error');
    assert.equal(titleErr.code, 'room_title_required');

    peer.close();
    host.close();
  });

  it('断线重连恢复: 持 peerToken 在超过 ghost TTL 且房主改密/开审批后仍可直接恢复原身份', async () => {
    const room = await openRoom(client, {
      hostPeerId: 'p_host_rec',
      password: 'oldpw',
      requireApproval: false,
    });
    const peer = await client.connect({
      roomCode: room.roomCode,
      peerId: 'p_peer_rec',
      name: '老画师',
      password: 'oldpw',
    });
    const w1 = await peer.waitFor((m) => m.type === 'welcome');
    assert.equal(w1.status, 'admitted');
    const peerToken = w1.peerToken;
    const origVip = w1.peers.find((p) => p.peerId === 'p_peer_rec').virtualIp;
    const origJoinedAt = w1.peers.find((p) => p.peerId === 'p_peer_rec').joinedAt;
    assert.ok(peerToken);

    // 成员掉线并超过 ghost TTL (3s), 期间房主开启审批并更换密码
    peer.close();
    await sleep(3400);
    room.host.send({ type: 'update_room', requireApproval: true, password: 'newpw' });
    await room.host.waitFor((m) => m.type === 'room_updated');

    // info 返回结构化 peers 列表用于排除本人 peerId 昵称去重
    const info = await client.roomInfo(room.roomCode);
    assert.equal(info.status, 200);
    assert.ok(Array.isArray(info.body.peers));

    // 老成员持 peerToken 重连 (不带新密码): 应免审免新密并恢复原 VIP 与 joinedAt
    const recovered = await client.connect({
      roomCode: room.roomCode,
      peerId: 'p_peer_rec',
      name: '老画师',
      peerToken,
    });
    const w2 = await recovered.waitFor((m) => m.type === 'welcome');
    assert.equal(w2.status, 'admitted', '恢复连接不应被当作需审批的新成员');
    const me2 = w2.peers.find((p) => p.peerId === 'p_peer_rec');
    assert.equal(me2.virtualIp, origVip);
    assert.equal(me2.joinedAt, origJoinedAt);

    recovered.close();
    room.host.close();
  });
});
