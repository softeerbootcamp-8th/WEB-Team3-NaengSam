package com.naengsam.quick.domain.matching.event;

import java.util.UUID;

/**
 * 부르미가 수락한 드리미를 거절했음을 알리는 이벤트. 주문이 MATCHING 으로 되돌려진 뒤에 매칭엔진에 거절을 제출하기 위해 쓴다.
 */
public record BoormiRejectedDreamiEvent(UUID offerId) {
}
