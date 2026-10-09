package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class DiaryPaneTest {
    @Test void connectionWithoutMessageHasActionableFeedback() {
        assertEquals("听歌记录服务未连接，请稍后刷新或重试连接",
                DiaryPane.errorMessage(new ConnectException()));
        assertEquals("听歌记录服务未连接，请稍后刷新或重试连接",
                DiaryPane.errorMessage(new CompletionException(new ConnectException())));
    }

    @Test void timeoutAndActualFailureAreDistinguished() {
        assertEquals("听歌记录服务响应超时，请重试",
                DiaryPane.errorMessage(new HttpTimeoutException("fixture timeout")));
        assertEquals("最近播放 HTTP 503：fixture unavailable",
                DiaryPane.errorMessage(new IllegalStateException("最近播放 HTTP 503：fixture unavailable")));
        assertEquals("IllegalStateException", DiaryPane.errorMessage(new IllegalStateException()));
    }
}
