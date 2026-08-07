package com.naengsam.quick.domain.matching.event;

import com.naengsam.quick.domain.order.entity.Orders;

/**
 * 부르미의 주문 접수가 끝나 매칭을 시작해야 함을 알리는 이벤트. 주문(ORDERS)과 결제가 커밋된 뒤에 매칭엔진에 매칭 시작을 제출하기 위해 쓴다.
 */
public record MatchingStartRequestedEvent(Orders order) {
}
