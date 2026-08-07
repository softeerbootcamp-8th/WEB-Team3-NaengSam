package com.naengsam.quick.domain.dreami.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.naengsam.quick.domain.address.dto.CoordinatesDto;
import com.naengsam.quick.domain.address.service.CoordinatesService;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

class CoordinatesServiceTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 도로명주소로_좌표를_조회한다() {
        // restApiKey는 static final이라 인스턴스 필드처럼 reflection으로 바꿀 수 없고, 아래 mock이
        // header(any(), any())로 값과 무관하게 매칭하므로 실제 키 값은 이 테스트에 영향을 주지 않는다.
        CoordinatesService coordinatesService = new CoordinatesService();

        RestClient restClient = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        CoordinatesDto.RoadAddress roadAddress = new CoordinatesDto.RoadAddress(
                "서울 강남구 테헤란로 1", "서울", "강남구", null,
                "테헤란로", "1", null, null, "06134",
                "127.0276", "37.4979"
        );
        CoordinatesDto expected = new CoordinatesDto(
                List.of(new CoordinatesDto.Document(roadAddress))
        );

        when(restClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(any(URI.class))).thenReturn(uriSpec);
        when(uriSpec.header(any(), any())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(CoordinatesDto.class)).thenReturn(expected);

        ReflectionTestUtils.setField(coordinatesService, "restClient", restClient);

        CoordinatesDto result = coordinatesService.getCoordinates("서울시 강남구");

        System.out.println("latitude = " + result.documents().getFirst().roadAddress().y());
        System.out.println("longitude = " + result.documents().getFirst().roadAddress().x());

        assertThat(result).isSameAs(expected);
    }
}
