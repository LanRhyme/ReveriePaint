# 3D 资产开源许可与来源说明 (3D Model Credits & Licenses)

本项目内置的 3D 参考人偶与几何模型遵循开源及公共领域许可

## 1. 人体标准素模 (男 / 女)

- **模型来源**: Blender Studio 官方资产库 (Blender Studio Human Base Meshes - Male & Female Realistic)
- **项目主页**: https://download.blender.org/demo/asset-bundles/human-base-meshes/
- **资产许可**: Creative Commons Zero 1.0 Universal (CC0 1.0 Public Domain)
- **商用与修改**: 允许免版权、免署名用于商业及非商业用途，支持二次修改与格式转换
- **工程处理说明**: 由 ReveriePaint 团队进行解剖骨骼关节球心定位与无扭曲平滑蒙皮权重构建，专为速写起稿与动态人体透视参考设计
- **骨骼拓扑优化**: 经 `scripts/model/remove_redundant_head_bone.py` 移除颅骨内多余的悬浮骨骼点，将头部蒙皮权重统一归并至颈颌交界处 Head 关节，使头部在姿态旋转与骨骼编辑时作为单一定位轴心自然转折，消除颅内杂点与网格撕裂

## 2. Asaro 头部切面素模

- **模型来源**: Blender Studio 官方基础几何库 (Blender Studio Human Base Meshes - Head Planar)
- **项目主页**: https://download.blender.org/demo/asset-bundles/human-base-meshes/
- **资产许可**: Creative Commons Zero 1.0 Universal (CC0 1.0 Public Domain)
- **工程处理说明**: 经典 Asaro 头部空间折面与打光向导，帮助推敲头颈转折与明暗交界线

## 3. 3D 写实家猫素模

- **模型来源**: Meshy AI 生成并由用户与 ReveriePaint 团队二次精细拓扑与解剖校准
- **资产许可**: CC0 1.0 Public Domain / GPL-3.0-or-later 自由衍生
- **工程处理说明**: 包含完整猫科面部细节、胡须轮廓、立耳、尾尖卷曲与 29 节点骨骼 (可动耳/口吻/趾骨)，专为动物起稿动态与透视参考设计
- **耳骨修正**: `scripts/model/fix_cat_ears.py` 把 `earend` / `R_earend` 的轴心从"耳尖上方 (y=0.577, 在耳朵几何最高点之上)"移到耳根 (y≈0.455) 并沿耳轴定向, 同步重算 inverseBind; 静止姿态逐顶点不变 (`world × inverseBind` 恒为单位阵, 误差 <3e-8), 仅让耳骨绕耳根旋转、局部 X 回到世界 +X 附近
- **管线与坐标系**: 由 `scripts/model/build_cat_glb.py` 重绑：Meshy 原始骨骼有两处硬伤 —— Spine / Neck 的骨起点分别在 z=+18.4 / z=+23.0 (远在模型之外，任何旋转都会把上半身甩飞)，蒙皮里 Head 一根骨独占 66% 权重、躯干没有脊柱权重；脚本修复两处骨起点、把会与 App 启发式冲突的 `headend` 改名为 `muzzle`、焊接碎壳后按「到骨线段距离」重算权重 (1/d³ 取前 4 + 18 轮平滑)，并整体居中到原点。产物为 glTF 标准朝向 (Y 轴向上、+Z 为面部正前方、x 关于 0 对称)，9004 顶点 / 7454 三角面，无动画无材质

