#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""移除人体素模 (anime_boy / anime_girl) 中头颅内部多余的骨骼点

背景:
人体素模原始骨架在胸腔 (Chest) 上方依次串接了 Neck (位于喉部/下颌转折处, y≈0.55) 与
Head (位于颅骨中央/眉心深处, y≈0.72)。在 3D 骨骼编辑模式下，由于 Head 是叶子节点，
系统会在其原点处绘制一个独立的交互圆圈，导致用户在人偶头部正中央看到一个多余且突兀的骨骼点，
且转头时若只旋转 Head 会仅带动颅顶、造成头面部下半截撕裂拉伸。
本脚本将 Head 骨骼的权重完全合并进 Neck 骨骼，将 Neck 骨骼重命名为 Head (作为胸腔直连
头部的唯一定位与旋转轴心，对应人体解剖寰枕关节转折处)，删除多余的叶子节点，
并同步更新 skin joints 与 inverseBindMatrices 闭包。

处理步骤:
1) 蒙皮权重: 将 JOINTS_0 / WEIGHTS_0 中引用原 Head 骨 (skin joint 12) 的权重累加至 Neck (skin joint 11)
2) 关节索引重映射: 将索引 > 12 的骨骼索引递减 1，并对四通道权重重新归一化
3) 逆绑定矩阵: 从 inverseBindMatrices 中移除索引 12 的 MAT4，bufferView 缩短 64 字节
4) 节点与层次结构: 将 node 9 ("Neck") 重命名为 "Head" 并清空其 children；删除 node 8 ("Head")；
   将整棵树中所有指向 > 8 节点的引用统一递减 1
"""
import json
import os
import struct
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def process_humanoid_glb(in_path, out_path):
    data = open(in_path, 'rb').read()
    off = 12
    clen, ctype = struct.unpack_from('<II', data, off)
    js = json.loads(data[off + 8:off + 8 + clen])
    if len(js['skins'][0]['joints']) <= 18:
        print(f'Already processed: {in_path}')
        return
    bin_ = bytearray(data[off + 8 + clen + 8:])

    pos_count = js['accessors'][0]['count']

    # 1. 更新 JOINTS_0 与 WEIGHTS_0
    j_acc_idx = js['meshes'][0]['primitives'][0]['attributes']['JOINTS_0']
    j_acc = js['accessors'][j_acc_idx]
    j_bv = js['bufferViews'][j_acc['bufferView']]
    j_offset = j_bv.get('byteOffset', 0) + j_acc.get('byteOffset', 0)
    j_type = np.uint16 if j_acc['componentType'] == 5123 else np.uint8
    joints = np.frombuffer(
        bin_[j_offset:j_offset + pos_count * 4 * np.dtype(j_type).itemsize],
        dtype=j_type
    ).reshape(-1, 4).copy()

    w_acc_idx = js['meshes'][0]['primitives'][0]['attributes']['WEIGHTS_0']
    w_acc = js['accessors'][w_acc_idx]
    w_bv = js['bufferViews'][w_acc['bufferView']]
    w_offset = w_bv.get('byteOffset', 0) + w_acc.get('byteOffset', 0)
    weights = np.frombuffer(
        bin_[w_offset:w_offset + pos_count * 16],
        dtype=np.float32
    ).reshape(-1, 4).copy()

    # 原模型中 skin joint 11 是 Neck, joint 12 是 Head
    for i in range(pos_count):
        row_j = joints[i]
        row_w = weights[i]

        has_11 = 11 in row_j
        has_12 = 12 in row_j

        if has_12:
            idx_12 = list(row_j).index(12)
            w_12 = row_w[idx_12]
            if has_11:
                idx_11 = list(row_j).index(11)
                row_w[idx_11] += w_12
                row_w[idx_12] = 0.0
                row_j[idx_12] = 0
            else:
                row_j[idx_12] = 11

        for s in range(4):
            if row_j[s] > 12:
                row_j[s] -= 1

        tot = np.sum(row_w)
        if tot > 1e-6:
            row_w /= tot

        joints[i] = row_j
        weights[i] = row_w

    bin_[j_offset:j_offset + len(joints.tobytes())] = joints.tobytes()
    bin_[w_offset:w_offset + len(weights.tobytes())] = weights.tobytes()

    # 2. 更新 inverseBindMatrices
    ibm_acc = js['accessors'][6]
    ibm_bv = js['bufferViews'][ibm_acc['bufferView']]
    ibm_offset = ibm_bv.get('byteOffset', 0) + ibm_acc.get('byteOffset', 0)
    ibm_mats = np.frombuffer(
        bin_[ibm_offset:ibm_offset + 19 * 64],
        dtype=np.float32
    ).reshape(19, 16).copy()
    new_ibm_mats = np.delete(ibm_mats, 12, axis=0)

    bin_[ibm_offset:ibm_offset + 18 * 64] = new_ibm_mats.tobytes()
    bin_ = bin_[:ibm_offset + 18 * 64]

    ibm_acc['count'] = 18
    ibm_bv['byteLength'] = 18 * 64
    js['buffers'][0]['byteLength'] = len(bin_)

    # 3. 调整节点树 (删除 node 8, 将 node 9 改名为 Head)
    nodes = js['nodes']
    nodes[9]['name'] = 'Head'
    nodes[9]['children'] = []
    del nodes[8]

    def remap_node(n_idx):
        return n_idx - 1 if n_idx > 8 else n_idx

    for n in nodes:
        if 'children' in n and n['children']:
            n['children'] = [remap_node(c) for c in n['children']]

    for s in js.get('scenes', []):
        if 'nodes' in s:
            s['nodes'] = [remap_node(r) for r in s['nodes']]

    skin = js['skins'][0]
    del skin['joints'][12]
    skin['joints'] = [remap_node(j) for j in skin['joints']]

    # 4. 写出 GLB
    js_bytes = json.dumps(js, separators=(',', ':')).encode('utf-8')
    js_pad = (4 - len(js_bytes) % 4) % 4
    bin_pad = (4 - len(bin_) % 4) % 4
    total = 12 + 8 + len(js_bytes) + js_pad + 8 + len(bin_) + bin_pad

    out = bytearray()
    out += struct.pack('<III', 0x46546C67, 2, total)
    out += struct.pack('<II', len(js_bytes) + js_pad, 0x4E4F534A)
    out += js_bytes + b' ' * js_pad
    out += struct.pack('<II', len(bin_) + bin_pad, 0x004E4942)
    out += bytes(bin_) + b'\x00' * bin_pad

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    open(out_path, 'wb').write(bytes(out))
    print(f'Done: {out_path} (joints: {len(skin["joints"])})')


if __name__ == '__main__':
    boy_path = os.path.join(ROOT, 'app/src/main/assets/3d_models/anime_boy.glb')
    girl_path = os.path.join(ROOT, 'app/src/main/assets/3d_models/anime_girl.glb')
    process_humanoid_glb(boy_path, boy_path)
    process_humanoid_glb(girl_path, girl_path)
