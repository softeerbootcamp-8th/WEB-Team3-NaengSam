package com.naengsam.quick.domain.upload.controller;

import com.naengsam.quick.domain.upload.dto.PresignedUrlDto;
import com.naengsam.quick.domain.upload.entity.UploadPurpose;
import com.naengsam.quick.domain.upload.exception.UploadErrorCode;
import com.naengsam.quick.domain.upload.service.UploadSessionService;
import com.naengsam.quick.domain.user.exception.AuthErrorCode;
import com.naengsam.quick.global.exception.BusinessException;
import com.naengsam.quick.global.session.LoginUser;
import com.naengsam.quick.global.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 업로드 컨트롤러. presigned URL 발급과, 클라이언트가 그 URL로 S3에 실제로 파일이 업로드됐는지 확인한다.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/upload")
@Tag(name = "업로드 컨트롤러", description = "S3 presigned URL을 발급하고, 실제로 파일이 업로드됐는지 확인한다.")
public class UploadController {

    private final UploadSessionService uploadSessionService;

    @Operation(summary = "업로드용 presigned URL 발급",
            description = "이 fileName/purpose로 S3에 직접 PUT 할 수 있는 presigned URL과, 그 파일의 S3 key를 발급한다.")
    @GetMapping("/url")
    @ApiResponse(responseCode = "200", description = "요청에 성공했습니다.")
    @ApiErrorCodes(enumClass = AuthErrorCode.class, codes = {"UNAUTHORIZED"})
    @ApiErrorCodes(enumClass = UploadErrorCode.class,
            codes = {"NO_FILE_ATTACHED", "INVALID_FILE_NAME", "UNSUPPORTED_FILE_TYPE"})
    public PresignedUrlDto getPresignedUrl(@RequestParam String fileName, @RequestParam UploadPurpose purpose,
            @RequestParam(required = false) UUID resourceId, @LoginUser UUID boormiId) {
        validateFileName(fileName);

        return uploadSessionService.issue(purpose, boormiId, resourceId, fileName);
    }

    /**
     * fileName이 비어있으면 첨부 자체가 없는 것으로 보고 거부한다. 경로 구분자/상위 디렉토리 참조가 섞여있으면
     * S3 key에 그대로 이어붙이므로 의도하지 않은 key 경로가 만들어질 수 있어 별도로 거부한다.
     */
    private void validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(UploadErrorCode.NO_FILE_ATTACHED);
        }
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new BusinessException(UploadErrorCode.INVALID_FILE_NAME);
        }
    }
}
