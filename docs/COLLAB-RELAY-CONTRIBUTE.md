# ReveriePaint 茶绘中继节点贡献指南

本文档面向希望为 ReveriePaint 多人茶绘提供或捐赠轻量 EasyTier 中继节点 (Relay Node) 的社区开发者与赞助者, 介绍节点架构隔离原则、安全部署流程、实测验证方法及提交流程

---

## 工作原理与隔离机制

ReveriePaint 茶绘基于 EasyTier (`v2.7.1`) 建立端到端虚拟网并传输笔画与图层数据:

- **P2P 优先**: 双方网络允许时直接打洞互联, 流量不经过中继服务器
- **弱网兜底**: 仅当双方均位于对称型 NAT (Symmetric NAT) 或跨运营商 UDP 阻断时, 才会通过中继节点转发加密报文
- **流量轻量**: 实时同步主要发送坐标点采样流 (`MsgStrokeDelta`) 与首次 `.kpp` 笔刷包, 带宽占用远低于远程桌面或视频流
- **房间级白名单隔离**: 茶绘房间虚拟网名固定为 `rvp-<6位房间码>`, 节点通过 `--relay-network-whitelist "rvp-*"` 仅放行 ReveriePaint 茶绘流量, 不会被外部无关网络当成免费公共中继蹭带宽
- **零侵入运行**: 使用 `--no-tun` 模式启动, 不创建宿主机虚拟网卡, 不修改系统路由表, 与同机既有的个人网站或业务服务互不干扰

---

## 服务器配置要求

| 项目 | 最低推荐 | 说明 |
|---|---|---|
| CPU / 内存 | 1 vCPU / 512 MB+ | `easytier-core` 空载仅占用十几 MB 内存 |
| 带宽 | 3 Mbps ~ 10 Mbps+ | 峰值取决于同时触发中继的房间数与导入大图频率 |
| 系统 | Linux (`x86_64` / `arm64`) | Ubuntu / Debian / CentOS / AlmaLinux / Alpine 等 |
| 网络端口 | TCP `11010` + UDP `11010` | 需同时在云厂商安全组与系统防火墙放行入方向 |

---

## 部署步骤

以下步骤使用与客户端内置 `libeasytier_ffi.so` 一致的 EasyTier **`v2.7.1`** 版本

### 1 放行安全组与系统防火墙

在云服务商控制台 (阿里云 / 腾讯云 / AWS / Cloudflare 等) 的**安全组入方向规则**中放行:

- `TCP` 端口 `11010` (来源 `0.0.0.0/0`)
- `UDP` 端口 `11010` (来源 `0.0.0.0/0`)

若宿主机启用了 `ufw` 或 `firewalld`, 同步放行:

```bash
# Ubuntu / Debian (ufw)
ufw allow 11010/tcp && ufw allow 11010/udp

# CentOS / RHEL / AlmaLinux (firewalld)
firewall-cmd --permanent --add-port=11010/tcp --add-port=11010/udp && firewall-cmd --reload
```

### 2 下载并安装 `easytier-core`

```bash
mkdir -p /opt/easytier && cd /opt/easytier

# x86_64 架构服务器
curl -L -o easytier.zip "https://github.com/EasyTier/EasyTier/releases/download/v2.7.1/easytier-linux-x86_64-v2.7.1.zip"

# 若为 arm64 架构服务器, 替换为下面这一行:
# curl -L -o easytier.zip "https://github.com/EasyTier/EasyTier/releases/download/v2.7.1/easytier-linux-aarch64-v2.7.1.zip"

unzip -o easytier.zip
install -m 755 easytier-linux-*/easytier-core /usr/local/bin/easytier-core
/usr/local/bin/easytier-core --version
```

### 3 配置 systemd 常驻守护

写入 `/etc/systemd/system/easytier-relay.service`:

```ini
[Unit]
Description=EasyTier Relay for ReveriePaint Collab
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/easytier-core \
  --no-tun \
  --listeners tcp://0.0.0.0:11010 udp://0.0.0.0:11010 \
  --relay-network-whitelist "rvp-*" \
  --relay-all-peer-rpc
Restart=always
RestartSec=3
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
```

启用并检查服务状态:

```bash
systemctl daemon-reload
systemctl enable --now easytier-relay
systemctl status easytier-relay --no-pager
ss -tulnp | grep 11010
```

确认输出中 `easytier-core` 正在监听 TCP 与 UDP `11010` 端口即可

---

## 连通性自测 (免改代码验证)

无需重新编译 APK, 可直接在已安装的 ReveriePaint App 中验证你的自建节点:

1. 打开 App -> 主页右上角「多人茶绘」 -> 展开底部「高级网络与自定义 EasyTier 中继节点」
2. 在「EasyTier 中继节点」输入框填写:
   ```
   tcp://<你的服务器IP或域名>:11010,udp://<你的服务器IP或域名>:11010
   ```
3. 两人分别使用不同运营商网络 (如 Wi-Fi 与手机流量) 建房/入房, 在房间成员面板查看网络路由与延迟显示 (`RELAYED` 或 `P2P_DIRECT`)

---

## 向项目贡献节点

测试可用且愿意长期或阶段性向社区开放的节点, 可通过以下任一方式提交:

- **方式一 (推荐): 提交 GitHub Issue**
  - 在 [GitHub Issues](https://github.com/LanRhyme/ReveriePaint/issues) 标题注明 `[Relay Node] <地区/线路>`
  - 提供信息:
    - 节点地址 (例如 `tcp://example.com:11010,udp://example.com:11010`)
    - 所在地区与带宽上限 (例如 `中国香港 / 10Mbps / 月流量 1TB`)
    - 预计可用周期或维护联系备注
- **方式二: 提交 PR**
  - 由维护者评估稳定性与地区覆盖后, 热注入至信令 Worker 的 `RELAY_PEERS` 环境变量 (`collab/worker/wrangler.toml`), 新旧版本客户端均可立即生效而无需发版

### 节点下线与撤回

若服务器到期、流量额度用尽或需要维护下线, 请提前在对应 Issue 留言或联系维护者, 维护者将通过 Cloudflare Worker 热更新立即将该节点移出 `RELAY_PEERS`

---

## 安全与隐私说明

- EasyTier 每个茶绘房间在创建时都会由信令服务签发随机 `network_secret` 加密隔离不同房间流量, 中继节点仅负责密文数据包转发, **无法读取画布内容、图层或用户密码**
- 提交 Issue 或 PR 时**严禁附带任何服务器 SSH 账号、密码、私钥或后台面板地址**, 仅需提供公开的中继连接 URI (`tcp://host:11010` / `udp://host:11010`)
