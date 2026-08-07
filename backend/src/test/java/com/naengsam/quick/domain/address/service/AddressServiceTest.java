package com.naengsam.quick.domain.address.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.naengsam.quick.domain.address.dto.AddressRequest;
import com.naengsam.quick.domain.address.dto.AddressDto;
import com.naengsam.quick.domain.address.dto.CoordinatesDto;
import com.naengsam.quick.domain.address.entity.Address;
import com.naengsam.quick.domain.address.repository.AddressRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AddressServiceTest {

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private CoordinatesService coordinatesService;

    @InjectMocks
    private AddressService addressService;

    @Test
    void 주소를_저장하면_좌표가_계산되어_반영된_주소가_저장되고_생성된_id가_반환된다() {
        UUID boormiId = UUID.randomUUID();
        AddressRequest requestDto = new AddressRequest(
                "우리집",
                "서울시 강남구",
                "101동 202호"
        );
        CoordinatesDto.RoadAddress roadAddress =
                new CoordinatesDto.RoadAddress(
                        null, null, null, null, null, null, null, null, null,
                        "127.123456", "37.123456"
                );
        CoordinatesDto coordinatesResponseDto =
                new CoordinatesDto(List.of(new CoordinatesDto.Document(roadAddress)));
        when(coordinatesService.getCoordinates(requestDto.addressLine1()))
                .thenReturn(coordinatesResponseDto);
        when(addressRepository.save(any(Address.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UUID savedId = addressService.saveAddress(requestDto, boormiId);

        ArgumentCaptor<Address> captor = ArgumentCaptor.forClass(Address.class);
        org.mockito.Mockito.verify(addressRepository).save(captor.capture());
        Address savedAddress = captor.getValue();

        assertThat(savedId).isEqualTo(savedAddress.getAddressId());
        assertThat(savedAddress.getAddressAlias()).isEqualTo(requestDto.addressAlias());
        assertThat(savedAddress.getLatitude()).isEqualByComparingTo(new BigDecimal("37.123456"));
        assertThat(savedAddress.getLongitude()).isEqualByComparingTo(new BigDecimal("127.123456"));
        assertThat(savedAddress.getAddressLine1()).isEqualTo(requestDto.addressLine1());
        assertThat(savedAddress.getAddressLine2()).isEqualTo(requestDto.addressLine2());
        assertThat(savedAddress.getBoormiId()).isEqualTo(boormiId);
    }

    @Test
    void 전체_주소를_조회하면_응답_dto_목록으로_변환되어_반환된다() {
        UUID boormiId = UUID.randomUUID();
        Address address = Address.builder()
                .addressId(UUID.randomUUID())
                .addressAlias("우리집")
                .latitude(new BigDecimal("37.123456"))
                .longitude(new BigDecimal("127.123456"))
                .addressLine1("서울시 강남구")
                .addressLine2("101동 202호")
                .boormiId(boormiId)
                .build();
        when(addressRepository.findAllByBoormiId(boormiId)).thenReturn(List.of(address));

        List<AddressDto> result = addressService.findAll(boormiId);

        assertThat(result).containsExactly(new AddressDto(
                address.getAddressAlias(),
                address.getAddressLine1(),
                address.getAddressLine2(),
                address.getBoormiId().toString()
        ));
    }
}
