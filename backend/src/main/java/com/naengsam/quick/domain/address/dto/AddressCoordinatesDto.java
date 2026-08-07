package com.naengsam.quick.domain.address.dto;

import java.math.BigDecimal;

/**
 * 배송지 좌표 변환 응답. 아무것도 저장하지 않고 계산된 위도/경도만 반환하며, 클라이언트가 이를 들고 있다가 결제 완료 시점에
 * 주문 생성 요청에 함께 담아 보낸다.
 */
public record AddressCoordinatesDto(
        BigDecimal originLatitude,
        BigDecimal originLongitude,
        BigDecimal destinationLatitude,
        BigDecimal destinationLongitude
) {
}
