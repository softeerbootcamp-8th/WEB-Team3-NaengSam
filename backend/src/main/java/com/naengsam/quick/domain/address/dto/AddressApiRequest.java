package com.naengsam.quick.domain.address.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 배송지 좌표 변환 요청. 결제 전 단계에서 출발지/도착지 도로명주소와 상세주소를 담아 좌표 변환만 요청한다.
 */
public record AddressApiRequest(
        @NotBlank
        String origin,

        @NotBlank
        String originDetail,

        @NotBlank
        String destination,

        @NotBlank
        String destinationDetail
) {
}
