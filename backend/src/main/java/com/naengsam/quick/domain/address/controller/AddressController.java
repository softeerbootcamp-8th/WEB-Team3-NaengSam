package com.naengsam.quick.domain.address.controller;

import com.naengsam.quick.domain.address.dto.AddressRequest;
import com.naengsam.quick.domain.address.dto.AddressDto;
import com.naengsam.quick.domain.address.service.AddressService;
import com.naengsam.quick.global.code.GeneralErrorCode;
import com.naengsam.quick.global.session.LoginUser;
import com.naengsam.quick.global.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/address")
@Tag(name = "배송지 컨트롤러", description = "부르미가 등록한 배송지를 저장하고 조회한다.")
public class AddressController {

    private final AddressService addressService;

    @Operation(summary = "배송지 저장", description = "도로명주소를 좌표로 변환해 배송지를 저장한다.")
    @PostMapping
    @ApiResponse(responseCode = "200", description = "요청에 성공했습니다.")
    @ApiErrorCodes(enumClass = GeneralErrorCode.class, codes = {"EXTERNAL_SERVICE_ERROR", "EXTERNAL_SERVICE_TIMEOUT"})
    public UUID saveAddress(@Valid @RequestBody AddressRequest requestDto, @LoginUser UUID boormiId) {
        return addressService.saveAddress(requestDto, boormiId);
    }

    @Operation(summary = "배송지 전체 조회", description = "저장된 배송지 목록을 조회한다.")
    @GetMapping
    @ApiResponse(responseCode = "200", description = "요청에 성공한다.")
    public List<AddressDto> findAll(@LoginUser UUID boormiId) {
        return addressService.findAll(boormiId);
    }
}
