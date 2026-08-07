package com.naengsam.quick.domain.address.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 배송지 저장 요청. 좌표는 요청에 포함하지 않고, 서버가 {@code addressLine1} 을 좌표 변환해 채운다.
 */
public record AddressRequest(
        @NotBlank
        String addressAlias,

        @NotBlank
        String addressLine1,

        @NotBlank
        String addressLine2
) {
}
