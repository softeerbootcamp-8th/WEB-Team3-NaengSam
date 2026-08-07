package com.naengsam.quick.domain.dreami.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.naengsam.quick.domain.address.dto.CoordinatesDto;
import com.naengsam.quick.domain.address.service.CoordinatesService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 실제 카카오 API에 대고 검증하므로 {@code CoordinatesService}만 직접 생성한다({@code @SpringBootTest} 로
 * 전체 컨텍스트를 띄우면 이 테스트와 무관한 다른 프로퍼티(DATABASE_URL 등) 까지 다 필요해져서 환경에 따라
 * 엉뚱하게 실패한다). {@code KAKAO_REST_API_KEY} 가 없는 환경에서는 건너뛴다.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "KAKAO_REST_API_KEY", matches = ".+")
class CoordinatesServiceIntegrationTest {

    private final CoordinatesService coordinatesService = new CoordinatesService();

    @Test
    void 실제_카카오_API로_도로명주소를_좌표로_변환한다() {
        CoordinatesDto response = coordinatesService.getCoordinates("서울특별시 강남구 테헤란로 427");

        assertThat(response.documents()).isNotEmpty();

        CoordinatesDto.RoadAddress roadAddress = response.documents().getFirst().roadAddress();
        assertThat(roadAddress.y()).isNotBlank(); // 위도
        assertThat(roadAddress.x()).isNotBlank(); // 경도
    }
}
