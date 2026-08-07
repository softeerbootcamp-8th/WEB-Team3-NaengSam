package com.naengsam.quick.domain.dreami.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 업로드 확인 요청. presigned URL 발급 시 받은 신분증/범죄이력조회서 S3 key를 그대로 담아 보낸다.
 */
public record DreamiAuthRequest(
        @NotBlank
        String idCardKey,

        @NotBlank
        String criminalRecordKey
) {
}
