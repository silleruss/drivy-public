package io.github.silleruss.drivy

import org.junit.Assert.*
import org.junit.Test

class DriveReportTest {
    @Test fun countsOneEventPerContinuousManeuverAndExcludesGaps() {
        val report = DriveReportAccumulator()
        report.observeSpeed(0.0, 1000)
        report.observeSpeed(18.0, 2000)
        report.observeSpeed(36.0, 3000)
        report.observeSpeed(36.0, 4000)
        report.observeSpeed(18.0, 5000)
        report.observeSpeed(0.0, 6000)
        report.observeSpeed(null, 7000)
        report.observeSpeed(50.0, 10000)
        assertEquals(1, report.accelerations)
        assertEquals(1, report.decelerations)
        assertEquals(5000L, report.coveredMs)
        assertTrue(report.hasGap)
    }

    @Test fun invalidAndOutOfOrderSamplesDoNotCreateEvents() {
        val report = DriveReportAccumulator()
        report.observeSpeed(10.0, 1000)
        report.observeSpeed(200.0, 1000)
        report.observeSpeed(Double.NaN, 2000)
        report.observeSpeed(200.0, 3000)
        assertEquals(0, report.accelerations)
        assertEquals(0L, report.coveredMs)
        assertTrue(report.hasGap)
    }

    @Test fun failureAtSameTimestampBreaksContinuityAndStaleFailureCannotBreakNewerData() {
        val report = DriveReportAccumulator()
        report.observeSpeed(0.0, 1000)
        report.observeSpeed(null, 1000)
        report.observeSpeed(18.0, 2000)
        report.observeSpeed(null, 1500)
        report.observeSpeed(36.0, 3000)
        assertEquals(1000L, report.coveredMs)
        assertEquals(1, report.accelerations)
        assertTrue(report.hasGap)
    }
}
