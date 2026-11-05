/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * 设备名展示层。
 *
 * 遥测存的三个原始字段都"不好认":
 *  - `model` 是 Build.MODEL, 国产 ROM 常年只给型号代码(SM-X700 / OPD2413 / V2190A);
 *  - `dev`   是 Build.DEVICE, 产品代号(gts8wifi / elish / OP6159L1), 圈内人认、普通人认不出;
 *  - `mfr`   是 Build.MANUFACTURER, 大小写与写法各不相同(samsung / Xiaomi / LENOVO / HUAWEI)。
 *
 * 这里做三件事: 厂商名中文化、型号/代号 → 市场名(别名表)、拼成"主标题 + 副标题"两行,
 * 让看板上一眼能看出是哪台机器, 而不是对着一串型号代码猜。
 *
 * 别名表**只收确认过的**: model ↔ dev ↔ 市场名 三者对得上(来源是本项目遥测里记录的
 * Build.FINGERPRINT 产品代号 + 厂商公开型号)。不确定的一律不写 —— 猜错比只显示型号更糟。
 * 新增条目时请把 `dev`(指纹里的产品代号) 和 `model`(设置里"关于平板"的型号) 都核对一遍。
 */

/** 厂商显示名。键为 Build.MANUFACTURER 原值(小写比较)。 */
const VENDORS = {
  samsung: '三星',
  xiaomi: '小米',
  redmi: '红米',
  huawei: '华为',
  honor: '荣耀',
  oppo: 'OPPO',
  oneplus: '一加',
  vivo: 'vivo',
  iqoo: 'iQOO',
  realme: 'realme',
  lenovo: '联想',
  motorola: '摩托罗拉',
  google: '谷歌',
  amazon: '亚马逊',
  xppen: 'XPPen',
  tcl: 'TCL',
  teclast: '台电',
  alldocube: '酷比魔方',
  'hanvon ugee': '汉王 Ugee',
};

/**
 * 市场名别名。优先按产品代号(dev)命中, 其次按型号(model) —— 代号更稳定(同型号可能有
 * 多个地区变体), 型号用来兜底(个别 ROM 的 dev 是通用名)。
 */
const ALIASES = {
  // ---- 三星 Galaxy Tab 系列 ----
  gts8wifi: 'Galaxy Tab S8',
  'SM-X700': 'Galaxy Tab S8',
  gts9wifi: 'Galaxy Tab S9',
  'SM-X710': 'Galaxy Tab S9',
  gts10fewifi: 'Galaxy Tab S10 FE',
  'SM-X520': 'Galaxy Tab S10 FE',
  gts9fepwifi: 'Galaxy Tab S9 FE+',
  'SM-X610': 'Galaxy Tab S9 FE+',
  gta4xlwifi: 'Galaxy Tab S6 Lite',
  'SM-P610': 'Galaxy Tab S6 Lite',
  gta4lwifi: 'Galaxy Tab A7',
  'SM-T500': 'Galaxy Tab A7',
  gts7l: 'Galaxy Tab S7',
  'SM-T878U': 'Galaxy Tab S7',
  // ---- 小米平板 ----
  nabu: '小米平板 5',
  '21051182C': '小米平板 5',
  elish: '小米平板 5 Pro',
  M2105K81AC: '小米平板 5 Pro',
  pipa: '小米平板 6',
  '23043RP34C': '小米平板 6',
  // ---- 数位板 ----
  XPPenMDP1221: 'XPPen 数位板',
  MDP1221: 'XPPen 数位板',
};

/** 安卓版本号: rel 是 "16" 之类, sdk 兜底; 都没有就返回空串 */
function androidVersion(dev) {
  const rel = String(dev.rel ?? '').trim();
  if (rel) return `Android ${rel}`;
  const sdk = Number(dev.sdk ?? 0);
  return sdk > 0 ? `SDK ${sdk}` : '';
}

function vendorOf(mfr) {
  const raw = String(mfr ?? '').trim();
  if (!raw) return '';
  return VENDORS[raw.toLowerCase()] ?? raw;
}

/** Windows 上跑安卓(WSA): 型号串没有意义, 直接给个人话名字 */
function isWindowsSubsystem(dev) {
  const model = String(dev.model ?? '');
  const code = String(dev.dev ?? '');
  return /Subsystem for Android/i.test(model) || /windows/i.test(code);
}

/** 市场名(可空) */
export function aliasOf(dev) {
  const code = String(dev.dev ?? '').trim();
  const model = String(dev.model ?? '').trim();
  if (code && ALIASES[code]) return ALIASES[code];
  if (model && ALIASES[model]) return ALIASES[model];
  return '';
}

/**
 * 主标题: 让"这是什么设备"一眼可辨。
 * 有别名 → `三星 Galaxy Tab S8`; 无别名 → `OPPO OPD2413`; 没型号 → `(未知设备)`。
 */
export function deviceTitle(dev) {
  if (isWindowsSubsystem(dev)) return 'Windows 上的安卓 (WSA)';
  const vendor = vendorOf(dev.mfr);
  const alias = aliasOf(dev);
  if (alias) return brandWith(vendor, alias);
  const model = String(dev.model ?? '').trim();
  if (!model) return '(未知设备)';
  return brandWith(vendor, model);
}

/** 别名/型号本身已含品牌时(小米平板 6 / XPPen 数位板)不再重复前缀 */
function brandWith(vendor, name) {
  if (!vendor) return name;
  if (name.startsWith(vendor)) return name;
  const core = vendor.replace(/\s*\(.*\)$/, '');
  if (core && name.includes(core)) return name;
  return `${vendor} ${name}`;
}

/**
 * 副标题: 原始型号 + 产品代号 + 安卓版本, 供核对与精确筛选用。
 * 例: `SM-X700 · gts8wifi · Android 16`
 */
export function deviceSub(dev) {
  if (isWindowsSubsystem(dev)) return 'Windows Subsystem for Android · x86_64';
  const parts = [];
  const model = String(dev.model ?? '').trim();
  const code = String(dev.dev ?? '').trim();
  const ver = androidVersion(dev);
  if (model) parts.push(model);
  // 代号与型号相同(联想等)时不必重复
  if (code && code !== model) parts.push(code);
  if (ver) parts.push(ver);
  return parts.join(' · ');
}

/** 供纯文本导出(CSV/日志)用的单行标签 */
export function deviceLabel(dev) {
  const title = deviceTitle(dev);
  const sub = deviceSub(dev);
  if (!sub) return title;
  if (isWindowsSubsystem(dev)) return `${title} (${sub})`;
  const model = String(dev.model ?? '').trim();
  // 标题里已经带了型号就不重复
  const compact = title.includes(model) ? sub.split(' · ').slice(1).join(' · ') : sub;
  return compact ? `${title} (${compact})` : title;
}

/** 机型分布里的一格: 主标题 + 副标题, 悬浮显示完整型号串 */
export function deviceBody(dev) {
  const title = escape(deviceTitle(dev));
  const sub = deviceSub(dev);
  return sub ? `<b>${title}</b><em>${escape(sub)}</em>` : `<b>${title}</b>`;
}

function escape(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}
