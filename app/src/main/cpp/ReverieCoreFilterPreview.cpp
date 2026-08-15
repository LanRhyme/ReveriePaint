#include "ReverieCoreInternal.h"


void ReverieCore::applyFilterPreview(int index, int filterType, double p1, double p2, double p3, double p4)
{
    if (!isLayerEditable(index)) return;
    if (!m_filterBackupDevice || m_filterBackupIndex != index) {
        beginFilterPreview(index);
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev || !m_filterBackupDevice) return;

    const int w = m_docWidth;
    const int h = m_docHeight;
    QImage img(w, h, QImage::Format_ARGB32_Premultiplied);
    m_filterBackupDevice->readBytes(img.bits(), 0, 0, w, h);

    switch (filterType) {
    case 0: { // HSBC: Hue (-180..180), Sat (0..2), Bright (0..2), Contrast (0..2)
        const double hShift = p1;
        const double sScale = qMax(0.0, p2);
        const double vScale = qMax(0.0, p3);
        const double cScale = qMax(0.0, p4);

        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue; // Skip transparent
                    int r = px[2], g = px[1], b = px[0];
                    QColor col(r, g, b);
                    float qh = 0.0f, qs = 0.0f, qv = 0.0f;
                    col.getHsvF(&qh, &qs, &qv);
                    if (qh >= 0.0f) {
                        qh = fmodf(qh + float(hShift) / 360.0f + 1.0f, 1.0f);
                    } else if (hShift != 0.0) {
                        qh = fmodf(float(hShift) / 360.0f + 1.0f, 1.0f);
                    }
                    qs = qBound(0.0f, qs * float(sScale), 1.0f);
                    qv = qBound(0.0f, qv * float(vScale), 1.0f);
                    col.setHsvF(qh, qs, qv);
                    r = col.red(); g = col.green(); b = col.blue();
                    if (cScale != 1.0) {
                        r = qBound(0, int((r - 128) * cScale + 128), 255);
                        g = qBound(0, int((g - 128) * cScale + 128), 255);
                        b = qBound(0, int((b - 128) * cScale + 128), 255);
                    }
                    px[2] = quint8(r); px[1] = quint8(g); px[0] = quint8(b);
                }
            }
        });
        break;
    }
    case 1: { // Color Balance: Cyan-Red (-100..100), Magenta-Green (-100..100), Yellow-Blue (-100..100)
        const int cr = int(p1 * 1.28);
        const int mg = int(p2 * 1.28);
        const int yb = int(p3 * 1.28);
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    px[2] = quint8(qBound(0, px[2] + cr, 255));
                    px[1] = quint8(qBound(0, px[1] + mg, 255));
                    px[0] = quint8(qBound(0, px[0] + yb, 255));
                }
            }
        });
        break;
    }
    case 2: { // Gaussian Blur: radius (1..100) via 3-pass fast sliding-window Box Blur with alpha weighting
        const int rad = qBound(1, int(p1), 100);
        QVector<quint32> buffer(w * h);
        quint32 *imgData = reinterpret_cast<quint32 *>(img.bits());
        quint32 *tmpData = buffer.data();

        int r = qMax(1, int(rad * 0.577)); // equivalent sigma matching
        boxBlurH(imgData, tmpData, w, h, r);
        boxBlurV(tmpData, imgData, w, h, r);
        boxBlurH(imgData, tmpData, w, h, r);
        boxBlurV(tmpData, imgData, w, h, r);
        boxBlurH(imgData, tmpData, w, h, r);
        boxBlurV(tmpData, imgData, w, h, r);
        break;
    }
    case 3: { // Motion Blur: angle (0..360), distance (1..100) with alpha weighting
        const double angleRad = p1 * M_PI / 180.0;
        const int dist = qBound(1, int(p2), 100);
        const double dirX = cos(angleRad);
        const double dirY = sin(angleRad);

        QVector<quint32> buffer(w * h);
        memcpy(buffer.data(), img.constBits(), w * h * 4);
        const quint32 *srcData = buffer.constData();
        quint32 *dstData = reinterpret_cast<quint32 *>(img.bits());

        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                for (int x = 0; x < w; ++x) {
                    int sumA = 0, sumRA = 0, sumGA = 0, sumBA = 0;
                    int count = 0;
                    for (int s = -dist; s <= dist; ++s) {
                        int nx = qBound(0, int(std::lround(x + s * dirX)), w - 1);
                        int ny = qBound(0, int(std::lround(y + s * dirY)), h - 1);
                        quint32 c = srcData[ny * w + nx];
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
    case 4: { // Sharpen: strength (0.1..3.0) via Alpha-weighted Unsharp Masking
        const double strength = qBound(0.1, p1, 3.0);
        QImage tmp = img.copy();
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *dst = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    const quint8 *pC = tmp.constScanLine(y) + x * 4;
                    quint8 *px = dst + x * 4;
                    if (pC[3] == 0) {
                        px[0] = 0; px[1] = 0; px[2] = 0; px[3] = 0;
                        continue;
                    }
                    int sumA = 0, sumR = 0, sumG = 0, sumB = 0;
                    for (int dy = -1; dy <= 1; ++dy) {
                        int ny = qBound(0, y + dy, h - 1);
                        const quint8 *row = tmp.constScanLine(ny);
                        for (int dx = -1; dx <= 1; ++dx) {
                            int nx = qBound(0, x + dx, w - 1);
                            const quint8 *p = row + nx * 4;
                            int a = p[3];
                            sumA += a;
                            sumR += p[2] * a;
                            sumG += p[1] * a;
                            sumB += p[0] * a;
                        }
                    }
                    int blurR = (sumA > 0) ? (sumR / sumA) : pC[2];
                    int blurG = (sumA > 0) ? (sumG / sumA) : pC[1];
                    int blurB = (sumA > 0) ? (sumB / sumA) : pC[0];

                    int diffR = pC[2] - blurR;
                    int diffG = pC[1] - blurG;
                    int diffB = pC[0] - blurB;

                    px[2] = quint8(qBound(0, int(pC[2] + diffR * strength), 255));
                    px[1] = quint8(qBound(0, int(pC[1] + diffG * strength), 255));
                    px[0] = quint8(qBound(0, int(pC[0] + diffB * strength), 255));
                    px[3] = pC[3];
                }
            }
        });
        break;
    }
    case 5: { // Mosaic / Pixelate: blockSize (2..64)
        const int bs = qBound(2, int(p1), 64);
        for (int by = 0; by < h; by += bs) {
            for (int bx = 0; bx < w; bx += bs) {
                int r = 0, g = 0, b = 0, a = 0, count = 0;
                for (int dy = 0; dy < bs && by + dy < h; ++dy) {
                    for (int dx = 0; dx < bs && bx + dx < w; ++dx) {
                        const quint8 *p = img.constScanLine(by + dy) + (bx + dx) * 4;
                        r += p[2]; g += p[1]; b += p[0]; a += p[3];
                        count++;
                    }
                }
                if (count == 0) continue;
                quint8 ar = r / count, ag = g / count, ab = b / count, aa = a / count;
                for (int dy = 0; dy < bs && by + dy < h; ++dy) {
                    quint8 *line = img.scanLine(by + dy);
                    for (int dx = 0; dx < bs && bx + dx < w; ++dx) {
                        quint8 *px = line + (bx + dx) * 4;
                        px[2] = ar; px[1] = ag; px[0] = ab; px[3] = aa;
                    }
                }
            }
        }
        break;
    }
    case 6: { // Invert / 反相: p1 = amount (0..100)
        const double amt = (p1 > 0.0) ? qBound(0.0, p1 / 100.0, 1.0) : 1.0;
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    int invR = 255 - px[2], invG = 255 - px[1], invB = 255 - px[0];
                    px[2] = quint8(qBound(0, int(px[2] * (1.0 - amt) + invR * amt), 255));
                    px[1] = quint8(qBound(0, int(px[1] * (1.0 - amt) + invG * amt), 255));
                    px[0] = quint8(qBound(0, int(px[0] * (1.0 - amt) + invB * amt), 255));
                }
            }
        });
        break;
    }
    case 7: { // Luminance to Alpha / 提取线稿: p1 = threshold (0..255), p2 = invertLineColor (0 or 1)
        const int thresh = (p1 > 0.0) ? qBound(0, int(p1), 255) : 255;
        const bool whiteLine = (p2 > 0.5);
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    int lum = (px[2] * 299 + px[1] * 587 + px[0] * 114) / 1000;
                    int targetAlpha = (lum < thresh) ? ((thresh - lum) * 255) / qMax(1, thresh) : 0;
                    int newAlpha = (targetAlpha * px[3]) / 255;
                    px[3] = quint8(qBound(0, newAlpha, 255));
                    quint8 lineC = whiteLine ? 255 : 0;
                    px[2] = lineC; px[1] = lineC; px[0] = lineC;
                }
            }
        });
        break;
    }
    case 8: { // Find Edges (Sobel): p1 = strength (0.5..10), p2 = mode (0: 白底黑线线稿, 1: 黑底彩色边缘, 2: 透明线稿)
        const double strength = (p1 > 0.0) ? qBound(0.5, p1, 10.0) : 2.0;
        const int mode = int(p2 + 0.5);
        QImage tmp = img.copy();
        filterParallelFor(0, h, [&](int startY, int endY) {
            const int kx[3][3] = {{-1, 0, 1}, {-2, 0, 2}, {-1, 0, 1}};
            const int ky[3][3] = {{-1, -2, -1}, {0, 0, 0}, {1, 2, 1}};
            for (int y = startY; y < endY; ++y) {
                quint8 *dst = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    int grx = 0, gry = 0, ggx = 0, ggy = 0, gbx = 0, gby = 0, gax = 0, gay = 0;
                    for (int dy = -1; dy <= 1; ++dy) {
                        int ny = qBound(0, y + dy, h - 1);
                        const quint8 *row = tmp.constScanLine(ny);
                        for (int dx = -1; dx <= 1; ++dx) {
                            int nx = qBound(0, x + dx, w - 1);
                            const quint8 *p = row + nx * 4;
                            int k_x = kx[dy + 1][dx + 1];
                            int k_y = ky[dy + 1][dx + 1];
                            grx += p[2] * k_x; gry += p[2] * k_y;
                            ggx += p[1] * k_x; ggy += p[1] * k_y;
                            gbx += p[0] * k_x; gby += p[0] * k_y;
                            gax += p[3] * k_x; gay += p[3] * k_y;
                        }
                    }
                    int magR = qBound(0, int(sqrt(grx * grx + gry * gry) * strength), 255);
                    int magG = qBound(0, int(sqrt(ggx * ggx + ggy * ggy) * strength), 255);
                    int magB = qBound(0, int(sqrt(gbx * gbx + gby * gby) * strength), 255);
                    int magA = qBound(0, int(sqrt(gax * gax + gay * gay) * strength), 255);
                    int mag = (magR * 299 + magG * 587 + magB * 114) / 1000;
                    mag = qMax(mag, magA);

                    quint8 *px = dst + x * 4;
                    if (mode == 0) { // 白底黑线 (线稿)
                        int lineVal = qBound(0, 255 - mag, 255);
                        px[2] = quint8(lineVal);
                        px[1] = quint8(lineVal);
                        px[0] = quint8(lineVal);
                        px[3] = 255;
                    } else if (mode == 1) { // 黑底彩色边缘 (轮廓高亮)
                        px[2] = quint8(magR);
                        px[1] = quint8(magG);
                        px[0] = quint8(magB);
                        px[3] = 255;
                    } else { // 透明背景黑线 (提取透明线稿)
                        px[2] = 0;
                        px[1] = 0;
                        px[0] = 0;
                        px[3] = quint8(qBound(0, mag, 255));
                    }
                }
            }
        });
        break;
    }
    case 9: { // Emboss / 浮雕: p1 = depth (0.5..10.0), p2 = angle (0..360), p3 = preserveColor (0 or 1)
        const double depth = (p1 > 0.0) ? qBound(0.5, p1, 10.0) : 2.0;
        const double angleRad = p2 * M_PI / 180.0;
        const bool preserveColor = (p3 > 0.5);

        // Light vector in 3D: (cosA, sinA, 1.0)
        const double lx = cos(angleRad);
        const double ly = sin(angleRad);
        const double lz = 1.0 / qMax(0.2, depth * 0.4);
        const double lLen = sqrt(lx * lx + ly * ly + lz * lz);
        const double nlx = lx / lLen;
        const double nly = ly / lLen;
        const double nlz = lz / lLen;

        QImage tmp = img.copy();
        filterParallelFor(0, h, [&](int startY, int endY) {
            const int kx[3][3] = {{-1, 0, 1}, {-2, 0, 2}, {-1, 0, 1}};
            const int ky[3][3] = {{-1, -2, -1}, {0, 0, 0}, {1, 2, 1}};
            for (int y = startY; y < endY; ++y) {
                quint8 *dst = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    double gx = 0.0, gy = 0.0;
                    for (int dy = -1; dy <= 1; ++dy) {
                        int ny = qBound(0, y + dy, h - 1);
                        const quint8 *row = tmp.constScanLine(ny);
                        for (int dx = -1; dx <= 1; ++dx) {
                            int nx = qBound(0, x + dx, w - 1);
                            const quint8 *p = row + nx * 4;
                            double lum = (p[2] * 299 + p[1] * 587 + p[0] * 114) / 1000.0;
                            gx += lum * kx[dy + 1][dx + 1];
                            gy += lum * ky[dy + 1][dx + 1];
                        }
                    }
                    gx /= 8.0;
                    gy /= 8.0;

                    // Surface normal N = (-gx, -gy, 1.0)
                    double nx = -gx;
                    double ny = -gy;
                    double nz = 1.0;
                    double nLen = sqrt(nx * nx + ny * ny + nz * nz);
                    double dot = (nx * nlx + ny * nly + nz * nlz) / nLen;
                    double diffuse = qBound(0.0, (dot + 1.0) * 0.5, 1.0);

                    const quint8 *p0 = tmp.constScanLine(y) + x * 4;
                    quint8 *px = dst + x * 4;
                    if (p0[3] == 0) {
                        px[0] = 0; px[1] = 0; px[2] = 0; px[3] = 0;
                        continue;
                    }
                    if (preserveColor) {
                        double factor = 0.3 + diffuse * 0.9;
                        px[2] = quint8(qBound(0, int(p0[2] * factor), 255));
                        px[1] = quint8(qBound(0, int(p0[1] * factor), 255));
                        px[0] = quint8(qBound(0, int(p0[0] * factor), 255));
                    } else {
                        int grayVal = qBound(0, int(diffuse * 255.0), 255);
                        px[2] = quint8(grayVal);
                        px[1] = quint8(grayVal);
                        px[0] = quint8(grayVal);
                    }
                    px[3] = p0[3];
                }
            }
        });
        break;
    }
    case 10: { // Noise / 杂色
        const int amt = qBound(1, int(p1), 100);
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    int noise = (rand() % (amt * 2 + 1)) - amt;
                    px[2] = quint8(qBound(0, px[2] + noise, 255));
                    px[1] = quint8(qBound(0, px[1] + noise, 255));
                    px[0] = quint8(qBound(0, px[0] + noise, 255));
                }
            }
        });
        break;
    }
    case 11: { // Glitch / 色散错位
        const int offset = qBound(1, int(p1), 40);
        QImage tmp = img.copy();
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *dst = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    int rx = qBound(0, x + offset, w - 1);
                    int bx = qBound(0, x - offset, w - 1);
                    const quint8 *pr = tmp.constScanLine(y) + rx * 4;
                    const quint8 *pb = tmp.constScanLine(y) + bx * 4;
                    quint8 *px = dst + x * 4;
                    px[2] = pr[2]; // Red
                    px[0] = pb[0]; // Blue
                }
            }
        });
        break;
    }
    case 12: { // Desaturate / 去色 (灰度化): p1 = amount (0..100)
        const double amt = (p1 > 0.0) ? qBound(0.0, p1 / 100.0, 1.0) : 1.0;
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    int g = (px[2] * 299 + px[1] * 587 + px[0] * 114) / 1000;
                    px[2] = quint8(qBound(0, int(px[2] * (1.0 - amt) + g * amt), 255));
                    px[1] = quint8(qBound(0, int(px[1] * (1.0 - amt) + g * amt), 255));
                    px[0] = quint8(qBound(0, int(px[0] * (1.0 - amt) + g * amt), 255));
                }
            }
        });
        break;
    }
    case 13: { // Curves / 调色曲线: p1=shadow(0..255), p2=midtone(0..255), p3=highlight(0..255), p4=channel(0:RGB, 1:R, 2:G, 3:B)
        const double sVal = qBound(0.0, p1, 255.0);
        const double mVal = qBound(0.0, p2, 255.0);
        const double hVal = qBound(0.0, p3, 255.0);
        const int chan = int(p4);
        quint8 lut[256];
        for (int i = 0; i < 256; ++i) {
            double t = double(i) / 255.0;
            double v;
            if (t <= 0.25) {
                double u = t / 0.25;
                v = (1.0 - u) * 0.0 + u * sVal;
            } else if (t <= 0.5) {
                double u = (t - 0.25) / 0.25;
                v = (1.0 - u) * sVal + u * mVal;
            } else if (t <= 0.75) {
                double u = (t - 0.5) / 0.25;
                v = (1.0 - u) * mVal + u * hVal;
            } else {
                double u = (t - 0.75) / 0.25;
                v = (1.0 - u) * hVal + u * 255.0;
            }
            lut[i] = quint8(qBound(0.0, v, 255.0));
        }
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    if (chan == 0) {
                        px[2] = lut[px[2]];
                        px[1] = lut[px[1]];
                        px[0] = lut[px[0]];
                    } else if (chan == 1) {
                        px[2] = lut[px[2]]; // Red
                    } else if (chan == 2) {
                        px[1] = lut[px[1]]; // Green
                    } else if (chan == 3) {
                        px[0] = lut[px[0]]; // Blue
                    }
                }
            }
        });
        break;
    }
    case 14: { // Levels / 色阶: p1=inBlack(0..254), p2=inWhite(1..255), p3=gamma(0.1..5.0)
        const double inB = qBound(0.0, p1, 254.0);
        const double inW = qBound(inB + 1.0, p2, 255.0);
        const double gamma = qMax(0.05, p3);
        const double invGamma = 1.0 / gamma;
        quint8 lut[256];
        for (int i = 0; i < 256; ++i) {
            double v = qBound(0.0, double(i - inB) / (inW - inB), 1.0);
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
    case 15: { // Color Temperature & Tint / 色温与色调: p1=temp(-100..100), p2=tint(-100..100)
        const int temp = int(p1);
        const int tint = int(p2);
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    int r = px[2] + temp + tint / 2;
                    int g = px[1] - tint;
                    int b = px[0] - temp + tint / 2;
                    px[2] = quint8(qBound(0, r, 255));
                    px[1] = quint8(qBound(0, g, 255));
                    px[0] = quint8(qBound(0, b, 255));
                }
            }
        });
        break;
    }
    case 16: { // Threshold / 阈值: p1=threshold(1..255)
        const int thresh = qBound(1, int(p1), 255);
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    int lum = (px[2] * 299 + px[1] * 587 + px[0] * 114) / 1000;
                    quint8 val = (lum >= thresh) ? 255 : 0;
                    px[2] = val; px[1] = val; px[0] = val;
                }
            }
        });
        break;
    }
    case 17: { // Posterize / 色调分离: p1=levels(2..32)
        const int levels = qBound(2, int(p1), 32);
        const double step = 255.0 / double(levels - 1);
        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint8 *px = line + x * 4;
                    if (px[3] == 0) continue;
                    px[2] = quint8(qBound(0, int(std::round(px[2] / step) * step), 255));
                    px[1] = quint8(qBound(0, int(std::round(px[1] / step) * step), 255));
                    px[0] = quint8(qBound(0, int(std::round(px[0] / step) * step), 255));
                }
            }
        });
        break;
    }
    case 18: { // Bloom / 辉光: p1=threshold(0..255), p2=radius(1..60), p3=intensity(0.1..3.0)
        const int thresh = qBound(0, int(p1), 255);
        const int rad = qBound(1, int(p2), 60);
        const double intensity = qBound(0.1, p3, 3.0);
        QVector<quint32> glow(w * h, 0);
        QVector<quint32> tmp(w * h, 0);
        const quint32 *srcData = reinterpret_cast<const quint32 *>(img.constBits());

        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                for (int x = 0; x < w; ++x) {
                    quint32 c = srcData[y * w + x];
                    int a = (c >> 24) & 0xFF;
                    if (a == 0) continue;
                    int r = (c >> 16) & 0xFF;
                    int g = (c >> 8) & 0xFF;
                    int b = c & 0xFF;
                    int lum = (r * 299 + g * 587 + b * 114) / 1000;
                    if (lum >= thresh) {
                        double weight = (thresh < 255) ? double(lum - thresh) / double(255 - thresh) : 1.0;
                        int ga = qBound(1, int(a * weight), 255);
                        int gr = int(r * weight);
                        int gg = int(g * weight);
                        int gb = int(b * weight);
                        glow[y * w + x] = (quint32(ga) << 24) | (quint32(gr) << 16) | (quint32(gg) << 8) | quint32(gb);
                    }
                }
            }
        });

        int rBox = qMax(1, int(rad * 0.577));
        boxBlurH(glow.constData(), tmp.data(), w, h, rBox);
        boxBlurV(tmp.constData(), glow.data(), w, h, rBox);
        boxBlurH(glow.constData(), tmp.data(), w, h, rBox);
        boxBlurV(tmp.constData(), glow.data(), w, h, rBox);
        boxBlurH(glow.constData(), tmp.data(), w, h, rBox);
        boxBlurV(tmp.constData(), glow.data(), w, h, rBox);

        filterParallelFor(0, h, [&](int startY, int endY) {
            for (int y = startY; y < endY; ++y) {
                quint8 *dst = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    quint32 gPix = glow[y * w + x];
                    int ga = (gPix >> 24) & 0xFF;
                    quint8 *px = dst + x * 4;
                    if (ga > 0) {
                        int gr = (gPix >> 16) & 0xFF;
                        int gg = (gPix >> 8) & 0xFF;
                        int gb = gPix & 0xFF;
                        double gFactor = (double(ga) / 255.0) * intensity;
                        int addR = int(gr * gFactor);
                        int addG = int(gg * gFactor);
                        int addB = int(gb * gFactor);

                        // Screen blend for organic luminous highlights
                        px[2] = quint8(qBound(0, 255 - ((255 - px[2]) * qMax(0, 255 - addR)) / 255, 255));
                        px[1] = quint8(qBound(0, 255 - ((255 - px[1]) * qMax(0, 255 - addG)) / 255, 255));
                        px[0] = quint8(qBound(0, 255 - ((255 - px[0]) * qMax(0, 255 - addB)) / 255, 255));
                        int newA = qMax(int(px[3]), qMin(255, int(ga * intensity)));
                        px[3] = quint8(qBound(0, newA, 255));
                    }
                }
            }
        });
        break;
    }
    default:
        applyFilterFxCases(img, w, h, filterType, p1, p2, p3, p4);
    }

    dev->writeBytes(img.constBits(), 0, 0, w, h);
    dev->setDirty(QRect(0, 0, w, h));
    recompositeProjection();
    markDirty();
}

