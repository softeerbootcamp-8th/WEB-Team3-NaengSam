package com.naengsam.quick.domain.address.service;

import com.naengsam.quick.domain.address.dto.KakaoDirectionsDto;
import com.naengsam.quick.domain.matching.dto.GeoPoint;
import com.naengsam.quick.global.code.GeneralErrorCode;
import com.naengsam.quick.global.exception.BusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 카카오맵 도보 길찾기 API로 출발지→도착지의 실제 보행 거리(m)와 소요시간(s)을 구한다.
 */
@Slf4j
@Service
public class KakaoDirectionsService {

    private static final String restApiKey = System.getenv("KAKAO_REST_API_KEY");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient = buildRestClient();

    private static RestClient buildRestClient() {
        // 연결 자체가 안 되는 상황을 막기 위해
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT); // api 요청 타임아웃

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 출발지/도착지 좌표로 도보 경로를 조회해 총 거리(m)와 소요시간(s)이 담긴 요약을 반환한다.
     */
    public KakaoDirectionsDto.Properties getRoute(GeoPoint origin, GeoPoint destination) {

        URI uri = UriComponentsBuilder.fromUriString("https://dapi.kakao.com/v2/routing/walk")
                .queryParam("start_x", origin.longitude())
                .queryParam("start_y", origin.latitude())
                .queryParam("end_x", destination.longitude())
                .queryParam("end_y", destination.latitude())
                .build()
                .toUri();

        KakaoDirectionsDto response;
        try {
            response = restClient.get()
                    .uri(uri)
                    .header("Authorization", "KakaoAK " + restApiKey)
                    .retrieve()
                    .body(KakaoDirectionsDto.class);
        } catch (ResourceAccessException e) {
            log.warn("카카오 도보 길찾기 API 응답 지연", e);
            throw new BusinessException(GeneralErrorCode.EXTERNAL_SERVICE_TIMEOUT);
        } catch (RestClientException e) {
            log.warn("카카오 도보 길찾기 API 호출 실패", e);
            throw new BusinessException(GeneralErrorCode.EXTERNAL_SERVICE_ERROR);
        }

        if (response == null || !"OK".equals(response.status()) || response.route() == null
                || response.route().properties() == null) {
            throw new BusinessException(GeneralErrorCode.EXTERNAL_SERVICE_ERROR);
        }
        return response.route().properties();
    }
}
