package com.naengsam.quick.domain.address.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 카카오맵 도보 길찾기 API 응답. 요금 계산에 필요한 총 거리/소요시간만 매핑하고 나머지는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoDirectionsDto(
        Route route,
        String status
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Route(
            Properties properties
    ) {
    }

    /**
     * 경로 요약 정보. totalDistance=미터, totalTime=초.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Properties(
            int totalDistance,
            int totalTime
    ) {
    }
}
