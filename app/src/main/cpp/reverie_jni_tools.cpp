/*
 * JNI bridge: Kotlin/Compose UI <-> ReverieCore C++ engine
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <QCoreApplication>
#include <QPainter>
#include <QString>
#include <QByteArray>

#include "ReverieCore.h"

#include "reverie_jni_common.h"

extern "C" {

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_drawPolygon(JNIEnv *env, jobject, jintArray xs, jintArray ys, jint count, jboolean closed)
{
    if (count < 2) return;
    jint *px = env->GetIntArrayElements(xs, nullptr);
    jint *py = env->GetIntArrayElements(ys, nullptr);
    QVector<QPoint> pts;
    for (int i = 0; i < count; ++i) pts.append(QPoint(px[i], py[i]));
    env->ReleaseIntArrayElements(xs, px, JNI_ABORT);
    env->ReleaseIntArrayElements(ys, py, JNI_ABORT);
    core()->drawPolygon(pts, closed);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_gradientFill(JNIEnv *, jobject, jint x1, jint y1, jint x2, jint y2, jint type, jint repeat, jboolean reverse)
{
    core()->gradientFill(x1, y1, x2, y2, type, repeat, reverse);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_moveLayerContent(JNIEnv *, jobject, jint dx, jint dy)
{
    core()->moveLayerContent(dx, dy);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_moveLayerContentLayers(
    JNIEnv *env, jobject, jintArray layers, jint dx, jint dy)
{
    QVector<int> list;
    if (layers != nullptr) {
        const jsize n = env->GetArrayLength(layers);
        jint *elems = env->GetIntArrayElements(layers, nullptr);
        for (int i = 0; i < n; ++i) {
            list.append(int(elems[i]));
        }
        env->ReleaseIntArrayElements(layers, elems, JNI_ABORT);
    }
    core()->moveLayerContentLayers(list, dx, dy);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_contentBounds(JNIEnv *env, jobject)
{
    const QRect b = core()->contentBounds();
    jintArray arr = env->NewIntArray(4);
    if (arr) {
        jint v[4] = {b.x(), b.y(), b.width(), b.height()};
        env->SetIntArrayRegion(arr, 0, 4, v);
    }
    return arr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_contentBoundsLayers(JNIEnv *env, jobject, jintArray layers)
{
    QVector<int> list;
    if (layers != nullptr) {
        const jsize n = env->GetArrayLength(layers);
        jint *elems = env->GetIntArrayElements(layers, nullptr);
        for (int i = 0; i < n; ++i) {
            list.append(int(elems[i]));
        }
        env->ReleaseIntArrayElements(layers, elems, JNI_ABORT);
    }
    const QRect b = core()->contentBounds(list);
    jintArray arr = env->NewIntArray(4);
    if (arr) {
        jint v[4] = {b.x(), b.y(), b.width(), b.height()};
        env->SetIntArrayRegion(arr, 0, 4, v);
    }
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyTransform(JNIEnv *env, jobject,
                                                             jdouble xscale, jdouble yscale,
                                                             jdouble xshear, jdouble yshear,
                                                             jdouble rotationRad,
                                                             jdouble xtranslate, jdouble ytranslate,
                                                             jdouble originX, jdouble originY)
{
    return core()->applyTransform(xscale, yscale, xshear, yshear,
                                  rotationRad, xtranslate, ytranslate,
                                  originX, originY)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyTransformLayers(
    JNIEnv *env, jobject, jintArray layers,
    jdouble xscale, jdouble yscale, jdouble xshear, jdouble yshear,
    jdouble rotationRad, jdouble xtranslate, jdouble ytranslate,
    jdouble originX, jdouble originY)
{
    QVector<int> list;
    if (layers != nullptr) {
        const jsize n = env->GetArrayLength(layers);
        jint *elems = env->GetIntArrayElements(layers, nullptr);
        for (int i = 0; i < n; ++i) {
            list.append(int(elems[i]));
        }
        env->ReleaseIntArrayElements(layers, elems, JNI_ABORT);
    }
    return core()->applyTransformLayers(list, xscale, yscale, xshear, yshear,
                                        rotationRad, xtranslate, ytranslate,
                                        originX, originY)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyTransformLayersEx(
    JNIEnv *env, jobject, jintArray layers,
    jdouble xscale, jdouble yscale, jdouble xshear, jdouble yshear,
    jdouble rotationRad, jdouble xtranslate, jdouble ytranslate,
    jdouble originX, jdouble originY, jboolean copyOnly)
{
    QVector<int> list;
    if (layers != nullptr) {
        const jsize n = env->GetArrayLength(layers);
        jint *elems = env->GetIntArrayElements(layers, nullptr);
        for (int i = 0; i < n; ++i) {
            list.append(int(elems[i]));
        }
        env->ReleaseIntArrayElements(layers, elems, JNI_ABORT);
    }
    return core()->applyTransformLayers(list, xscale, yscale, xshear, yshear,
                                        rotationRad, xtranslate, ytranslate,
                                        originX, originY, copyOnly == JNI_TRUE)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyPerspectiveTransform(JNIEnv *env, jobject,
                                                                        jdouble x0, jdouble y0,
                                                                        jdouble x1, jdouble y1,
                                                                        jdouble x2, jdouble y2,
                                                                        jdouble x3, jdouble y3,
                                                                        jdouble origX, jdouble origY,
                                                                        jdouble origW, jdouble origH)
{
    return core()->applyPerspectiveTransform(x0, y0, x1, y1, x2, y2, x3, y3,
                                             origX, origY, origW, origH)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_applyWarpMeshTransform(JNIEnv *env, jobject,
                                                                     jdoubleArray origXs, jdoubleArray origYs,
                                                                     jdoubleArray transfXs, jdoubleArray transfYs,
                                                                     jint count,
                                                                     jdouble origX, jdouble origY,
                                                                     jdouble origW, jdouble origH)
{
    if (count <= 0) return JNI_FALSE;
    jdouble *ox = env->GetDoubleArrayElements(origXs, nullptr);
    jdouble *oy = env->GetDoubleArrayElements(origYs, nullptr);
    jdouble *tx = env->GetDoubleArrayElements(transfXs, nullptr);
    jdouble *ty = env->GetDoubleArrayElements(transfYs, nullptr);

    QVector<QPointF> origPoints;
    QVector<QPointF> transfPoints;
    origPoints.reserve(count);
    transfPoints.reserve(count);
    for (int i = 0; i < count; ++i) {
        origPoints.append(QPointF(ox[i], oy[i]));
        transfPoints.append(QPointF(tx[i], ty[i]));
    }

    env->ReleaseDoubleArrayElements(origXs, ox, JNI_ABORT);
    env->ReleaseDoubleArrayElements(origYs, oy, JNI_ABORT);
    env->ReleaseDoubleArrayElements(transfXs, tx, JNI_ABORT);
    env->ReleaseDoubleArrayElements(transfYs, ty, JNI_ABORT);

    return core()->applyWarpMeshTransform(origPoints, transfPoints, origX, origY, origW, origH)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_cropCanvas(JNIEnv *, jobject, jint x, jint y, jint w, jint h)
{
    core()->cropCanvas(x, y, w, h);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_floodFillAt(
    JNIEnv *, jobject, jint x, jint y, jint tolerance, jboolean sampleMerged,
    jint expand, jint feather, jint closeGap)
{
    core()->floodFillAt(x, y, tolerance, sampleMerged, expand, feather, closeGap);
}

JNIEXPORT jstring JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_pickColorAt(JNIEnv *env, jobject, jint x, jint y, jboolean currentLayerOnly)
{
    const QString c = core()->pickColorAt(x, y, currentLayerOnly);
    return c.isEmpty() ? nullptr : env->NewStringUTF(c.toUtf8().constData());
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquify(JNIEnv *, jobject, jint fx, jint fy, jint tx, jint ty, jdouble strength, jint mode)
{
    core()->liquify(fx, fy, tx, ty, strength, mode);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyBegin(JNIEnv *env, jobject, jintArray layers)
{
    // Signature must match the Kotlin declaration exactly: JNI resolves by
    // symbol name only and does NOT validate arity - an extra count param
    // read a garbage register and the layer list resolved to nothing
    QVector<int> list;
    if (layers != nullptr) {
        const jsize n = env->GetArrayLength(layers);
        jint *elems = env->GetIntArrayElements(layers, nullptr);
        for (int i = 0; i < n; ++i) {
            list.append(int(elems[i]));
        }
        env->ReleaseIntArrayElements(layers, elems, JNI_ABORT);
    }
    core()->liquifyBegin(list);
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyEnd(JNIEnv *, jobject)
{
    core()->liquifyEnd();
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyCancel(JNIEnv *, jobject)
{
    core()->liquifyCancel();
}

// Phase 5 · C3-2 (docs/LIQUIFY-C3-FIELD-PLAN.md §3): 拖动期零引擎解算的两端。
// ① 取"未形变的源像素"给 GPU 场当源纹理 —— 只读图层, 不碰网格/不形变/不写回;
// ② 抬笔把 GPU 算好的像素结果一次性写回 —— 选区/Alpha 锁/脏区/撤销语义与经典路径一致。
JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyFieldSource(JNIEnv *, jobject, jint x, jint y,
                                                                 jint w, jint h)
{
    return core()->liquifyFieldSource(x, y, w, h) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyFieldCommit(JNIEnv *env, jobject, jint x,
                                                                 jint y, jint w, jint h,
                                                                 jbyteArray pixels,
                                                                 jboolean bottomUp)
{
    if (pixels == nullptr || w <= 0 || h <= 0) return JNI_FALSE;
    const qint64 need = qint64(w) * qint64(h) * 4;
    if (qint64(env->GetArrayLength(pixels)) < need) return JNI_FALSE;
    // 注意: `QVector<quint8> buf(int(need))` 会被当成函数声明(vexing parse), 必须用 resize 形式
    QVector<quint8> buf;
    buf.resize(int(need));
    env->GetByteArrayRegion(pixels, 0, jsize(need), reinterpret_cast<jbyte *>(buf.data()));
    core()->liquifyFieldCommit(x, y, w, h, buf, bottomUp == JNI_TRUE);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyFieldMode(JNIEnv *, jobject)
{
    return core()->liquifyFieldMode() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyStats(JNIEnv *env, jobject)
{
    // 上一次液化 apply 的分段耗时与规模:
    // [total, warp, seed, blit, composite, areaPx, targets, count, precision, cells]。
    // 只在标尺开启时读数 (每秒一次, 引擎线程调用); count 供 Kotlin 判断"是否有新数据"。
    qint64 stats[10] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    core()->liquifyStats(stats);
    jlongArray arr = env->NewLongArray(10);
    if (!arr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 10, reinterpret_cast<const jlong *>(stats));
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyRebaseStats(JNIEnv *env, jobject)
{
    // Phase 3 埋点 (docs/LIQUIFY-REBASE-INVESTIGATION.md §8): rebase / materialize 生命周期
    // + 拖动热路径的单位成本。
    //   [rebaseCount, reason, flushMs, flushMaxMs, cloneMs, oldAreaPx, newAreaPx,
    //    innerOverflowPx, gridPoints, throttleCount, throttleMs, throttleMaxMs,
    //    callCount, callUs, callMaxUs]
    // 独立于 liquifyStats(不改动它的 10 元契约); rebaseCount / throttleCount / callCount
    // 单调递增, 供 Kotlin 侧算窗口增量。纯诊断, 不参与任何渲染/提交路径。
    qint64 stats[15] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    core()->liquifyRebaseStats(stats);
    jlongArray arr = env->NewLongArray(15);
    if (!arr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 15, reinterpret_cast<const jlong *>(stats));
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyGrid(JNIEnv *env, jobject)
{
    // 当前液化网格的只读导出:
    //   [bx, by, bw, bh, columns, rows, precision, count,
    //    (origX, origY, offsetX, offsetY) × count]
    // row-major, 点坐标为文档坐标; 无活动网格时 count = 0(只返回 8 个表头值)。
    // 仅供性能标尺的网格可视化与后续 Preview 原型使用, 不参与任何渲染/提交路径。
    const ReverieCore::LiquifyGridExport g = core()->liquifyGridExport();
    const int total = 8 + g.count * 4;
    QVector<float> buf(total, 0.0f);
    buf[0] = float(g.bounds.x());
    buf[1] = float(g.bounds.y());
    buf[2] = float(g.bounds.width());
    buf[3] = float(g.bounds.height());
    buf[4] = float(g.columns);
    buf[5] = float(g.rows);
    buf[6] = float(g.precision);
    buf[7] = float(g.count);
    for (int i = 0; i < g.count; ++i) {
        const int base = 8 + i * 4;
        buf[base + 0] = float(g.original[i].x());
        buf[base + 1] = float(g.original[i].y());
        buf[base + 2] = float(g.offset[i].x());
        buf[base + 3] = float(g.offset[i].y());
    }
    jfloatArray arr = env->NewFloatArray(total);
    if (!arr) return nullptr;
    env->SetFloatArrayRegion(arr, 0, total, reinterpret_cast<const jfloat *>(buf.constData()));
    return arr;
}

JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyPreviewMeta(JNIEnv *env, jobject)
{
    // Phase 2A-2 预览元信息: [w, h, docX, docY, docW, docH, seq] —— w = 0 表示当前没有预览。
    // 只有 `setprop debug.reverie.liquifyPreview 1` 且处于括号手势时才会有非零 w。
    jint meta[7] = {0, 0, 0, 0, 0, 0, 0};
    core()->liquifyPreviewMeta(reinterpret_cast<int *>(meta));
    jintArray arr = env->NewIntArray(7);
    if (!arr) return nullptr;
    env->SetIntArrayRegion(arr, 0, 7, meta);
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyPreviewPixels(JNIEnv *env, jobject)
{
    // 预览像素 (RGBA8888, 自上而下)。调用方先取 meta 决定是否需要重新取(seq 变化)。
    jint meta[7] = {0, 0, 0, 0, 0, 0, 0};
    core()->liquifyPreviewMeta(reinterpret_cast<int *>(meta));
    const int bytes = meta[0] * meta[1] * 4;
    if (bytes <= 0) return nullptr;
    QVector<quint8> buf(bytes);
    core()->liquifyPreviewPixels(buf.data());
    jbyteArray arr = env->NewByteArray(bytes);
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, bytes, reinterpret_cast<const jbyte *>(buf.constData()));
    return arr;
}

JNIEXPORT jintArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyPreviewSourceMeta(JNIEnv *env, jobject)
{
    // Phase 2B 主机侧(AGSL)绘制的输入元信息: [cropW, cropH, docX, docY, docW, docH, seq]。
    // cropW = 0 表示当前没有可用源裁剪(不在预览态 / 非 8bit BGRA 文档 / 超出预算),
    // 调用方据此回退到引擎侧 CPU 预览。
    jint meta[7] = {0, 0, 0, 0, 0, 0, 0};
    core()->liquifyPreviewSourceMeta(reinterpret_cast<int *>(meta));
    jintArray arr = env->NewIntArray(7);
    if (!arr) return nullptr;
    env->SetIntArrayRegion(arr, 0, 7, meta);
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_liquifyPreviewSourcePixels(JNIEnv *env, jobject)
{
    // 未形变的 bounds 裁剪(RGBA8888, 1 像素 = 1 文档像素)。只在 rebase 时变,
    // 调用方按 seq 缓存, 不必每个 dab 都取。
    jint meta[7] = {0, 0, 0, 0, 0, 0, 0};
    core()->liquifyPreviewSourceMeta(reinterpret_cast<int *>(meta));
    const int bytes = meta[0] * meta[1] * 4;
    if (bytes <= 0) return nullptr;
    QVector<quint8> buf(bytes);
    core()->liquifyPreviewSourcePixels(buf.data());
    jbyteArray arr = env->NewByteArray(bytes);
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, bytes, reinterpret_cast<const jbyte *>(buf.constData()));
    return arr;
}

JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setLiquifyPreviewHostDrawMode(
    JNIEnv *, jobject, jint mode)
{
    // -1 跟随 system property / 0 强制引擎侧叠加(AGSL 初始化失败时的回退入口) / 1 强制主机侧绘制。
    core()->setLiquifyPreviewHostDrawMode(int(mode));
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setLiquifyBrushSize(JNIEnv *, jobject, jdouble size)
{
    core()->setLiquifyBrushSize(size);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_drawText(JNIEnv *env, jobject, jint x, jint y, jstring text, jdouble fontSize)
{
    const char *c = env->GetStringUTFChars(text, nullptr);
    core()->drawText(x, y, QString::fromUtf8(c), fontSize);
    env->ReleaseStringUTFChars(text, c);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_stampBitmap(
    JNIEnv *env, jobject, jint x, jint y, jobject bitmap)
{
    if (!bitmap) return;
    AndroidBitmapInfo info;
    void *pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        return;
    }
    core()->stampBitmap(x, y, (int)info.width, (int)info.height, pixels);
    AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setShapeStrokeWidth(JNIEnv *, jobject, jdouble w)
{
    core()->setShapeStrokeWidth(w);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_setShapeFilled(JNIEnv *, jobject, jboolean f)
{
    core()->setShapeFilled(f == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_drawShape(JNIEnv *, jobject, jint kind, jint x1, jint y1, jint x2, jint y2, jboolean filled)
{
    core()->drawShape(kind, x1, y1, x2, y2, filled == JNI_TRUE);
}

static inline void copyTransformPreviewToBitmap(const QImage &outImage, void *pixels, const AndroidBitmapInfo &info)
{
    const QImage &src = (outImage.width() == (int)info.width && outImage.height() == (int)info.height)
        ? outImage
        : outImage.scaled(info.width, info.height, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
    if (info.stride == info.width * 4 && src.bytesPerLine() == (int)info.width * 4) {
        memcpy(pixels, src.constBits(), size_t(info.width) * info.height * 4);
    } else {
        for (uint32_t y = 0; y < info.height; ++y) {
            memcpy(static_cast<char *>(pixels) + size_t(y) * info.stride,
                   src.constScanLine(y),
                   size_t(info.width) * 4);
        }
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_startTransformPreview(JNIEnv *env, jobject, jobject bitmap)
{
    if (!bitmap) return JNI_FALSE;
    QImage outImage;
    bool res = core()->startTransformPreview(QVector<int>(), &outImage);
    if (res && !outImage.isNull()) {
        AndroidBitmapInfo info;
        void *pixels;
        if (AndroidBitmap_getInfo(env, bitmap, &info) >= 0 &&
            info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
            AndroidBitmap_lockPixels(env, bitmap, &pixels) >= 0) {

            copyTransformPreviewToBitmap(outImage, pixels, info);
            AndroidBitmap_unlockPixels(env, bitmap);
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_startTransformPreviewLayers(
    JNIEnv *env, jobject, jintArray layers, jobject bitmap)
{
    if (!bitmap) return JNI_FALSE;
    QVector<int> list;
    if (layers != nullptr) {
        const jsize n = env->GetArrayLength(layers);
        jint *elems = env->GetIntArrayElements(layers, nullptr);
        for (int i = 0; i < n; ++i) {
            list.append(int(elems[i]));
        }
        env->ReleaseIntArrayElements(layers, elems, JNI_ABORT);
    }
    QImage outImage;
    bool res = core()->startTransformPreview(list, &outImage);
    if (res && !outImage.isNull()) {
        AndroidBitmapInfo info;
        void *pixels;
        if (AndroidBitmap_getInfo(env, bitmap, &info) >= 0 &&
            info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
            AndroidBitmap_lockPixels(env, bitmap, &pixels) >= 0) {

            copyTransformPreviewToBitmap(outImage, pixels, info);
            AndroidBitmap_unlockPixels(env, bitmap);
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_startTransformPreviewLayersEx(
    JNIEnv *env, jobject, jintArray layers, jobject bitmap, jboolean copyOnly)
{
    if (!bitmap) return JNI_FALSE;
    QVector<int> list;
    if (layers != nullptr) {
        const jsize n = env->GetArrayLength(layers);
        jint *elems = env->GetIntArrayElements(layers, nullptr);
        for (int i = 0; i < n; ++i) {
            list.append(int(elems[i]));
        }
        env->ReleaseIntArrayElements(layers, elems, JNI_ABORT);
    }
    QImage outImage;
    bool res = core()->startTransformPreview(list, &outImage, copyOnly == JNI_TRUE);
    if (res && !outImage.isNull()) {
        AndroidBitmapInfo info;
        void *pixels;
        if (AndroidBitmap_getInfo(env, bitmap, &info) >= 0 &&
            info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
            AndroidBitmap_lockPixels(env, bitmap, &pixels) >= 0) {

            copyTransformPreviewToBitmap(outImage, pixels, info);
            AndroidBitmap_unlockPixels(env, bitmap);
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_reverie_paint_core_ReverieCoreBridge_cancelTransformPreview(JNIEnv *env, jobject)
{
    core()->cancelTransformPreview();
}
}
