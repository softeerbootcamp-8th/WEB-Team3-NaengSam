package com.naengsam.quick.domain.address.service;

import com.naengsam.quick.domain.address.dto.AddressRequest;
import com.naengsam.quick.domain.address.dto.AddressDto;
import com.naengsam.quick.domain.address.dto.CoordinatesDto;
import com.naengsam.quick.domain.address.entity.Address;
import com.naengsam.quick.domain.address.repository.AddressRepository;
import com.naengsam.quick.global.code.GeneralErrorCode;
import com.naengsam.quick.global.exception.BusinessException;
import com.naengsam.quick.global.session.LoginUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AddressService {

    private final AddressRepository addressRepository;
    private final CoordinatesService coordinatesService;

    /**
     * 도로명주소를 {@code CoordinatesService} 로 좌표 변환해 배송지를 저장하고, 생성된 식별자를 반환한다.
     */
    public UUID saveAddress(AddressRequest requestDto, @LoginUser UUID boormiId) {
        CoordinatesDto coordinates = coordinatesService.getCoordinates(requestDto.addressLine1());
        List<CoordinatesDto.Document> documents = coordinates.documents();
        if (documents.isEmpty()) {
            throw new BusinessException(GeneralErrorCode.EXTERNAL_SERVICE_ERROR);
        }
        CoordinatesDto.RoadAddress roadAddress = documents.getFirst().roadAddress();

        Address address = Address.builder()
                .addressId(UUID.randomUUID())
                .addressAlias(requestDto.addressAlias())
                .latitude(new BigDecimal(roadAddress.y()).setScale(8, RoundingMode.HALF_UP))
                .longitude(new BigDecimal(roadAddress.x()).setScale(8, RoundingMode.HALF_UP))
                .addressLine1(requestDto.addressLine1())
                .addressLine2(requestDto.addressLine2())
                .boormiId(boormiId)
                .build();

        return addressRepository.save(address).getAddressId();
    }

    /**
     * 저장된 배송지 전체를 조회한다.
     */
    public List<AddressDto> findAll(UUID boormiId) {
        return addressRepository.findAllByBoormiId(boormiId).stream()
                .map(address -> new AddressDto(
                        address.getAddressAlias(),
                        address.getAddressLine1(),
                        address.getAddressLine2(),
                        address.getBoormiId().toString()
                ))
                .toList();
    }
}
