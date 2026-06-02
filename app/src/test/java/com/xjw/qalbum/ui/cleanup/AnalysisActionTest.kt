package com.xjw.qalbum.ui.cleanup

import com.xjw.qalbum.data.db.dao.ImageDao
import com.xjw.qalbum.service.analysis.AnalysisState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 校验「分析图片」卡片主按钮三态判定与「已分析」计数。
 * 这两个纯函数是本次改动里唯一带分支的非 UI 逻辑。
 */
class AnalysisActionTest {

    private fun row(total: Int, hashDone: Int = 0, pendingBase: Int = total) =
        ImageDao.AnalysisProgressRow(
            totalImages = total,
            hashDone = hashDone,
            ocrDone = 0,
            aiDone = 0,
            embeddingDone = 0,
            pendingBase = pendingBase,
        )

    @Test
    fun noImagesDisablesButton() {
        assertEquals(
            AnalysisAction.NO_IMAGES,
            analysisAction(AnalysisState.Idle, row(total = 0, pendingBase = 0)),
        )
    }

    @Test
    fun neverAnalyzedShowsStart() {
        assertEquals(
            AnalysisAction.START,
            analysisAction(AnalysisState.Idle, row(total = 100, hashDone = 0, pendingBase = 100)),
        )
    }

    @Test
    fun partiallyAnalyzedShowsContinue() {
        assertEquals(
            AnalysisAction.CONTINUE,
            analysisAction(AnalysisState.Idle, row(total = 100, hashDone = 30, pendingBase = 70)),
        )
    }

    @Test
    fun doneButStillPendingShowsContinue() {
        // 上一批 Done，但全库仍有待基础分析的图 → 继续分析
        assertEquals(
            AnalysisAction.CONTINUE,
            analysisAction(AnalysisState.Done, row(total = 100, hashDone = 50, pendingBase = 50)),
        )
    }

    @Test
    fun allBaseDoneShowsAllDone() {
        assertEquals(
            AnalysisAction.ALL_DONE,
            analysisAction(AnalysisState.Idle, row(total = 100, hashDone = 100, pendingBase = 0)),
        )
    }

    @Test
    fun runningOverridesEverything() {
        assertEquals(
            AnalysisAction.CANCEL,
            analysisAction(AnalysisState.Running, row(total = 100, hashDone = 100, pendingBase = 0)),
        )
        assertEquals(
            AnalysisAction.CANCEL,
            analysisAction(AnalysisState.Running, row(total = 100, hashDone = 0, pendingBase = 100)),
        )
    }

    @Test
    fun analyzedCountIsTotalMinusPending() {
        assertEquals(70, analyzedCount(row(total = 100, pendingBase = 30)))
    }

    @Test
    fun analyzedCountNeverNegative() {
        assertEquals(0, analyzedCount(row(total = 10, pendingBase = 20)))
    }
}
