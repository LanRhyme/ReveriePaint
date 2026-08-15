#include "ReverieCoreInternal.h"
#include "ReverieCoreInternal.h"

/** FX filter preview cases (19-34): drop shadow, neon, ripple, twirl, etc. */
void applyFilterFxCases(QImage &img, int w, int h, int filterType,
                        double p1, double p2, double p3, double p4)
{
    switch (filterType) {
    case 19: { // Drop Shadow / 投影: p1=angle(0..360), p2=distance(0..50), p3=radius(1..40), p4=opacity(0.0..1.0)
        const double angleRad = p1 * M_PI / 180.0;
        const double dist = qBound(0.0, p2, 50.0);
        const int rad = qBound(1, int(p3), 40);
        const double opacity = qBound(0.0, p4, 1.0);
        const int offX = int(std::round(dist * cos(angleRad)));
        const int offY = int(std::round(dist * sin(angleRad)));

        QVector<quint32> shadow(w * h, 0);
        QVector<quint32> tmp(w * h, 0);
        QVector<quint32> orig(w * h);
        memcpy(orig.data(), img.constBits(), w * h * 4);
        const quint32 *srcData = orig.constData();

        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                for (int x = 0; x < w; ++x) {
                    int sx = x - offX;
                    int sy = y - offY;
                    if (sx >= 0 && sx < w && sy >= 0 && sy < h) {
                        int a = (srcData[sy * w + sx] >> 24) & 0xFF;
                        if (a > 0) {
                            int sa = int(a * opacity);
                            shadow[y * w + x] = (quint32(sa) << 24); // black shadow with alpha sa
                        }
                    }
                }
            }
        });
        int rBox = qMax(1, int(rad * 0.577));
        boxBlurH(shadow.constData(), tmp.data(), w, h, rBox);
        boxBlurV(tmp.constData(), shadow.data(), w, h, rBox);
        boxBlurH(shadow.constData(), tmp.data(), w, h, rBox);
        boxBlurV(tmp.constData(), shadow.data(), w, h, rBox);

        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *dst = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint32 fgPix = srcData[y * w + x];
                    int fgA = (fgPix >> 24) & 0xFF;
                    int fgR = (fgPix >> 16) & 0xFF;
                    int fgG = (fgPix >> 8) & 0xFF;
                    int fgB = fgPix & 0xFF;

                    int shA = (shadow[y * w + x] >> 24) & 0xFF;
                    // Composite: Foreground over Black Shadow
                    int outA = fgA + (shA * (255 - fgA)) / 255;
                    quint8 *px = dst + x * 4;
                    if (outA > 0) {
                        px[2] = quint8(qBound(0, (fgR * fgA) / outA, 255));
                        px[1] = quint8(qBound(0, (fgG * fgA) / outA, 255));
                        px[0] = quint8(qBound(0, (fgB * fgA) / outA, 255));
                        px[3] = quint8(qBound(0, outA, 255));
                    } else {
                        px[0] = 0; px[1] = 0; px[2] = 0; px[3] = 0;
                    }
                }
            }
        });
        break;
    }
    case 20: { // Luminance to Opacity (亮度转不透明度): p1 = invert (0 or 1)
        const bool invert = (p1 > 0.5);
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    int lum = (px[2] * 299 + px[1] * 587 + px[0] * 114) / 1000;
                    if (invert) lum = 255 - lum;
                    px[3] = quint8((px[3] * lum) / 255);
                }
            }
        });
        break;
    }
    case 21: { // Oil Paint / Kuwahara filter: p1 = radius (1..5)
        const int rad = qBound(1, int(p1), 5);
        QVector<quint32> buffer(w * h);
        memcpy(buffer.data(), img.constBits(), w * h * 4);
        const quint32 *src = buffer.constData();

        filterParallelFor(0, h, [&](int startY, int endY) {
            const int quadBounds[4][4] = {
                {-rad, 0, -rad, 0},
                {0, rad, -rad, 0},
                {-rad, 0, 0, rad},
                {0, rad, 0, rad}
            };
            for (int y = startY; y < endY; ++y) {
                quint8 *dst = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *target = dst + x * 4;
                    if (target[3] == 0) continue;

                    int minVar = 0x7FFFFFFF;
                    int bestR = target[2], bestG = target[1], bestB = target[0];

                    for (int q = 0; q < 4; ++q) {
                        int qx0 = quadBounds[q][0], qx1 = quadBounds[q][1];
                        int qy0 = quadBounds[q][2], qy1 = quadBounds[q][3];
                        int sumR = 0, sumG = 0, sumB = 0, count = 0;
                        int sumSqR = 0, sumSqG = 0, sumSqB = 0;

                        for (int dy = qy0; dy <= qy1; ++dy) {
                            int ny = qBound(0, y + dy, h - 1);
                            int rowOff = ny * w;
                            for (int dx = qx0; dx <= qx1; ++dx) {
                                int nx = qBound(0, x + dx, w - 1);
                                quint32 c = src[rowOff + nx];
                                int r = (c >> 16) & 0xFF;
                                int g = (c >> 8) & 0xFF;
                                int b = c & 0xFF;
                                sumR += r; sumG += g; sumB += b;
                                sumSqR += r * r; sumSqG += g * g; sumSqB += b * b;
                                count++;
                            }
                        }
                        if (count > 0) {
                            int meanR = sumR / count, meanG = sumG / count, meanB = sumB / count;
                            int var = (sumSqR - meanR * sumR) + (sumSqG - meanG * sumG) + (sumSqB - meanB * sumB);
                            if (var < minVar) {
                                minVar = var;
                                bestR = meanR; bestG = meanG; bestB = meanB;
                            }
                        }
                    }
                    target[2] = quint8(bestR);
                    target[1] = quint8(bestG);
                    target[0] = quint8(bestB);
                }
            }
        });
        break;
    }
    case 22: { // Radial / Zoom Blur: p1 = amount (1..50), p2 = cx (0..1), p3 = cy (0..1)
        const int amount = qBound(1, int(p1), 50);
        const double cx = (p2 > 0.0) ? p2 * w : w * 0.5;
        const double cy = (p3 > 0.0) ? p3 * h : h * 0.5;
        QVector<quint32> buffer(w * h);
        memcpy(buffer.data(), img.constBits(), w * h * 4);
        const quint32 *srcData = buffer.constData();
        quint32 *dstData = reinterpret_cast<quint32 *>(img.bits());

        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                for (int x = 0; x < w; ++x) {
                    double dx = x - cx;
                    double dy = y - cy;
                    int sumA = 0, sumRA = 0, sumGA = 0, sumBA = 0;
                    int count = 0;
                    const int samples = 16;
                    for (int s = 0; s < samples; ++s) {
                        double scale = 1.0 - (double(s) / samples) * (double(amount) / 100.0);
                        int sx = qBound(0, int(std::lround(cx + dx * scale)), w - 1);
                        int sy = qBound(0, int(std::lround(cy + dy * scale)), h - 1);
                        quint32 c = srcData[sy * w + sx];
                        int a = (c >> 24) & 0xFF;
                        int r = (c >> 16) & 0xFF;
                        int g = (c >> 8) & 0xFF;
                        int b = c & 0xFF;
                        sumA += a;
                        sumRA += r * a;
                        sumGA += g * a;
                        sumBA += b * a;
                        count++;
                    }
                    if (sumA > 0 && count > 0) {
                        int finalA = sumA / count;
                        int finalR = qBound(0, sumRA / sumA, 255);
                        int finalG = qBound(0, sumGA / sumA, 255);
                        int finalB = qBound(0, sumBA / sumA, 255);
                        dstData[y * w + x] = (quint32(finalA) << 24) |
                                             (quint32(finalR) << 16) |
                                             (quint32(finalG) << 8) |
                                             quint32(finalB);
                    } else {
                        dstData[y * w + x] = 0;
                    }
                }
            }
        });
        break;
    }
    case 23: { // Halftone (半色调网点): p1 = dotSize (4..24)
        const int dotSize = qBound(4, int(p1), 24);
        const double halfDot = dotSize * 0.5;
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    int lum = (px[2] * 299 + px[1] * 587 + px[0] * 114) / 1000;
                    int cellX = x % dotSize;
                    int cellY = y % dotSize;
                    double dist = sqrt((cellX - halfDot) * (cellX - halfDot) + (cellY - halfDot) * (cellY - halfDot));
                    double maxDist = halfDot * (1.0 - (lum / 255.0));
                    quint8 val = (dist <= maxDist) ? 0 : 255;
                    px[2] = val; px[1] = val; px[0] = val;
                }
            }
        });
        break;
    }
    case 24: { // Exposure & Gamma (曝光与伽马): p1 = exposure (-3..3), p2 = gamma (0.2..3.0)
        const double expScale = pow(2.0, p1);
        const double invGamma = 1.0 / qMax(0.1, p2);
        quint8 lut[256];
        for (int i = 0; i < 256; ++i) {
            double v = qBound(0.0, (double(i) / 255.0) * expScale, 1.0);
            lut[i] = quint8(qBound(0.0, pow(v, invGamma) * 255.0, 255.0));
        }
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    px[2] = lut[px[2]];
                    px[1] = lut[px[1]];
                    px[0] = lut[px[0]];
                }
            }
        });
        break;
    }
    case 25: { // Edge Glow / Neon (边缘霓虹发光): p1 = strength (0.5..5.0), p2 = radius (1..30), p3 = hueMode (0: 原色, 1: 青蓝, 2: 粉紫, 3: 炫金)
        const double strength = (p1 > 0.0) ? qBound(0.5, p1, 5.0) : 2.5;
        const int rad = (p2 > 0.0) ? qBound(1, int(p2), 30) : 8;
        const int hueMode = int(p3 + 0.5);

        QImage tmp = img.copy();
        QVector<quint32> glow(w * h, 0);
        QVector<quint32> buf(w * h, 0);

        filterParallelFor(0, h, [&](int startY, int endY) {
            const int kx[3][3] = {{-1, 0, 1}, {-2, 0, 2}, {-1, 0, 1}};
            const int ky[3][3] = {{-1, -2, -1}, {0, 0, 0}, {1, 2, 1}};
            for (int y = startY; y < endY; ++y) {
                for (int x = 0; x < w; ++x) {
                    int gx = 0, gy = 0, gax = 0, gay = 0;
                    for (int dy = -1; dy <= 1; ++dy) {
                        int ny = qBound(0, y + dy, h - 1);
                        const quint8 *row = tmp.constScanLine(ny);
                        for (int dx = -1; dx <= 1; ++dx) {
                            int nx = qBound(0, x + dx, w - 1);
                            const quint8 *p = row + nx * 4;
                            int lum = (p[2] * 299 + p[1] * 587 + p[0] * 114) / 1000;
                            gx += lum * kx[dy + 1][dx + 1];
                            gy += lum * ky[dy + 1][dx + 1];
                            gax += p[3] * kx[dy + 1][dx + 1];
                            gay += p[3] * ky[dy + 1][dx + 1];
                        }
                    }
                    int magLum = int(sqrt(gx * gx + gy * gy) * strength);
                    int magA = int(sqrt(gax * gax + gay * gay) * strength);
                    int mag = qBound(0, qMax(magLum, magA), 255);
                    if (mag > 0) {
                        const quint8 *pOrig = tmp.constScanLine(y) + x * 4;
                        int nr, ng, nb;
                        if (hueMode == 1) { // 赛博青蓝 (Cyan #00F5FF)
                            nr = 0; ng = 245; nb = 255;
                        } else if (hueMode == 2) { // 霓虹粉紫 (Magenta #FF1493)
                            nr = 255; ng = 20; nb = 147;
                        } else if (hueMode == 3) { // 炫彩金黄 (Gold #FFD700)
                            nr = 255; ng = 215; nb = 0;
                        } else { // 原色增强
                            int maxC = qMax(qMax(int(pOrig[2]), int(pOrig[1])), int(pOrig[0]));
                            if (maxC > 10) {
                                nr = qBound(0, (pOrig[2] * 255) / maxC, 255);
                                ng = qBound(0, (pOrig[1] * 255) / maxC, 255);
                                nb = qBound(0, (pOrig[0] * 255) / maxC, 255);
                            } else {
                                nr = 0; ng = 230; nb = 255;
                            }
                        }
                        glow[y * w + x] = (quint32(mag) << 24) | (quint32(nr) << 16) | (quint32(ng) << 8) | quint32(nb);
                    }
                }
            }
        });

        int rBox = qMax(1, int(rad * 0.577));
        boxBlurH(glow.constData(), buf.data(), w, h, rBox);
        boxBlurV(buf.constData(), glow.data(), w, h, rBox);
        boxBlurH(glow.constData(), buf.data(), w, h, rBox);
        boxBlurV(buf.constData(), glow.data(), w, h, rBox);

        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *dst = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint32 gPix = glow[y * w + x];
                    int ga = (gPix >> 24) & 0xFF;
                    const quint8 *pOrig = tmp.constScanLine(y) + x * 4;
                    quint8 *px = dst + x * 4;
                    if (ga > 0) {
                        int gr = (gPix >> 16) & 0xFF;
                        int gg = (gPix >> 8) & 0xFF;
                        int gb = gPix & 0xFF;
                        double gFactor = double(ga) / 255.0;
                        int addR = int(gr * gFactor);
                        int addG = int(gg * gFactor);
                        int addB = int(gb * gFactor);

                        // Screen blend neon glow over artwork
                        px[2] = quint8(qBound(0, 255 - ((255 - pOrig[2]) * (255 - addR)) / 255, 255));
                        px[1] = quint8(qBound(0, 255 - ((255 - pOrig[1]) * (255 - addG)) / 255, 255));
                        px[0] = quint8(qBound(0, 255 - ((255 - pOrig[0]) * (255 - addB)) / 255, 255));
                        int newA = qMax(int(pOrig[3]), ga);
                        px[3] = quint8(qBound(0, newA, 255));
                    } else {
                        px[2] = pOrig[2]; px[1] = pOrig[1]; px[0] = pOrig[0]; px[3] = pOrig[3];
                    }
                }
            }
        });
        break;
    }
    case 26: { // Lens / Defocus Blur: p1 = radius (1..30)
        const int rad = qBound(1, int(p1), 30);
        QVector<quint32> buffer(w * h);
        QVector<quint32> tmp(w * h);
        memcpy(buffer.data(), img.constBits(), w * h * 4);
        int rBox = qMax(1, int(rad * 0.7));
        boxBlurH(buffer.constData(), tmp.data(), w, h, rBox);
        boxBlurV(tmp.constData(), buffer.data(), w, h, rBox);
        boxBlurH(buffer.constData(), tmp.data(), w, h, rBox);
        boxBlurV(tmp.constData(), reinterpret_cast<quint32 *>(img.bits()), w, h, rBox);
        break;
    }
    case 27: { // Shadows & Highlights (阴影与高光): p1 = shadows boost(0..100), p2 = highlights reduce(0..100)
        const double sBoost = qBound(0.0, p1 / 100.0, 1.0);
        const double hReduce = qBound(0.0, p2 / 100.0, 1.0);
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    int r = px[2], g = px[1], b = px[0];
                    double lum = (r * 0.299 + g * 0.587 + b * 0.114) / 255.0;

                    // Shadow factor (strongest at lum=0, fades to 0 at lum=0.7)
                    double sFactor = sBoost * qMax(0.0, 1.0 - lum / 0.7);
                    // Highlight factor (strongest at lum=1, fades to 0 at lum=0.3)
                    double hFactor = hReduce * qMax(0.0, (lum - 0.3) / 0.7);

                    double scale = 1.0 + sFactor * 0.8 - hFactor * 0.5;
                    px[2] = quint8(qBound(0, int(r * scale), 255));
                    px[1] = quint8(qBound(0, int(g * scale), 255));
                    px[0] = quint8(qBound(0, int(b * scale), 255));
                }
            }
        });
        break;
    }
    case 28: { // Vibrance (自然饱和度): p1 = vibrance (-100..100)
        const double vib = qBound(-1.0, p1 / 100.0, 1.0);
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    int r = px[2], g = px[1], b = px[0];
                    int maxC = qMax(r, qMax(g, b));
                    int minC = qMin(r, qMin(g, b));
                    if (maxC == minC) continue;
                    double sat = double(maxC - minC) / double(maxC);
                    // Vibrance boosts unsaturated colors more
                    double amt = vib * (1.0 - sat);
                    double lum = (r * 0.299 + g * 0.587 + b * 0.114);
                    px[2] = quint8(qBound(0, int(lum + (r - lum) * (1.0 + amt)), 255));
                    px[1] = quint8(qBound(0, int(lum + (g - lum) * (1.0 + amt)), 255));
                    px[0] = quint8(qBound(0, int(lum + (b - lum) * (1.0 + amt)), 255));
                }
            }
        });
        break;
    }
    case 29: { // Color to Alpha (颜色转透明度): p1 = targetColor(0xRRGGBB), p2 = tolerance(0..100), p3 = smoothness(0..50)
        const quint32 target = (quint32)p1;
        const int tr = (target >> 16) & 0xFF;
        const int tg = (target >> 8) & 0xFF;
        const int tb = target & 0xFF;
        const double tol = qBound(0.0, p2, 100.0) * 4.41; // max dist is ~441
        const double smooth = qMax(1.0, p3 * 4.41);

        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    int dr = px[2] - tr;
                    int dg = px[1] - tg;
                    int db = px[0] - tb;
                    double dist = sqrt(dr * dr + dg * dg + db * db);
                    if (dist <= tol) {
                        px[3] = 0;
                    } else if (dist < tol + smooth) {
                        double factor = (dist - tol) / smooth;
                        px[3] = quint8(px[3] * factor);
                    }
                }
            }
        });
        break;
    }
    case 31: { // Water Ripple / Waves (水波纹扭曲): p1 = amplitude(1..30), p2 = frequency(1..50)
        const double amp = qBound(1.0, p1, 30.0);
        const double freq = qBound(1.0, p2, 50.0);
        const double lambda = (w + h) / (freq * 2.0);
        QVector<quint32> buffer(w * h);
        memcpy(buffer.data(), img.constBits(), w * h * 4);
        const quint32 *srcData = buffer.constData();
        quint32 *dstData = reinterpret_cast<quint32 *>(img.bits());

        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                for (int x = 0; x < w; ++x) {
                    double offX = amp * sin((2.0 * M_PI * y) / lambda);
                    double offY = amp * cos((2.0 * M_PI * x) / lambda);
                    int sx = qBound(0, int(std::lround(x + offX)), w - 1);
                    int sy = qBound(0, int(std::lround(y + offY)), h - 1);
                    dstData[y * w + x] = srcData[sy * w + sx];
                }
            }
        });
        break;
    }
    case 32: { // Twirl / Swirl (旋涡扭曲): p1 = angle(-360..360), p2 = radius(10..300)
        const double maxAngleRad = (p1 * M_PI) / 180.0;
        const double maxRadius = qBound(10.0, p2, 500.0);
        const double cx = w * 0.5;
        const double cy = h * 0.5;
        QVector<quint32> buffer(w * h);
        memcpy(buffer.data(), img.constBits(), w * h * 4);
        const quint32 *srcData = buffer.constData();
        quint32 *dstData = reinterpret_cast<quint32 *>(img.bits());

        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                for (int x = 0; x < w; ++x) {
                    double dx = x - cx;
                    double dy = y - cy;
                    double dist = sqrt(dx * dx + dy * dy);
                    if (dist < maxRadius) {
                        double factor = 1.0 - (dist / maxRadius);
                        double angle = maxAngleRad * factor * factor;
                        double cosA = cos(angle);
                        double sinA = sin(angle);
                        double rx = cx + (dx * cosA - dy * sinA);
                        double ry = cy + (dx * sinA + dy * cosA);
                        int sx = qBound(0, int(std::lround(rx)), w - 1);
                        int sy = qBound(0, int(std::lround(ry)), h - 1);
                        dstData[y * w + x] = srcData[sy * w + sx];
                    } else {
                        dstData[y * w + x] = srcData[y * w + x];
                    }
                }
            }
        });
        break;
    }
    case 33: { // Surface Blur / Bilateral Filter (保边平滑磨皮): p1 = radius(1..6), p2 = threshold(5..80)
        const int rad = qBound(1, int(p1), 6);
        const double maxDistSq = qMax(1.0, p2 * p2 * 3.0);
        QVector<quint32> buffer(w * h);
        memcpy(buffer.data(), img.constBits(), w * h * 4);
        const quint32 *srcData = buffer.constData();
        quint32 *dstData = reinterpret_cast<quint32 *>(img.bits());

        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                for (int x = 0; x < w; ++x) {
                    quint32 centerPix = srcData[y * w + x];
                    int ca = (centerPix >> 24) & 0xFF;
                    if (ca == 0) {
                        dstData[y * w + x] = 0;
                        continue;
                    }
                    int cr = (centerPix >> 16) & 0xFF;
                    int cg = (centerPix >> 8) & 0xFF;
                    int cb = centerPix & 0xFF;

                    double sumWeight = 0.0;
                    double sumR = 0.0, sumG = 0.0, sumB = 0.0;

                    for (int dy = -rad; dy <= rad; ++dy) {
                        int ny = qBound(0, y + dy, h - 1);
                        int rowOff = ny * w;
                        for (int dx = -rad; dx <= rad; ++dx) {
                            int nx = qBound(0, x + dx, w - 1);
                            quint32 p = srcData[rowOff + nx];
                            int pa = (p >> 24) & 0xFF;
                            if (pa == 0) continue;
                            int pr = (p >> 16) & 0xFF;
                            int pg = (p >> 8) & 0xFF;
                            int pb = p & 0xFF;

                            double colorDistSq = (pr - cr) * (pr - cr) + (pg - cg) * (pg - cg) + (pb - cb) * (pb - cb);
                            if (colorDistSq <= maxDistSq) {
                                double diff = 1.0 - (colorDistSq / maxDistSq);
                                double wVal = diff * diff;
                                sumWeight += wVal;
                                sumR += pr * wVal;
                                sumG += pg * wVal;
                                sumB += pb * wVal;
                            }
                        }
                    }
                    if (sumWeight > 0.0) {
                        int fr = qBound(0, int(sumR / sumWeight), 255);
                        int fg = qBound(0, int(sumG / sumWeight), 255);
                        int fb = qBound(0, int(sumB / sumWeight), 255);
                        dstData[y * w + x] = (quint32(ca) << 24) | (quint32(fr) << 16) | (quint32(fg) << 8) | quint32(fb);
                    } else {
                        dstData[y * w + x] = centerPix;
                    }
                }
            }
        });
        break;
    }
    case 34: { // Scanlines / CRT (扫描线与CRT): p1 = spacing(2..12), p2 = intensity(0..100)
        const int spacing = qBound(2, int(p1), 12);
        const double intensity = qBound(0.0, p2 / 100.0, 1.0);
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                bool isScanline = (y % spacing == 0);
                double darkFactor = isScanline ? (1.0 - intensity * 0.6) : 1.0;
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    px[2] = quint8(qBound(0, int(px[2] * darkFactor), 255));
                    px[1] = quint8(qBound(0, int(px[1] * darkFactor), 255));
                    px[0] = quint8(qBound(0, int(px[0] * darkFactor), 255));
                }
            }
        });
        break;
    }
    default:
        break;
    }
}
