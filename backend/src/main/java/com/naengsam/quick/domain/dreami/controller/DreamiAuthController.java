package com.naengsam.quick.domain.dreami.controller;

import com.naengsam.quick.domain.dreami.dto.DreamiAuthRequest;
import com.naengsam.quick.domain.dreami.exception.DreamiErrorCode;
import com.naengsam.quick.domain.dreami.service.DreamiService;
import com.naengsam.quick.domain.upload.entity.UploadPurpose;
import com.naengsam.quick.domain.upload.exception.UploadErrorCode;
import com.naengsam.quick.domain.upload.service.UploadSessionService;
import com.naengsam.quick.domain.user.exception.AuthErrorCode;
import com.naengsam.quick.global.session.LoginUser;
import com.naengsam.quick.global.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 드리미 인증 컨트롤러. 신분증/범죄이력조회서가 S3에 모두 업로드됐는지 확인하고, 확인되면 인증 신청을 저장한다.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/dreami")
@Tag(name = "드리미 인증 컨트롤러", description = "신분증/범죄이력조회서 업로드를 확인하고 드리미 인증 신청을 저장한다.")
public class DreamiAuthController {

    private final UploadSessionService uploadSessionService;
    private final DreamiService dreamiService;

    @Operation(summary = "드리미가 본인 인증 자료 제대로 제출했는지 확인",
            description = "presigned URL로 업로드한 신분증/범죄이력조회서 파일이 S3에 실제로 존재하는지 확인한다.")
    @PostMapping("/verification")
    @ApiResponse(responseCode = "200", description = "요청에 성공했습니다.")
    @ApiErrorCodes(enumClass = AuthErrorCode.class, codes = {"UNAUTHORIZED"})
    @ApiErrorCodes(enumClass = DreamiErrorCode.class, codes = {"ALREADY_APPROVED"})
    @ApiErrorCodes(enumClass = UploadErrorCode.class, codes = {"FILE_NOT_FOUND", "KEY_OWNER_MISMATCH",
            "STORAGE_UPLOAD_FAILED"})
    public void verifyUploadedDocuments(@Valid @RequestBody DreamiAuthRequest requestDto,
                                        @LoginUser UUID boormiId) {
        // 승인 완료됐을 때는 해당 api 호출해서 값 덮어쓰지 못하게 해야함
        // reviewing, requested, rejected일 때는 다시 제출 허용
        dreamiService.assertNotAlreadyApproved(boormiId);

        // 클라이언트는 S3 업로드가 끝난 뒤에만 이걸 부르므로, 업로드가 안 돼있으면 FILE_NOT_FOUND 예외로 끝난다.
        boolean idCardNewlyConsumed = uploadSessionService.checkUpload(UploadPurpose.DREAMI_ID_CARD, boormiId,
                requestDto.idCardKey());
        boolean criminalRecordNewlyConsumed = uploadSessionService.checkUpload(
                UploadPurpose.DREAMI_CRIMINAL_RECORD, boormiId, requestDto.criminalRecordKey());

        // 재시도로 둘 다 이미 소비된 요청이면 저장을 반복하지 않는다.
        // 하나만 거짓인 경우 예시: 범죄 이력 조회서는 통과되지 않아서 이 파일만 다시 제출해야 할 때 신분증 사진은 newlyConsumed = false
        if (idCardNewlyConsumed || criminalRecordNewlyConsumed) {
            dreamiService.saveVerificationFileKeys(boormiId, requestDto.idCardKey(), requestDto.criminalRecordKey());
        }
    }
}
