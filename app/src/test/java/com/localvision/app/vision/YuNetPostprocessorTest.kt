package com.localvision.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YuNetPostprocessorTest {
    @Test
    fun `decodes a face and five keypoints`() {
        val outputs = emptyOutputs(inputSize = 32)
        outputs.cls.getValue(8)[0] = 1f
        outputs.obj.getValue(8)[0] = 1f
        outputs.bbox.getValue(8).apply {
            this[0] = 1f
            this[1] = 1f
            this[2] = 0f
            this[3] = 0f
        }
        val result = YuNetPostprocessor.decode(outputs, inputSize = 32)
        assertEquals(1, result.size)
        assertEquals(5, result.single().keypoints.size)
        assertEquals(0.125f, result.single().bounds.left, 0.001f)
        assertEquals(0.375f, result.single().bounds.right, 0.001f)
    }

    @Test
    fun `suppresses overlapping lower score face`() {
        val high = Detection(NormalizedRect(0.1f, 0.1f, 0.6f, 0.6f), "face", 0.9f)
        val low = Detection(NormalizedRect(0.12f, 0.12f, 0.62f, 0.62f), "face", 0.8f)
        assertEquals(listOf(high), YuNetPostprocessor.nms(listOf(high, low), 0.3f))
        assertTrue(YuNetPostprocessor.iou(high.bounds, low.bounds) > 0.8f)
    }

    @Test
    fun `applies configured confidence threshold`() {
        val outputs = emptyOutputs(inputSize = 32)
        outputs.cls.getValue(8)[0] = 0.25f
        outputs.obj.getValue(8)[0] = 1f
        assertEquals(1, YuNetPostprocessor.decode(outputs, scoreThreshold = 0.4f, inputSize = 32).size)
        assertTrue(YuNetPostprocessor.decode(outputs, scoreThreshold = 0.6f, inputSize = 32).isEmpty())
    }

    @Test
    fun `removes centered letterbox padding when mapping coordinates`() {
        val outputs = emptyOutputs(inputSize = 32)
        outputs.cls.getValue(8)[0] = 1f
        outputs.obj.getValue(8)[0] = 1f
        outputs.bbox.getValue(8).apply {
            this[0] = 1f
            this[1] = 1f
        }
        val result = YuNetPostprocessor.decode(
            outputs = outputs,
            inputSize = 32,
            transform = LetterboxTransform(
                inputSize = 32,
                scaledWidth = 32,
                scaledHeight = 16,
                padX = 0,
                padY = 8,
            ),
        ).single()
        assertEquals(0.125f, result.bounds.left, 0.001f)
        assertEquals(0f, result.bounds.top, 0.001f)
        assertEquals(0.25f, result.bounds.bottom, 0.001f)
    }

    @Test(expected = AnalysisFailure.InvalidOutput::class)
    fun `rejects incompatible tensor length`() {
        val outputs = emptyOutputs(inputSize = 32)
        val invalid = outputs.copy(bbox = outputs.bbox.toMutableMap().apply { put(8, FloatArray(1)) })
        YuNetPostprocessor.decode(invalid, inputSize = 32)
    }

    private fun emptyOutputs(inputSize: Int): YuNetOutputs {
        val strides = listOf(8, 16, 32)
        fun arrays(fields: Int) = strides.associateWithTo(mutableMapOf()) { stride ->
            FloatArray((inputSize / stride) * (inputSize / stride) * fields)
        }
        return YuNetOutputs(arrays(1), arrays(1), arrays(4), arrays(10))
    }
}

