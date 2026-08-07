package com.naengsam.quick.domain.upload.service;

import com.naengsam.quick.domain.upload.dto.PresignedUrlDto;
import com.naengsam.quick.domain.upload.entity.UploadPurpose;
import com.naengsam.quick.domain.upload.entity.UploadSession;
import com.naengsam.quick.domain.upload.exception.UploadErrorCode;
import com.naengsam.quick.domain.upload.repository.UploadSessionRepository;
import com.naengsam.quick.global.exception.BusinessException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * presigned URL 발급을 "업로드 세션"으로 추적한다. 용도(purpose)/소유자(boormiId)/대상(resourceId)은 key 문자열이 아니라 세션 row에 저장해두고, 검증 시점에 그
 * 컬럼값끼리 비교한다 — 그래서 다른 용도·다른 사람·다른 건에 발급된 key를 그대로 제출해도 통과하지 못한다. 소비(consume) 여부도 세션 상태로 관리해 재시도로 인한 중복 처리를 방지한다.
 */
@Service
@RequiredArgsConstructor
public class UploadSessionService {

    private final S3PresignService s3PresignService;
    private final UploadSessionRepository uploadSessionRepository;

    /**
     * key의 스코프를 검증하고, S3에 실제 업로드됐는지 확인한 뒤 소비 처리한다. 클라이언트는 업로드가 끝났다고 확신한 뒤에만 이 메서드를 부르므로,
     * 업로드가 안 된 상태는 정상적인 중간 상태가 아니라 예외로 처리한다.
     *
     * @return 이번 호출로 처음 소비됐는지(재시도로 이미 소비된 요청이면 false)
     */
    @Transactional
    public boolean checkUpload(UploadPurpose uploadPurpose, UUID boormiId, String key) {
        // 다른 사람에게 발급됐거나 다른 용도로 발급된 key를 그대로 제출하는 것을 막는다.
        validateScope(uploadPurpose, boormiId, null, key);

        // 업로드가 완료되지 않은 상태라면
        if (!s3PresignService.isFileUploaded(key)) {
            throw new BusinessException(UploadErrorCode.FILE_NOT_FOUND);
        }

        // 세션을 소비 처리한다. 재시도로 이미 소비된 요청이면 저장을 반복하지 않는다.
        return consume(key);
    }

    @Transactional
    public PresignedUrlDto issue(UploadPurpose purpose, UUID boormiId, UUID resourceId, String fileName) {
        if (purpose.isResourceScopeRequired() && resourceId == null) {
            throw new BusinessException(UploadErrorCode.MISSING_RESOURCE_ID);
        }

        String key = buildKey(purpose, fileName);
        String url = s3PresignService.generateUploadUrl(key);
        uploadSessionRepository.save(UploadSession.create(purpose, boormiId, resourceId, key));
        return new PresignedUrlDto(url, key);
    }

    /**
     * key가 이 purpose/boormiId/resourceId 조합으로 발급된 것인지 확인한다. 아니면(다른 용도·다른 사람·다른 건에 발급된 key를 그대로 제출한 경우) 예외를 던진다.
     */
    @Transactional(readOnly = true)
    public void validateScope(UploadPurpose purpose, UUID boormiId, UUID resourceId, String key) {
        if (!findByKey(key).matches(purpose, boormiId, resourceId)) {
            throw new BusinessException(UploadErrorCode.KEY_OWNER_MISMATCH);
        }
    }

    /**
     * 세션을 소비 처리한다. 조건부 UPDATE(ISSUED일 때만 CONSUMED로) 하나로 전이시키므로, 동시에 같은 key로 호출돼도 단 하나의 호출만 true를 받는다.
     *
     * @return 이번 호출로 새로 소비됐으면 true, 이미 소비된 상태(재시도)라 아무것도 하지 않았으면 false
     */
    @Transactional
    public boolean consume(String key) {
        if (uploadSessionRepository.markConsumedIfIssued(key) == 1) {
            return true;
        }
        findByKey(key); // 세션 자체가 없으면 FILE_NOT_FOUND, 있으면(이미 CONSUMED) 그냥 통과
        return false;
    }

    private UploadSession findByKey(String key) {
        return uploadSessionRepository
                .findByS3Key(key)
                .orElseThrow(() -> new BusinessException(UploadErrorCode.FILE_NOT_FOUND));
    }

    private String buildKey(UploadPurpose purpose, String fileName) {
        return "uploads/" + purpose.name() + "/" + UUID.randomUUID() + "-" + fileName;
    }
}
