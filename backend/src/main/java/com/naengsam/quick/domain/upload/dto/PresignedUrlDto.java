package com.naengsam.quick.domain.upload.dto;

/**
 * presigned URL 발급 응답. {@code url}로 클라이언트가 S3에 직접 PUT하고, {@code key}는 이후 업로드 확인/등록 요청에 그대로 사용한다.
 */
public record PresignedUrlDto(
        String url,
        String key
) {
}
