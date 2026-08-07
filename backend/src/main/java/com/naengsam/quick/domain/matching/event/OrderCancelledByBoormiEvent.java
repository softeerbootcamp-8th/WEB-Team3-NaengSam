package com.naengsam.quick.domain.matching.event;

import java.util.UUID;

/**
 * 부르미가 매칭 중인 주문을 취소했음을 알리는 이벤트. 주문 취소와 환불이 커밋된 뒤에 매칭엔진에 제안 회수를 제출하기 위해 쓴다.
 */
public record OrderCancelledByBoormiEvent(UUID orderId) {
}
