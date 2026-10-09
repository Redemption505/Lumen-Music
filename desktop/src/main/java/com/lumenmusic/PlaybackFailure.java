package com.lumenmusic;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

/** Distinguishes confirmed invalid resources from temporary transport failures. */
public record PlaybackFailure(String code, String displayMessage, boolean retryable, boolean permanent) {
    public static PlaybackFailure from(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof MusicClient.StreamException)) cause = cause.getCause();
        if (cause instanceof MusicClient.StreamException failure) return forCode(failure.code());
        if (cause instanceof HttpTimeoutException) return forCode("UPSTREAM_TIMEOUT");
        if (cause instanceof java.io.FileNotFoundException || cause instanceof java.nio.file.NoSuchFileException)
            return forCode("LOCAL_FILE_MISSING");
        if (cause instanceof IOException) return forCode("UPSTREAM_UNAVAILABLE");
        String detail = cause.getMessage();
        return new PlaybackFailure("PLAYBACK_ERROR", "无法播放：" + (detail == null ? "播放器未能打开音频" : detail), false, false);
    }

    public static PlaybackFailure forCode(String code) {
        String message = switch (code) {
            case "VIDEO_NOT_FOUND", "VIDEO_DELETED" -> "视频不存在或已删除";
            case "VIDEO_ACCESS_DENIED" -> "该视频限制了访问权限";
            case "TRACK_PAGE_NOT_FOUND" -> "该分P不存在或已删除";
            case "AUDIO_UNAVAILABLE" -> "该视频没有可用音频轨";
            case "AUDIO_FORMAT_UNSUPPORTED" -> "该视频没有兼容的音频轨";
            case "LOCAL_FILE_MISSING", "LOCAL_FILE_NOT_FOUND" -> "本地音频文件已移动或删除";
            case "UPSTREAM_TIMEOUT" -> "连接音频源超时，重试后仍未恢复";
            case "UPSTREAM_CONNECTION_LOST" -> "音频连接被中断，请稍后重试";
            case "UPSTREAM_TLS_ERROR" -> "音频连接的证书校验失败";
            case "UPSTREAM_UNAVAILABLE", "AUDIO_SOURCE_ERROR", "HTTP_502", "HTTP_503", "HTTP_504" -> "音频源暂时连不上，请稍后重试";
            case "UPSTREAM_REJECTED" -> "B站暂时限制了音频请求，请稍后重试";
            case "AUDIO_SOURCE_EXPIRED" -> "音频地址失效，重新获取后仍不可用";
            default -> "音频暂不可用（" + code + "）";
        };
        boolean permanent = java.util.Set.of("VIDEO_NOT_FOUND", "VIDEO_DELETED", "TRACK_PAGE_NOT_FOUND",
                "AUDIO_UNAVAILABLE", "AUDIO_FORMAT_UNSUPPORTED", "LOCAL_FILE_MISSING", "LOCAL_FILE_NOT_FOUND").contains(code);
        boolean retryable = java.util.Set.of("UPSTREAM_TIMEOUT", "UPSTREAM_UNAVAILABLE", "AUDIO_SOURCE_ERROR",
                "UPSTREAM_CONNECTION_LOST", "AUDIO_SOURCE_EXPIRED", "HTTP_502", "HTTP_503", "HTTP_504").contains(code);
        return new PlaybackFailure(code, message, retryable, permanent);
    }
}
