package com.xjw.qalbum.ui.cleanup

import com.xjw.qalbum.service.analysis.AnalysisState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 校验「分析图片」卡片主按钮三态判定 [analysisAction]。
 * [analyzed] = 完成全部「当前需要」阶段的图片数（与图库完成角标同口径），
 * 与「hash 完成数」解耦：只跑了 hash、但 OCR/AI/向量开着时，analyzed 接近 0 而按钮仍是「继续」。
 */
class AnalysisActionTest {

    @Test
    fun noImagesDisablesButton() {
        assertEquals(
            AnalysisAction.NO_IMAGES,
            analysisAction(AnalysisState.Idle, total = 0, analyzed = 0, hashedAny = false),
        )
    }

    @Test
    fun neverAnalyzedShowsStart() {
        assertEquals(
            AnalysisAction.START,
            analysisAction(AnalysisState.Idle, total = 100, analyzed = 0, hashedAny = false),
        )
    }

    @Test
    fun hashedButNothingFullyAnalyzedShowsContinue() {
        // 只跑了 hash、OCR/AI/向量开着：analyzed=0（没有图四项全完成）但 hashedAny=true → 继续分析
        assertEquals(
            AnalysisAction.CONTINUE,
            analysisAction(AnalysisState.Idle, total = 100, analyzed = 0, hashedAny = true),
        )
    }

    @Test
    fun partiallyAnalyzedShowsContinue() {
        assertEquals(
            AnalysisAction.CONTINUE,
            analysisAction(AnalysisState.Idle, total = 100, analyzed = 30, hashedAny = true),
        )
    }

    @Test
    fun allAnalyzedShowsAllDone() {
        assertEquals(
            AnalysisAction.ALL_DONE,
            analysisAction(AnalysisState.Idle, total = 100, analyzed = 100, hashedAny = true),
        )
    }

    @Test
    fun runningOverridesEverything() {
        assertEquals(
            AnalysisAction.CANCEL,
            analysisAction(AnalysisState.Running, total = 100, analyzed = 100, hashedAny = true),
        )
        assertEquals(
            AnalysisAction.CANCEL,
            analysisAction(AnalysisState.Running, total = 100, analyzed = 0, hashedAny = false),
        )
    }
}
