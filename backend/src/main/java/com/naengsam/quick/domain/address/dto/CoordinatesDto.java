package com.naengsam.quick.domain.address.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 카카오 로컬 API(주소 검색)의 응답. 좌표 계산에 필요한 필드만 매핑하고 나머지는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true) // 정의하지 않은 메타 데이터나 다른 필드는 무시
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CoordinatesDto(
        List<Document> documents
) {
    /**
     * 검색된 주소 한 건.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Document(
            RoadAddress roadAddress
    ) {
    }

    /**
     * 도로명주소 상세 정보와 좌표(x=경도, y=위도).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RoadAddress(
            String addressName,
            String region1depthName,
            String region2depthName,
            String region3depthName,
            String roadName,
            String mainBuildingNo,
            String subBuildingNo,
            String buildingName,
            String zoneNo,
            String x, // 경도
            String y  // 위도
    ) {
    }
}
