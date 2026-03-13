package us.shandian.giga.get;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DownloadMissionTest {

    @Test
    public void getRetryDelayMillisUsesLinearBackoffWithCap() {
        assertEquals(0L, DownloadMission.getRetryDelayMillis(0));
        assertEquals(1_000L, DownloadMission.getRetryDelayMillis(1));
        assertEquals(3_000L, DownloadMission.getRetryDelayMillis(3));
        assertEquals(8_000L, DownloadMission.getRetryDelayMillis(20));
    }
}
