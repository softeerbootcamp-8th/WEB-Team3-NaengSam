package com.naengsam.quick.domain.matching.event;

import java.util.UUID;

/**
 * 부르미가 드리미를 최종 확정했음을 알리는 이벤트. 주문이 IN_PROGRESS 로 커밋된 뒤에 매칭엔진에 수락을 제출하기 위해 쓴다.
 */
public record BoormiConfirmedEvent(UUID offerId) {
}
