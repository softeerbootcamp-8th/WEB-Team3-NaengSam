package com.naengsam.quick.domain.address.controller;

import com.naengsam.quick.domain.address.dto.AddressApiRequest;
import com.naengsam.quick.domain.address.dto.AddressCoordinatesDto;
import com.naengsam.quick.domain.address.dto.CoordinatesDto;
import com.naengsam.quick.domain.address.service.CoordinatesService;
import com.naengsam.quick.global.code.GeneralErrorCode;
import com.naengsam.quick.global.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/address")
@Tag(name = "주소 좌표 변환 컨트롤러", description = "도로명주소를 위도/경도로 변환한다. 결제가 완료되기 전까지는 아무것도 저장하지 않는다.")
@RequiredArgsConstructor
public class AddressApiController {

    private final CoordinatesService coordinatesService;

    @Operation(summary = "배송지 좌표 변환", description = "출발지/도착지 도로명주소를 위도/경도로 변환해 반환한다. 클라이언트는 이 값을 들고 있다가 결제 완료 시점에 주문 생성 요청에 함께 담아 보낸다.")
    @PostMapping("/place")
    @ApiResponse(responseCode = "200", description = "요청에 성공했습니다.")
    @ApiErrorCodes(enumClass = GeneralErrorCode.class, codes = {"EXTERNAL_SERVICE_ERROR", "EXTERNAL_SERVICE_TIMEOUT"})
    public AddressCoordinatesDto getCoordinates(@Valid @RequestBody AddressApiRequest requestDto) {
        // 카카오의 도로명주소 -> 위도 경도로 변환 api 사용
        CoordinatesDto originCoordinates = coordinatesService.getCoordinates(requestDto.origin());
        CoordinatesDto destinationCoordinates = coordinatesService.getCoordinates(requestDto.destination());

        // 결제 완료 전까지는 아무것도 저장하지 않고, 계산된 좌표만 클라이언트에 돌려준다.
        // 클라이언트가 이 값을 나머지 주문 정보와 함께 들고 있다가 결제 완료 시점에 한 번에 제출한다.
        return new AddressCoordinatesDto(
                new BigDecimal(originCoordinates.documents().getFirst().roadAddress().y()).setScale(8, RoundingMode.HALF_UP),
                new BigDecimal(originCoordinates.documents().getFirst().roadAddress().x()).setScale(8, RoundingMode.HALF_UP),
                new BigDecimal(destinationCoordinates.documents().getFirst().roadAddress().y()).setScale(8, RoundingMode.HALF_UP),
                new BigDecimal(destinationCoordinates.documents().getFirst().roadAddress().x()).setScale(8, RoundingMode.HALF_UP)
        );
    }
}
