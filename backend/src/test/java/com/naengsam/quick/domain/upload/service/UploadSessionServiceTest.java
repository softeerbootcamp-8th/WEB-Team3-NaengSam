package com.naengsam.quick.domain.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.naengsam.quick.domain.upload.dto.PresignedUrlDto;
import com.naengsam.quick.domain.upload.entity.UploadPurpose;
import com.naengsam.quick.domain.upload.entity.UploadSession;
import com.naengsam.quick.domain.upload.exception.UploadErrorCode;
import com.naengsam.quick.domain.upload.repository.UploadSessionRepository;
import com.naengsam.quick.global.code.BaseErrorCode;
import com.naengsam.quick.global.exception.BusinessException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 업로드 세션 발급/스코프 검증/소비(재시도 멱등성 포함)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class UploadSessionServiceTest {

    @Mock
    private S3PresignService s3PresignService;

    @Mock
    private UploadSessionRepository uploadSessionRepository;

    @InjectMocks
    private UploadSessionService uploadSessionService;

    private static BaseErrorCode errorCodeOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).getErrorCode();
    }

    // ---------- issue ----------

    @Test
    void 발급하면_purpose가_새겨진_key로_세션을_저장한다() {
        UUID boormiId = UUID.randomUUID();
        given(s3PresignService.generateUploadUrl(any())).willReturn("https://example.com/upload");

        PresignedUrlDto result = uploadSessionService.issue(UploadPurpose.DREAMI_ID_CARD, boormiId, null,
                "idcard.png");

        assertThat(result.key()).startsWith("uploads/DREAMI_ID_CARD/");
        assertThat(result.key()).endsWith("-idcard.png");
        assertThat(result.url()).isEqualTo("https://example.com/upload");

        ArgumentCaptor<UploadSession> captor = ArgumentCaptor.forClass(UploadSession.class);
        verify(uploadSessionRepository).save(captor.capture());
        assertThat(captor.getValue().getPurpose()).isEqualTo(UploadPurpose.DREAMI_ID_CARD);
        assertThat(captor.getValue().getBoormiId()).isEqualTo(boormiId);
    }

    // ---------- validateScope ----------

    @Test
    void 발급된_purpose_boormiId_resourceId가_모두_일치하면_예외없이_통과한다() {
        UUID boormiId = UUID.randomUUID();
        UploadSession session = UploadSession.create(UploadPurpose.DREAMI_ID_CARD, boormiId, null, "uploads/x/y-a.png");
        given(uploadSessionRepository.findByS3Key("uploads/x/y-a.png")).willReturn(Optional.of(session));

        assertThatCode(() -> uploadSessionService.validateScope(UploadPurpose.DREAMI_ID_CARD, boormiId, null,
                "uploads/x/y-a.png")).doesNotThrowAnyException();
    }

    @Test
    void 다른_용도로_발급된_key면_KEY_OWNER_MISMATCH_예외() {
        UUID boormiId = UUID.randomUUID();
        UploadSession session = UploadSession.create(UploadPurpose.DREAMI_ID_CARD, boormiId, null, "uploads/x/y-a.png");
        given(uploadSessionRepository.findByS3Key("uploads/x/y-a.png")).willReturn(Optional.of(session));

        Throwable thrown = catchThrowable(() -> uploadSessionService.validateScope(
                UploadPurpose.DREAMI_CRIMINAL_RECORD, boormiId, null, "uploads/x/y-a.png"));

        assertThat(errorCodeOf(thrown)).isEqualTo(UploadErrorCode.KEY_OWNER_MISMATCH);
    }

    @Test
    void 다른_사람에게_발급된_key면_KEY_OWNER_MISMATCH_예외() {
        UUID owner = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        UploadSession session = UploadSession.create(UploadPurpose.DREAMI_ID_CARD, owner, null, "uploads/x/y-a.png");
        given(uploadSessionRepository.findByS3Key("uploads/x/y-a.png")).willReturn(Optional.of(session));

        Throwable thrown = catchThrowable(() -> uploadSessionService.validateScope(UploadPurpose.DREAMI_ID_CARD,
                attacker, null, "uploads/x/y-a.png"));

        assertThat(errorCodeOf(thrown)).isEqualTo(UploadErrorCode.KEY_OWNER_MISMATCH);
    }

    @Test
    void 발급된_적_없는_key면_FILE_NOT_FOUND_예외() {
        given(uploadSessionRepository.findByS3Key("uploads/x/y-a.png")).willReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> uploadSessionService.validateScope(UploadPurpose.DREAMI_ID_CARD,
                UUID.randomUUID(), null, "uploads/x/y-a.png"));

        assertThat(errorCodeOf(thrown)).isEqualTo(UploadErrorCode.FILE_NOT_FOUND);
    }

    // ---------- consume ----------

    @Test
    void 조건부_UPDATE가_1행을_바꾸면_true를_반환한다() {
        given(uploadSessionRepository.markConsumedIfIssued("uploads/x/y-a.png")).willReturn(1);

        boolean result = uploadSessionService.consume("uploads/x/y-a.png");

        assertThat(result).isTrue();
    }

    @Test
    void 이미_소비돼_조건부_UPDATE가_0행이면_예외없이_false를_반환한다() {
        UploadSession session = UploadSession.create(UploadPurpose.DREAMI_ID_CARD, UUID.randomUUID(), null,
                "uploads/x/y-a.png");
        session.consume();
        given(uploadSessionRepository.markConsumedIfIssued("uploads/x/y-a.png")).willReturn(0);
        given(uploadSessionRepository.findByS3Key("uploads/x/y-a.png")).willReturn(Optional.of(session));

        boolean result = uploadSessionService.consume("uploads/x/y-a.png");

        assertThat(result).isFalse();
    }

    @Test
    void 발급된_적_없는_key를_소비하려하면_FILE_NOT_FOUND_예외() {
        given(uploadSessionRepository.markConsumedIfIssued("uploads/x/y-a.png")).willReturn(0);
        given(uploadSessionRepository.findByS3Key("uploads/x/y-a.png")).willReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> uploadSessionService.consume("uploads/x/y-a.png"));

        assertThat(errorCodeOf(thrown)).isEqualTo(UploadErrorCode.FILE_NOT_FOUND);
    }

    // ---------- checkUpload ----------

    @Test
    void 확인시_스코프가_다르면_KEY_OWNER_MISMATCH_예외이고_업로드여부는_확인하지_않는다() {
        UUID boormiId = UUID.randomUUID();
        UploadSession session = UploadSession.create(UploadPurpose.DREAMI_ID_CARD, boormiId, null, "uploads/x/y-a.png");
        given(uploadSessionRepository.findByS3Key("uploads/x/y-a.png")).willReturn(Optional.of(session));

        Throwable thrown = catchThrowable(() -> uploadSessionService.checkUpload(
                UploadPurpose.DREAMI_CRIMINAL_RECORD, boormiId, "uploads/x/y-a.png"));

        assertThat(errorCodeOf(thrown)).isEqualTo(UploadErrorCode.KEY_OWNER_MISMATCH);
        verify(s3PresignService, never()).isFileUploaded(any());
    }

    @Test
    void 확인시_아직_업로드가_안됐으면_FILE_NOT_FOUND_예외이고_소비하지_않는다() {
        UUID boormiId = UUID.randomUUID();
        UploadSession session = UploadSession.create(UploadPurpose.DREAMI_ID_CARD, boormiId, null, "uploads/x/y-a.png");
        given(uploadSessionRepository.findByS3Key("uploads/x/y-a.png")).willReturn(Optional.of(session));
        given(s3PresignService.isFileUploaded("uploads/x/y-a.png")).willReturn(false);

        Throwable thrown = catchThrowable(() -> uploadSessionService.checkUpload(UploadPurpose.DREAMI_ID_CARD,
                boormiId, "uploads/x/y-a.png"));

        assertThat(errorCodeOf(thrown)).isEqualTo(UploadErrorCode.FILE_NOT_FOUND);
        verify(uploadSessionRepository, never()).markConsumedIfIssued(any());
    }

    @Test
    void 확인시_업로드됐고_처음_소비되면_true를_반환한다() {
        UUID boormiId = UUID.randomUUID();
        UploadSession session = UploadSession.create(UploadPurpose.DREAMI_ID_CARD, boormiId, null, "uploads/x/y-a.png");
        given(uploadSessionRepository.findByS3Key("uploads/x/y-a.png")).willReturn(Optional.of(session));
        given(s3PresignService.isFileUploaded("uploads/x/y-a.png")).willReturn(true);
        given(uploadSessionRepository.markConsumedIfIssued("uploads/x/y-a.png")).willReturn(1);

        boolean result = uploadSessionService.checkUpload(UploadPurpose.DREAMI_ID_CARD, boormiId,
                "uploads/x/y-a.png");

        assertThat(result).isTrue();
    }

    @Test
    void 확인시_재시도로_이미_소비된_요청이면_false를_반환한다() {
        UUID boormiId = UUID.randomUUID();
        UploadSession session = UploadSession.create(UploadPurpose.DREAMI_ID_CARD, boormiId, null, "uploads/x/y-a.png");
        session.consume();
        given(uploadSessionRepository.findByS3Key("uploads/x/y-a.png")).willReturn(Optional.of(session));
        given(s3PresignService.isFileUploaded("uploads/x/y-a.png")).willReturn(true);
        given(uploadSessionRepository.markConsumedIfIssued("uploads/x/y-a.png")).willReturn(0);

        boolean result = uploadSessionService.checkUpload(UploadPurpose.DREAMI_ID_CARD, boormiId,
                "uploads/x/y-a.png");

        assertThat(result).isFalse();
    }
}
