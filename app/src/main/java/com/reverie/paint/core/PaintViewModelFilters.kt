package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


    internal fun PaintViewModel.beginFilterPreview(index: Int) {
        isFilterAdjustActive = true
        runCore(render = false) {
            ReverieCoreBridge.beginFilterPreview(index)
        }
    }

    internal fun PaintViewModel.applyFilterPreview(index: Int, filterType: Int, p1: Double = 0.0, p2: Double = 0.0, p3: Double = 0.0, p4: Double = 0.0) {
        filterPreviewJob?.cancel()
        filterPreviewJob = viewModelScope.launch(Dispatchers.Default) {
            runCore(after = {
                scheduleRender(immediate = true)
            }) {
                ReverieCoreBridge.applyFilterPreview(index, filterType, p1, p2, p3, p4)
            }
        }
    }

    internal fun PaintViewModel.applyCurvesLUTPreview(index: Int, lutR: ByteArray, lutG: ByteArray, lutB: ByteArray) {
        filterPreviewJob?.cancel()
        filterPreviewJob = viewModelScope.launch(Dispatchers.Default) {
            runCore(after = {
                scheduleRender(immediate = true)
            }) {
                ReverieCoreBridge.applyCurvesLUTPreview(index, lutR, lutG, lutB)
            }
        }
    }

    internal fun PaintViewModel.applyGradientMapPreview(index: Int, gradientLut: IntArray) {
        filterPreviewJob?.cancel()
        filterPreviewJob = viewModelScope.launch(Dispatchers.Default) {
            runCore(after = {
                scheduleRender(immediate = true)
            }) {
                ReverieCoreBridge.applyGradientMapPreview(index, gradientLut)
            }
        }
    }

    internal fun PaintViewModel.commitFilter(index: Int, filterName: String) {
        isFilterAdjustActive = false
        filterPreviewJob?.cancel()
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.commitFilter(index, filterName)
        }
    }

    internal fun PaintViewModel.cancelFilter(index: Int) {
        isFilterAdjustActive = false
        filterPreviewJob?.cancel()
        runCore(after = {
            scheduleRender(immediate = true)
            notifyLayerChanged()
        }) {
            ReverieCoreBridge.cancelFilter(index)
        }
    }

    internal fun PaintViewModel.recompositeProjection() {
        runCore(after = ::notifyLayerChanged) {
            // runCore triggers rendering and syncs projection
        }
    }
