package com.naengsam.quick.domain.upload.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.naengsam.quick.domain.upload.dto.PresignedUrlDto;
import com.naengsam.quick.domain.upload.entity.UploadPurpose;
import com.naengsam.quick.domain.upload.service.UploadSessionService;
import com.naengsam.quick.global.exception.GlobalExceptionHandler;
import com.naengsam.quick.global.session.LoginUserArgumentResolver;
import com.naengsam.quick.global.session.SessionConst;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * presigned URL 발급 시 fileName 검증(첨부 없음/부적절한 이름)이 정의서(FILE_001/FILE_006)대로 동작하는지 검증한다.
 */
class UploadControllerTest {

    private UploadSessionService uploadSessionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        uploadSessionService = mock(UploadSessionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UploadController(uploadSessionService))
                .setCustomArgumentResolvers(new LoginUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void fileName이_비어있으면_FILE_001로_거부한다() throws Exception {
        UUID boormiId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/upload/url")
                        .param("fileName", "")
                        .param("purpose", "DREAMI_ID_CARD")
                        .sessionAttr(SessionConst.LOGIN_USER, boormiId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_001"));

        verify(uploadSessionService, never()).issue(any(), any(), any(), any());
    }

    @Test
    void fileName에_경로_구분자가_있으면_FILE_006으로_거부한다() throws Exception {
        UUID boormiId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/upload/url")
                        .param("fileName", "../secret.png")
                        .param("purpose", "DREAMI_ID_CARD")
                        .sessionAttr(SessionConst.LOGIN_USER, boormiId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_006"));

        verify(uploadSessionService, never()).issue(any(), any(), any(), any());
    }

    @Test
    void 정상적인_fileName이면_purpose와_boormiId로_세션을_발급받는다() throws Exception {
        UUID boormiId = UUID.randomUUID();
        String key = "uploads/DREAMI_ID_CARD/aaa-idcard.png";
        when(uploadSessionService.issue(UploadPurpose.DREAMI_ID_CARD, boormiId, null, "idcard.png"))
                .thenReturn(new PresignedUrlDto("https://example.com/upload", key));

        mockMvc.perform(get("/api/v1/upload/url")
                        .param("fileName", "idcard.png")
                        .param("purpose", "DREAMI_ID_CARD")
                        .sessionAttr(SessionConst.LOGIN_USER, boormiId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.url").value("https://example.com/upload"));

        verify(uploadSessionService).issue(eq(UploadPurpose.DREAMI_ID_CARD), eq(boormiId), isNull(), eq("idcard.png"));
    }
}