void ReverieCore::applyCurvesLUTPreview(int index, const quint8 *lutR, const quint8 *lutG, const quint8 *lutB)
{
    if (!isLayerEditable(index) || !lutR || !lutG || !lutB) return;
    if (!m_filterBackupDevice || m_filterBackupIndex != index) {
        beginFilterPreview(index);
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev || !m_filterBackupDevice) return;

    const int w = m_docWidth;
    const int h = m_docHeight;
    QImage img(w, h, QImage::Format_ARGB32_Premultiplied);
    m_filterBackupDevice->readBytes(img.bits(), 0, 0, w, h);

    filterParallelFor(0, h, [&](int startY, int endY) {
        for (int y = startY; y < endY; ++y) {
            quint8 *line = img.scanLine(y);
            for (int x = 0; x < w; ++x) {
                quint8 *px = line + x * 4;
                if (px[3] == 0) continue;
                px[2] = lutR[px[2]]; // Red
                px[1] = lutG[px[1]]; // Green
                px[0] = lutB[px[0]]; // Blue
            }
        }
    });

    dev->writeBytes(img.constBits(), 0, 0, w, h);
    dev->setDirty(QRect(0, 0, w, h));
    recompositeProjection();
    markDirty();
}

void ReverieCore::applyGradientMapPreview(int index, const quint32 *gradientLut256)
{
    if (!isLayerEditable(index) || !gradientLut256) return;
    if (!m_filterBackupDevice || m_filterBackupIndex != index) {
        beginFilterPreview(index);
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev || !m_filterBackupDevice) return;

    const int w = m_docWidth;
    const int h = m_docHeight;
    QImage img(w, h, QImage::Format_ARGB32_Premultiplied);
    m_filterBackupDevice->readBytes(img.bits(), 0, 0, w, h);

    filterParallelFor(0, h, [&](int startY, int endY) {
        for (int y = startY; y < endY; ++y) {
            quint8 *line = img.scanLine(y);
            for (int x = 0; x < w; ++x) {
                quint8 *px = line + x * 4;
                if (px[3] == 0) continue;
                int lum = (px[2] * 299 + px[1] * 587 + px[0] * 114) / 1000;
                quint32 gCol = gradientLut256[qBound(0, lum, 255)];
                int gr = (gCol >> 16) & 0xFF;
                int gg = (gCol >> 8) & 0xFF;
                int gb = gCol & 0xFF;
                int ga = (gCol >> 24) & 0xFF;
                px[2] = quint8(gr);
                px[1] = quint8(gg);
                px[0] = quint8(gb);
                px[3] = quint8((px[3] * ga) / 255);
            }
        }
    });

    dev->writeBytes(img.constBits(), 0, 0, w, h);
    dev->setDirty(QRect(0, 0, w, h));
    recompositeProjection();
    markDirty();
}

