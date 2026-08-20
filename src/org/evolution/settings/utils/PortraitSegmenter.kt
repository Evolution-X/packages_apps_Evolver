/*
 * Copyright (C) 2025-2026 AxionOS
 * Copyright (C) 2024-2026 Lunaris OS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.evolution.settings.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.TensorProcessor
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.TensorOperatorWrapper
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

private const val TAG = "PortraitSegmenter"

private const val RAID_MODEL = "mobile_bg_removal_mosaic_dm1_w_metadata.f16.tflite"
private const val DEEP_MATTING_MODEL = "deep_matting.tflite"
private const val FG_ESTIMATION_MODEL = "foreground_estimator_5680_512_512.tflite"
private const val MODELS_DIR = "segmentation_models_v2"

class PortraitSegmenter(private val context: Context) {

    private var raidModelFile: File? = null
    private var mattingModelFile: File? = null
    private var fgEstModelFile: File? = null
    private var hasFullPipeline = false
    private val interpreterOptions = Interpreter.Options().apply { setNumThreads(4) }

    fun init() {
        try {
            raidModelFile = cacheModel(RAID_MODEL)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache RAID model", e)
            return
        }
        try {
            mattingModelFile = cacheModel(DEEP_MATTING_MODEL)
        } catch (e: Exception) {
            Log.w(TAG, "Deep matting model not available", e)
        }
        try {
            fgEstModelFile = cacheModel(FG_ESTIMATION_MODEL)
        } catch (e: Exception) {
            Log.w(TAG, "FG estimation model not available", e)
        }
        hasFullPipeline = mattingModelFile != null && fgEstModelFile != null
        Log.d(TAG, "Pipeline: ${if (hasFullPipeline) "FULL 3-stage" else "RAID-only fallback"}")
    }

    fun isReady(): Boolean = raidModelFile != null

    private fun cacheModel(fileName: String): File {
        val deContext = context.createDeviceProtectedStorageContext()
        val modelsDir = File(deContext.cacheDir.absolutePath, MODELS_DIR)
        modelsDir.mkdirs()
        val cachedModel = File(modelsDir, fileName)
        if (!cachedModel.exists() || cachedModel.length() == 0L) {
            cachedModel.delete()
            context.assets.open(fileName).use { input ->
                FileOutputStream(cachedModel).use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "Cached model $fileName: ${cachedModel.length()} bytes")
        }
        return cachedModel
    }

    fun segment(bitmap: Bitmap): Bitmap? {
        val raidFile = raidModelFile ?: return null
        return if (hasFullPipeline) runFullPipeline(raidFile, bitmap) else fallbackSegment(raidFile, bitmap)
    }

    private fun runFullPipeline(raidFile: File, bitmap: Bitmap): Bitmap? {
        return try {
            val startTime = System.currentTimeMillis()

            val raidOutputBuffers = HashMap<Int, TensorBuffer>()
            val raidInputImage: TensorImage
            val coarseMask: Bitmap
            Interpreter(raidFile, interpreterOptions).use { raid ->
                raidInputImage = convertRaidInput(raid, bitmap)
                runModel(raid, arrayOf(raidInputImage.tensorBuffer.buffer), raidOutputBuffers)
                coarseMask = convertRaidOutput(raidOutputBuffers, raid)
            }
            Log.d(TAG, "Stage 1 RAID: ${coarseMask.width}x${coarseMask.height} (${System.currentTimeMillis() - startTime}ms)")

            val t2 = System.currentTimeMillis()
            val mattingOutputBuffers = HashMap<Int, TensorBuffer>()
            val confidenceBitmap: Bitmap
            val refinedAlpha: Bitmap
            val mattingOutputShape: IntArray
            Interpreter(mattingModelFile!!, interpreterOptions).use { matting ->
                val mattingInputImage = convertMattingImageInput(matting, bitmap)
                val mattingMaskBuffer = convertMaskToInputBuffer(matting, coarseMask)
                runModel(matting, arrayOf(mattingInputImage.tensorBuffer.buffer, mattingMaskBuffer), mattingOutputBuffers)
                mattingOutputShape = matting.getOutputTensor(0).shape()
            }
            val mattingResult = convertMattingOutput(mattingOutputBuffers, mattingOutputShape)
            confidenceBitmap = mattingResult.first
            refinedAlpha = mattingResult.second
            coarseMask.recycle()
            Log.d(TAG, "Stage 2 DeepMatting: ${refinedAlpha.width}x${refinedAlpha.height} (${System.currentTimeMillis() - t2}ms)")

            val t3 = System.currentTimeMillis()
            val fgEstOutputBuffers = HashMap<Int, TensorBuffer>()
            val fgEstMaskTensor: TensorBuffer
            Interpreter(fgEstModelFile!!, interpreterOptions).use { fgEst ->
                val fgEstInputImage = normalizeFgEstInput(raidInputImage)
                fgEstMaskTensor = convertMaskToInputTensor(fgEst, refinedAlpha)
                runModel(fgEst, arrayOf(fgEstInputImage.tensorBuffer.buffer, fgEstMaskTensor.buffer), fgEstOutputBuffers)
            }
            Log.d(TAG, "Stage 3 FGEstimation: complete (${System.currentTimeMillis() - t3}ms)")

            val fgOutputTensor = fgEstOutputBuffers[0]!!
            val maskedFGColors = getMaskedFGColors(fgOutputTensor, fgEstMaskTensor)
            refinedAlpha.recycle()

            val scaledFGColors = Bitmap.createScaledBitmap(maskedFGColors, bitmap.width, bitmap.height, true)
            if (scaledFGColors !== maskedFGColors) maskedFGColors.recycle()

            val confidentFG = getConfidentOriginalPixels(bitmap, confidenceBitmap)
            confidenceBitmap.recycle()

            val canvas = Canvas(scaledFGColors)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
            }
            canvas.drawBitmap(confidentFG, 0f, 0f, paint)
            confidentFG.recycle()

            Log.d(TAG, "Full pipeline complete: ${scaledFGColors.width}x${scaledFGColors.height} (total ${System.currentTimeMillis() - startTime}ms)")
            scaledFGColors
        } catch (e: Exception) {
            Log.e(TAG, "Full pipeline failed, falling back to RAID-only", e)
            fallbackSegment(raidFile, bitmap)
        }
    }

    private fun runModel(
        interp: Interpreter,
        inputBuffers: Array<ByteBuffer>,
        outputBuffers: HashMap<Int, TensorBuffer>,
    ) {
        outputBuffers.clear()
        val outputs = HashMap<Int, Any>()
        for (i in 0 until interp.outputTensorCount) {
            val buf = TensorBuffer.createFixedSize(interp.getOutputTensor(i).shape(), DataType.FLOAT32)
            outputBuffers[i] = buf
            outputs[i] = buf.buffer
        }
        interp.runForMultipleInputsOutputs(inputBuffers, outputs)
    }

    private fun convertRaidInput(interp: Interpreter, bitmap: Bitmap): TensorImage {
        val shape = interp.getInputTensor(0).shape()
        val processor = ImageProcessor.Builder()
            .add(ResizeOp(shape[1], shape[2], ResizeOp.ResizeMethod.BILINEAR))
            .add(TensorOperatorWrapper(CastOp(DataType.FLOAT32)))
            .build()
        val tensorImage = TensorImage(DataType.UINT8)
        tensorImage.load(bitmap)
        return processor.process(tensorImage)
    }

    private fun convertRaidOutput(outputBuffers: HashMap<Int, TensorBuffer>, interp: Interpreter): Bitmap {
        val shape = interp.getOutputTensor(1).shape()
        val tensorBuffer = outputBuffers[1]!!
        val processor = TensorProcessor.Builder()
            .add(NormalizeOp(0f, 0.003921569f))
            .add(CastOp(DataType.UINT8))
            .build()
        val processed = processor.process(tensorBuffer)
        val w = shape[1]; val h = shape[2]
        processed.buffer.rewind()
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        mask.copyPixelsFromBuffer(processed.buffer)
        return mask
    }

    private fun convertMattingImageInput(interp: Interpreter, bitmap: Bitmap): TensorImage {
        val shape = interp.getInputTensor(0).shape()
        val processor = ImageProcessor.Builder()
            .add(ResizeOp(shape[1], shape[2], ResizeOp.ResizeMethod.BILINEAR))
            .add(TensorOperatorWrapper(CastOp(DataType.FLOAT32)))
            .add(TensorOperatorWrapper(NormalizeOp(floatArrayOf(0f, 0f, 0f), floatArrayOf(255f, 255f, 255f))))
            .build()
        val tensorImage = TensorImage(DataType.UINT8)
        tensorImage.load(bitmap)
        return processor.process(tensorImage)
    }

    private fun convertMaskToInputBuffer(interp: Interpreter, mask: Bitmap): ByteBuffer {
        val shape = interp.getInputTensor(1).shape()
        val scaled = Bitmap.createScaledBitmap(mask, shape[1], shape[2], true)
        val tensorBuffer = TensorBuffer.createFixedSize(shape, DataType.UINT8)
        tensorBuffer.buffer.rewind()
        scaled.copyPixelsToBuffer(tensorBuffer.buffer)
        if (scaled !== mask) scaled.recycle()
        val processor = TensorProcessor.Builder()
            .add(CastOp(DataType.FLOAT32))
            .add(NormalizeOp(0f, 255f))
            .build()
        return processor.process(tensorBuffer).buffer
    }

    private fun convertMattingOutput(
        outputBuffers: HashMap<Int, TensorBuffer>,
        shape: IntArray,
    ): Pair<Bitmap, Bitmap> {
        val w = shape[1]; val h = shape[2]
        val buffer = outputBuffers[0]!!.buffer
        buffer.rewind()
        val totalFloats = shape.reduce { acc, v -> acc * v }
        val confidenceBuffer = ByteBuffer.allocateDirect(4 * totalFloats).order(ByteOrder.nativeOrder())
        val alphaBuffer = ByteBuffer.allocateDirect(totalFloats).order(ByteOrder.nativeOrder())
        while (buffer.hasRemaining()) {
            val f = buffer.float
            confidenceBuffer.putInt(Color.argb(if (f > 0.99f) 1f else 0f, 0f, 0f, 0f))
            alphaBuffer.put((f * 255f).roundToInt().toByte())
        }
        confidenceBuffer.rewind()
        val confidenceBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        confidenceBitmap.copyPixelsFromBuffer(confidenceBuffer)
        alphaBuffer.rewind()
        val refinedAlpha = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        refinedAlpha.copyPixelsFromBuffer(alphaBuffer)
        return Pair(confidenceBitmap, refinedAlpha)
    }

    private fun normalizeFgEstInput(raidInput: TensorImage): TensorImage {
        val processor = ImageProcessor.Builder()
            .add(TensorOperatorWrapper(NormalizeOp(floatArrayOf(0f, 0f, 0f), floatArrayOf(255f, 255f, 255f))))
            .build()
        return processor.process(raidInput)
    }

    private fun convertMaskToInputTensor(interp: Interpreter, mask: Bitmap): TensorBuffer {
        val shape = interp.getInputTensor(1).shape()
        val scaled = Bitmap.createScaledBitmap(mask, shape[1], shape[2], true)
        val tensorBuffer = TensorBuffer.createFixedSize(shape, DataType.UINT8)
        tensorBuffer.buffer.rewind()
        scaled.copyPixelsToBuffer(tensorBuffer.buffer)
        if (scaled !== mask) scaled.recycle()
        val processor = TensorProcessor.Builder()
            .add(CastOp(DataType.FLOAT32))
            .add(NormalizeOp(0f, 255f))
            .build()
        return processor.process(tensorBuffer)
    }

    private fun getMaskedFGColors(fgTensor: TensorBuffer, maskTensor: TensorBuffer): Bitmap {
        val fgBuffer = fgTensor.buffer
        val maskBuffer = maskTensor.buffer
        val w = fgTensor.shape[1]; val h = fgTensor.shape[2]
        fgBuffer.rewind(); maskBuffer.rewind()
        val pixels = IntArray(w * h)
        var i = 0
        while (fgBuffer.hasRemaining()) {
            pixels[i++] = Color.argb(maskBuffer.float, fgBuffer.float, fgBuffer.float, fgBuffer.float)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun getConfidentOriginalPixels(original: Bitmap, confidenceBitmap: Bitmap): Bitmap {
        val scaled = Bitmap.createScaledBitmap(confidenceBitmap, original.width, original.height, true)
        val canvas = Canvas(scaled)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(original, 0f, 0f, paint)
        return scaled
    }

    private fun fallbackSegment(raidFile: File, bitmap: Bitmap): Bitmap? {
        return try {
            val raidOutputBuffers = HashMap<Int, TensorBuffer>()
            val maskIdx: Int
            val shape: IntArray
            Interpreter(raidFile, interpreterOptions).use { interp ->
                val inputImage = convertRaidInput(interp, bitmap)
                runModel(interp, arrayOf(inputImage.tensorBuffer.buffer), raidOutputBuffers)
                maskIdx = 1
                shape = interp.getOutputTensor(maskIdx).shape()
            }
            val maskW = shape[1]; val maskH = shape[2]
            val buf = raidOutputBuffers[maskIdx]!!.buffer
            buf.rewind()
            val maskValues = FloatArray(maskW * maskH)
            for (p in maskValues.indices) {
                val raw = buf.float
                maskValues[p] = when {
                    raw < 0.05f -> 0f
                    raw > 0.95f -> 1f
                    else -> raw
                }
            }
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, maskW, maskH, true)
            val srcPixels = IntArray(maskW * maskH)
            scaledBitmap.getPixels(srcPixels, 0, maskW, 0, 0, maskW, maskH)
            scaledBitmap.recycle()
            val resultPixels = IntArray(maskW * maskH)
            for (i in srcPixels.indices) {
                val alpha = (maskValues[i].coerceIn(0f, 1f) * 255f).roundToInt()
                resultPixels[i] = Color.argb(alpha, Color.red(srcPixels[i]), Color.green(srcPixels[i]), Color.blue(srcPixels[i]))
            }
            val result = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
            result.setPixels(resultPixels, 0, maskW, 0, 0, maskW, maskH)
            cleanupForeground(result)
            val scaled = Bitmap.createScaledBitmap(result, bitmap.width, bitmap.height, true)
            if (scaled !== result) result.recycle()
            Log.d(TAG, "Fallback segmentation complete")
            scaled
        } catch (e: Exception) {
            Log.e(TAG, "Fallback segmentation failed", e)
            null
        }
    }

    private fun cleanupForeground(bitmap: Bitmap): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val alpha = IntArray(w * h)
        for (i in pixels.indices) {
            val a = Color.alpha(pixels[i])
            alpha[i] = if (a < 25) 0 else a
        }
        val eroded = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var minA = alpha[y * w + x]
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx; val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h) minA = min(minA, alpha[ny * w + nx])
                    }
                }
                eroded[y * w + x] = minA
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val a = eroded[idx]
                if (a == 0) {
                    pixels[idx] = 0
                } else if (a < 220) {
                    var sr = 0; var sg = 0; var sb = 0; var cnt = 0
                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            val nx = x + dx; val ny = y + dy
                            if (nx in 0 until w && ny in 0 until h) {
                                val nIdx = ny * w + nx
                                if (eroded[nIdx] >= 220) {
                                    sr += Color.red(pixels[nIdx]); sg += Color.green(pixels[nIdx])
                                    sb += Color.blue(pixels[nIdx]); cnt++
                                }
                            }
                        }
                    }
                    pixels[idx] = if (cnt > 0) Color.argb(a, sr / cnt, sg / cnt, sb / cnt)
                                  else Color.argb(a, Color.red(pixels[idx]), Color.green(pixels[idx]), Color.blue(pixels[idx]))
                } else {
                    pixels[idx] = Color.argb(a, Color.red(pixels[idx]), Color.green(pixels[idx]), Color.blue(pixels[idx]))
                }
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    fun release() {
        raidModelFile = null
        mattingModelFile = null
        fgEstModelFile = null
        hasFullPipeline = false
    }
}
