package com.naengsam.quick.domain.matching.event;

import java.util.UUID;

/**
 * 드리미가 제안을 수락했음을 알리는 이벤트. 주문이 PENDING_BOORMI_CONFIRMATION 으로 커밋된 뒤에 매칭엔진에 수락을 제출하기 위해 쓴다.
 */
public record DreamiAcceptedEvent(UUID offerId) {
}
