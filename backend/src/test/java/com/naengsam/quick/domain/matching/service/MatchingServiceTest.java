package com.naengsam.quick.domain.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.naengsam.quick.domain.delivery.service.DeliveryService;
import com.naengsam.quick.domain.matching.dto.GeoPoint;
import com.naengsam.quick.domain.matching.event.BoormiConfirmedEvent;
import com.naengsam.quick.domain.matching.event.BoormiRejectedDreamiEvent;
import com.naengsam.quick.domain.matching.event.DreamiAcceptedEvent;
import com.naengsam.quick.domain.matching.event.MatchingEventType;
import com.naengsam.quick.domain.matching.event.MatchingStartRequestedEvent;
import com.naengsam.quick.domain.matching.event.OrderCancelledByBoormiEvent;
import com.naengsam.quick.domain.matching.model.MatchOffer;
import com.naengsam.quick.domain.matching.model.MatchOfferStatus;
import com.naengsam.quick.domain.matching.model.OrderOfferGroup;
import com.naengsam.quick.domain.matching.model.OrderOfferGroupStatus;
import com.naengsam.quick.domain.matching.model.WaitingDreami;
import com.naengsam.quick.domain.matching.model.WaitingDreamiStatus;
import com.naengsam.quick.domain.order.entity.Orders;
import com.naengsam.quick.global.sse.SseService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class MatchingServiceTest {

    private MatchingService matchingService;
    private MatchingEngine matchingEngine;
    private SseService sseService;
    private OfferTimeoutScheduler offerTimeoutScheduler;
    private DeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        matchingEngine = mock(MatchingEngine.class);
        sseService = mock(SseService.class);
        offerTimeoutScheduler = mock(OfferTimeoutScheduler.class);
        deliveryService = mock(DeliveryService.class);
        matchingService = new MatchingService(matchingEngine, sseService, offerTimeoutScheduler, deliveryService);
    }

    @Test
    void 주문을_등록하면_최대_3명의_드리미에게_제안한다() {
        // given
        UUID orderId = UUID.randomUUID();

        UUID dreamiId1 = UUID.randomUUID();
        UUID dreamiId2 = UUID.randomUUID();
        UUID dreamiId3 = UUID.randomUUID();
        UUID dreamiId4 = UUID.randomUUID();

        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);

        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(dreamiId1, location);
        matchingService.applyRegisterDreami(dreamiId2, location);
        matchingService.applyRegisterDreami(dreamiId3, location);
        matchingService.applyRegisterDreami(dreamiId4, location);

        // when
        matchingService.applyStartMatching(order);

        // then
        Map<UUID, OrderOfferGroup> orderOfferGroups =
                getOrderOfferGroups();

        List<MatchOffer> offers =
                orderOfferGroups.get(orderId).offers();

        assertThat(offers).hasSize(3);
        assertThat(offers)
                .allMatch(offer ->
                        offer.status() == MatchOfferStatus.OFFERED);

        Map<UUID, WaitingDreami> dreamiMap = getDreamiMap();

        long proposedCount = dreamiMap.values().stream()
                .filter(dreami ->
                        dreami.status() ==
                                WaitingDreamiStatus.PROPOSED)
                .count();

        long matchingCount = dreamiMap.values().stream()
                .filter(dreami ->
                        dreami.status() ==
                                WaitingDreamiStatus.MATCHING)
                .count();

        assertThat(proposedCount).isEqualTo(3);
        assertThat(matchingCount).isEqualTo(1);
    }

    @Test
    void 드리미_한명이_수락하면_나머지_제안은_회수된다() {
        // given
        UUID orderId = UUID.randomUUID();

        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);

        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyRegisterDreami(UUID.randomUUID(), location);

        matchingService.applyStartMatching(order);

        List<MatchOffer> offers =
                getOrderOfferGroups().get(orderId).offers();

        MatchOffer acceptedOffer = offers.getFirst();

        WaitingDreami acceptedDreami =
                getDreamiMap().get(acceptedOffer.dreamiId());

        // when
        matchingService.applyAcceptByDreami(acceptedOffer.offerId());

        // then
        assertThat(acceptedOffer.status())
                .isEqualTo(
                        MatchOfferStatus
                                .PENDING_BOORMI_CONFIRMATION
                );

        assertThat(offers)
                .filteredOn(offer ->
                        !offer.offerId().equals(acceptedOffer.offerId()))
                .allMatch(offer ->
                        offer.status() ==
                                MatchOfferStatus.WITHDRAWN);

        assertThat(acceptedDreami.status())
                .isEqualTo(
                        WaitingDreamiStatus.PROPOSED
                );

        assertThat(offers)
                .filteredOn(offer ->
                        !offer.offerId().equals(acceptedOffer.offerId()))
                .extracting(MatchOffer::dreamiId)
                .allMatch(dreamiId ->
                        getDreamiMap().get(dreamiId).status()
                                == WaitingDreamiStatus.MATCHING
                );
    }

    @Test
    void 부르미까지_수락하면_MATCHED가_된다() {
        // given
        UUID orderId = UUID.randomUUID();

        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);

        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyStartMatching(order);

        MatchOffer offer =
                getOrderOfferGroups().get(orderId).offers().getFirst();

        matchingService.applyAcceptByDreami(offer.offerId());

        // when
        matchingService.applyAcceptByBoormi(offer.offerId());

        // then
        assertThat(offer.status())
                .isEqualTo(MatchOfferStatus.MATCHED);

        assertThat(getOrderOfferGroups()).doesNotContainKey(orderId);
    }

    @Test
    void 부르미까지_수락하면_배달이_시작된다() {
        // given
        UUID orderId = UUID.randomUUID();

        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);

        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyStartMatching(order);

        OrderOfferGroup group = getOrderOfferGroups().get(orderId);
        MatchOffer offer = group.offers().getFirst();
        UUID boormiId = group.boormiId();
        UUID dreamiId = offer.dreamiId();

        matchingService.applyAcceptByDreami(offer.offerId());

        // when
        matchingService.applyAcceptByBoormi(offer.offerId());

        // then
        verify(deliveryService, times(1)).startDelivery(orderId, dreamiId, boormiId);
    }

    @Test
    void 이미_진행중인_방이_있으면_다시_매칭을_시작할_수_없고_기존_그룹은_유지된다() {
        // given
        UUID orderId = UUID.randomUUID();

        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);

        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyStartMatching(order);

        OrderOfferGroup originalGroup = getOrderOfferGroups().get(orderId);

        // when
        boolean started = matchingService.startMatching(order);

        // then
        assertThat(started).isFalse();
        assertThat(getOrderOfferGroups().get(orderId)).isSameAs(originalGroup);
    }

    @Test
    void 대기중인_드리미가_없으면_Offer_없이_그룹이_생성되고_재매칭_대상이_된다() {
        // given
        UUID orderId = UUID.randomUUID();

        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        // when (등록된 드리미가 한 명도 없는 상태에서 매칭 시작)
        matchingService.applyStartMatching(order);

        // then
        OrderOfferGroup group = getOrderOfferGroups().get(orderId);

        assertThat(group.offers()).isEmpty();
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.CLOSED);
        assertThat(group.rematchRequired()).isTrue();
    }

    @Test
    void 대기중인_드리미가_한명이면_Offer_한개짜리_그룹이_OPEN_상태로_생성된다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();

        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(dreamiId, location);

        // when
        matchingService.applyStartMatching(order);

        // then
        OrderOfferGroup group = getOrderOfferGroups().get(orderId);

        assertThat(group.offers()).hasSize(1);
        assertThat(group.offers().getFirst().dreamiId()).isEqualTo(dreamiId);
        assertThat(group.offers().getFirst().status())
                .isEqualTo(MatchOfferStatus.OFFERED);
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.OPEN);
        assertThat(group.rematchRequired()).isFalse();
    }

    @Test
    void 드리미_한명이_여러_주문의_OrderOfferGroup에_동시에_들어갈_수_있다() {
        // 후속 커밋에서 드리미가 한 번에 하나의 방에만 참여하도록 제한할 예정.
        // 이번 커밋(OrderOfferGroup 도입) 범위에서는 아직 그 제한이 없다는 것을 명시적으로 확인한다.

        // given
        UUID dreamiId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);

        UUID orderIdA = UUID.randomUUID();
        UUID orderIdB = UUID.randomUUID();
        Orders orderA = mock(Orders.class);
        Orders orderB = mock(Orders.class);
        when(orderA.getOrderId()).thenReturn(orderIdA);
        when(orderB.getOrderId()).thenReturn(orderIdB);

        matchingService.applyRegisterDreami(dreamiId, location);
        matchingService.applyStartMatching(orderA);

        // 원래라면 PROPOSED 상태라 다음 매칭 후보에서 제외되지만, 드리미 상태 제한이 아직 없다는 것을 보여주기 위해
        // 공개 API(markMatching)로 다시 MATCHING 상태로 되돌린다.
        WaitingDreami dreami = getDreamiMap().get(dreamiId);
        dreami.markMatching();

        // when
        matchingService.applyStartMatching(orderB);

        // then
        List<MatchOffer> offersA = getOrderOfferGroups().get(orderIdA).offers();
        List<MatchOffer> offersB = getOrderOfferGroups().get(orderIdB).offers();

        assertThat(offersA).extracting(MatchOffer::dreamiId).containsExactly(dreamiId);
        assertThat(offersB).extracting(MatchOffer::dreamiId).containsExactly(dreamiId);
    }

    @Test
    void Offer_상태변경은_그룹을_다시_조회해도_그대로_반영된다() {
        // given
        UUID orderId = UUID.randomUUID();

        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyRegisterDreami(UUID.randomUUID(), location);

        matchingService.applyStartMatching(order);

        MatchOffer acceptedOffer =
                getOrderOfferGroups().get(orderId).offers().getFirst();
        WaitingDreami acceptedDreami =
                getDreamiMap().get(acceptedOffer.dreamiId());

        // when (수락되지 않은 나머지 오퍼가 OFFERED -> WITHDRAWN 으로 바뀜)
        matchingService.applyAcceptByDreami(acceptedOffer.offerId());

        // then
        List<MatchOffer> offersAfter =
                getOrderOfferGroups().get(orderId).offers();

        assertThat(offersAfter)
                .filteredOn(offer -> !offer.offerId().equals(acceptedOffer.offerId()))
                .allMatch(offer -> offer.status() == MatchOfferStatus.WITHDRAWN);
    }

    @Test
    void 매칭이_완료되면_인메모리_상태가_정리된다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();

        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(dreamiId, location);
        matchingService.applyStartMatching(order);

        MatchOffer offer =
                getOrderOfferGroups().get(orderId).offers().getFirst();

        matchingService.applyAcceptByDreami(offer.offerId());

        // when
        matchingService.applyAcceptByBoormi(offer.offerId());

        // then (그룹/오퍼/드리미가 각 맵에서 모두 제거된다)
        assertThat(getOrderOfferGroups()).doesNotContainKey(orderId);
        assertThat(getOffersById()).doesNotContainKey(offer.offerId());
        assertThat(getDreamiMap()).doesNotContainKey(dreamiId);
    }

    @Test
    void 부르미가_거절하면_그룹은_재매칭이_필요한_상태가_된다() {
        // given
        UUID orderId = UUID.randomUUID();

        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);

        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyStartMatching(order);

        MatchOffer offer =
                getOrderOfferGroups().get(orderId).offers().getFirst();

        matchingService.applyAcceptByDreami(offer.offerId());

        // when
        matchingService.applyRejectByBoormi(offer.offerId());

        // then
        OrderOfferGroup group = getOrderOfferGroups().get(orderId);

        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.CLOSED);
        assertThat(group.rematchRequired()).isTrue();
    }

    @Test
    void 부르미가_수락한_드리미를_거절하면_WITHDRAWN된_드리미가_다시_후보가_될_수_있다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID dreamiId1 = UUID.randomUUID();
        UUID dreamiId2 = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(dreamiId1, location);
        matchingService.applyRegisterDreami(dreamiId2, location);
        matchingService.applyStartMatching(order);

        List<MatchOffer> offers = getOrderOfferGroups().get(orderId).offers();
        MatchOffer acceptedOffer = offers.getFirst();
        UUID withdrawnDreamiId = offers.get(1).dreamiId();

        matchingService.applyAcceptByDreami(acceptedOffer.offerId());
        assertThat(offers.get(1).status()).isEqualTo(MatchOfferStatus.WITHDRAWN);

        // when (부르미가 수락자를 거절 -> 남은 후보 중 WITHDRAWN된 드리미도 다시 제안 가능해야 한다)
        matchingService.applyRejectByBoormi(acceptedOffer.offerId());

        // then
        OrderOfferGroup group = getOrderOfferGroups().get(orderId);
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.OPEN);
        assertThat(group.offers())
                .filteredOn(offer -> offer.status() == MatchOfferStatus.OFFERED)
                .extracting(MatchOffer::dreamiId)
                .containsExactly(withdrawnDreamiId);
    }

    @Test
    void 모든_드리미가_거절하면_그룹은_재매칭이_필요한_상태가_된다() {
        // given
        UUID orderId = UUID.randomUUID();

        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);

        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyRegisterDreami(UUID.randomUUID(), location);

        matchingService.applyStartMatching(order);

        List<MatchOffer> offers =
                getOrderOfferGroups().get(orderId).offers();

        // when
        for (MatchOffer offer : offers) {
            matchingService.applyRejectByDreami(offer.offerId());
        }

        // then
        OrderOfferGroup group = getOrderOfferGroups().get(orderId);

        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.CLOSED);
        assertThat(group.rematchRequired()).isTrue();
    }

    @Test
    void 드리미_응답_timeout으로_만료된_오퍼는_재매칭시_같은_드리미에게_다시_제안되지_않는다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(dreamiId, location);
        matchingService.applyStartMatching(order);

        MatchOffer offer =
                getOrderOfferGroups().get(orderId).offers().getFirst();

        // when (드리미 응답시간 만료 -> 재매칭 시도 시 후보에서 제외되어야 한다)
        matchingService.applyExpireDreamiOffer(offer.offerId());

        // then
        OrderOfferGroup group = getOrderOfferGroups().get(orderId);
        assertThat(group.offers()).hasSize(1);
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.CLOSED);
        assertThat(group.rematchRequired()).isTrue();
        assertThat(getDreamiMap().get(dreamiId).status())
                .isEqualTo(WaitingDreamiStatus.MATCHING);
    }

    @Test
    void 드리미가_응답하지_않으면_DREAMI_EXPIRED가_되고_다음_대기중인_드리미에게_오퍼한다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID dreamiId1 = UUID.randomUUID();
        UUID dreamiId2 = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(dreamiId1, location);
        matchingService.applyStartMatching(order);

        MatchOffer firstOffer =
                getOrderOfferGroups().get(orderId).offers().getFirst();

        // 첫 오퍼가 나간 뒤에 새로 등록된 드리미가 다음 후보가 되어야 한다.
        matchingService.applyRegisterDreami(dreamiId2, location);

        // when (드리미1이 응답하지 않아 제한시간 만료)
        matchingService.applyExpireDreamiOffer(firstOffer.offerId());

        // then
        assertThat(firstOffer.status()).isEqualTo(MatchOfferStatus.DREAMI_EXPIRED);

        OrderOfferGroup group = getOrderOfferGroups().get(orderId);
        assertThat(group.offers()).hasSize(2);
        MatchOffer secondOffer = group.offers().getLast();
        assertThat(secondOffer.dreamiId()).isEqualTo(dreamiId2);
        assertThat(secondOffer.status()).isEqualTo(MatchOfferStatus.OFFERED);
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.OPEN);
    }

    @Test
    void 부르미_응답_timeout으로_회수된_오퍼는_재매칭시_같은_드리미에게_다시_제안된다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(dreamiId, location);
        matchingService.applyStartMatching(order);

        MatchOffer offer =
                getOrderOfferGroups().get(orderId).offers().getFirst();
        matchingService.applyAcceptByDreami(offer.offerId());

        // when (부르미 응답시간 만료 -> 드리미 본인 잘못이 아니므로 재매칭 후보에 다시 포함되어야 한다)
        matchingService.applyExpireBoormiOffer(offer.offerId());

        // then
        OrderOfferGroup group = getOrderOfferGroups().get(orderId);
        assertThat(group.offers()).hasSize(2);
        assertThat(group.offers().getLast().dreamiId()).isEqualTo(dreamiId);
        assertThat(group.offers().getLast().status()).isEqualTo(MatchOfferStatus.OFFERED);
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.OPEN);
    }

    @Test
    void 부르미_응답_timeout_후_드리미보다_더_오래_기다린_후보가_있으면_해당_드리미는_MATCHING_상태로_남는다() {
        // given (부르미 응답 timeout으로 자유로워질 드리미보다 먼저 등록되어 대기 시간이 더 긴 후보 3명을 준비한다)
        UUID orderId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(dreamiId, location);
        matchingService.applyStartMatching(order);

        MatchOffer offer =
                getOrderOfferGroups().get(orderId).offers().getFirst();
        matchingService.applyAcceptByDreami(offer.offerId());

        UUID olderDreamiId1 = UUID.randomUUID();
        UUID olderDreamiId2 = UUID.randomUUID();
        UUID olderDreamiId3 = UUID.randomUUID();
        matchingService.applyRegisterDreami(olderDreamiId1, location);
        matchingService.applyRegisterDreami(olderDreamiId2, location);
        matchingService.applyRegisterDreami(olderDreamiId3, location);

        // when (부르미 응답시간 만료 -> 드리미는 MATCHING으로 돌아가지만, 대기 시간이 더 긴 다른 3명이 우선 제안받는다)
        matchingService.applyExpireBoormiOffer(offer.offerId());

        // then
        assertThat(getDreamiMap().get(dreamiId).status())
                .isEqualTo(WaitingDreamiStatus.MATCHING);

        OrderOfferGroup group = getOrderOfferGroups().get(orderId);
        assertThat(group.offers())
                .filteredOn(o -> o.status() == MatchOfferStatus.OFFERED)
                .extracting(MatchOffer::dreamiId)
                .containsExactlyInAnyOrder(olderDreamiId1, olderDreamiId2, olderDreamiId3);
    }

    @Test
    void 이미_수락된_오퍼에_드리미_timeout이_뒤늦게_도착해도_상태가_바뀌지_않는다() {
        // given
        UUID orderId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyStartMatching(order);

        MatchOffer offer =
                getOrderOfferGroups().get(orderId).offers().getFirst();
        matchingService.applyAcceptByDreami(offer.offerId());

        // when (드리미 응답 timeout이 이미 수락된 뒤 뒤늦게 도착)
        matchingService.applyExpireDreamiOffer(offer.offerId());

        // then (OFFERED 상태가 아니므로 무시되어야 한다)
        assertThat(offer.status()).isEqualTo(MatchOfferStatus.PENDING_BOORMI_CONFIRMATION);
    }

    @Test
    void 이미_확정된_오퍼에_부르미_timeout이_뒤늦게_도착하면_무시한다() {
        // given
        UUID orderId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyStartMatching(order);

        MatchOffer offer =
                getOrderOfferGroups().get(orderId).offers().getFirst();
        matchingService.applyAcceptByDreami(offer.offerId());
        matchingService.applyAcceptByBoormi(offer.offerId());

        // when (부르미 응답 timeout이 이미 MATCHED된 뒤 뒤늦게 도착 - PENDING_BOORMI_CONFIRMATION 상태가 아니므로 조용히 무시되어야 한다)
        Throwable thrown = catchThrowable(() -> matchingService.applyExpireBoormiOffer(offer.offerId()));

        // then
        assertThat(thrown).isNull();
        assertThat(offer.status()).isEqualTo(MatchOfferStatus.MATCHED);
    }

     @Test
    void 이미_만료된_오퍼에_부르미_거절이_뒤늦게_도착하면_무시한다() {
        // given
        UUID orderId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyStartMatching(order);

        MatchOffer offer =
                getOrderOfferGroups().get(orderId).offers().getFirst();
        matchingService.applyAcceptByDreami(offer.offerId());
        matchingService.applyExpireBoormiOffer(offer.offerId());

        // when (부르미 거절이 이미 BOORMI_EXPIRED된 뒤 뒤늦게 도착 - 조용히 무시되어야 한다)
        Throwable thrown = catchThrowable(() -> matchingService.applyRejectByBoormi(offer.offerId()));

        // then
        assertThat(thrown).isNull();
        assertThat(offer.status()).isEqualTo(MatchOfferStatus.BOORMI_EXPIRED);
    }

    @Test
    void 매칭이_완료되어_정리된_그룹은_스케줄된_재매칭_스캔으로_되살아나지_않는다() {
        // given
        UUID orderId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyStartMatching(order);
        MatchOffer offer =
                getOrderOfferGroups().get(orderId).offers().getFirst();
        matchingService.applyAcceptByDreami(offer.offerId());
        matchingService.applyAcceptByBoormi(offer.offerId());

        assertThat(getOrderOfferGroups()).doesNotContainKey(orderId);

        // when
        Throwable thrown = catchThrowable(() -> matchingService.applyRematchWaitingGroups());

        // then
        assertThat(thrown).isNull();
        assertThat(getOrderOfferGroups()).doesNotContainKey(orderId);
    }

    @Test
    void 주문_취소로_CLOSED된_그룹은_스케줄된_재매칭_스캔으로_다시_열리지_않는다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(dreamiId, location);
        matchingService.applyStartMatching(order);
        matchingService.applyCancelOrderByBoormi(orderId);

        OrderOfferGroup group = getOrderOfferGroups().get(orderId);
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.CLOSED);
        assertThat(group.rematchRequired()).isFalse();
        int offersBeforeScan = group.offers().size();

        // when (fallback 스케줄 재매칭 실행)
        matchingService.applyRematchWaitingGroups();

        // then (rematchRequired가 false이므로 취소된 그룹은 스캔 대상에서 제외되어야 한다)
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.CLOSED);
        assertThat(group.offers()).hasSize(offersBeforeScan);
    }

    @Test
    void 주문_시작하면_top3_드리미에게_각각_OFFER_POPUP을_보낸다() {
        // given
        UUID orderId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        UUID dreamiId1 = UUID.randomUUID();
        UUID dreamiId2 = UUID.randomUUID();
        UUID dreamiId3 = UUID.randomUUID();
        UUID dreamiId4 = UUID.randomUUID();
        matchingService.applyRegisterDreami(dreamiId1, location);
        matchingService.applyRegisterDreami(dreamiId2, location);
        matchingService.applyRegisterDreami(dreamiId3, location);
        matchingService.applyRegisterDreami(dreamiId4, location);

        // when
        matchingService.applyStartMatching(order);

        // then
        ArgumentCaptor<UUID> target = ArgumentCaptor.forClass(UUID.class);
        verify(sseService, times(3))
                .send(target.capture(), eq(MatchingEventType.OFFER_POPUP), any());
        assertThat(target.getAllValues())
                .containsExactlyInAnyOrder(dreamiId1, dreamiId2, dreamiId3);
    }

    @Test
    void 드리미가_수락하면_주문_부르미에게_DREAMI_INFO를_보낸다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID boormiId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);
        when(order.getBoormiId()).thenReturn(boormiId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyStartMatching(order);
        MatchOffer offer =
                getOrderOfferGroups().get(orderId).offers().getFirst();

        // when
        matchingService.applyAcceptByDreami(offer.offerId());

        // then
        verify(sseService).send(eq(boormiId), eq(MatchingEventType.DREAMI_INFO), any());
    }

    @Test
    void 드리미가_수락하면_선착순_패배자에게_OFFER_CLOSED를_보낸다() {
        // given
        UUID orderId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyStartMatching(order);
        List<MatchOffer> offers =
                getOrderOfferGroups().get(orderId).offers();
        MatchOffer accepted = offers.getFirst();
        MatchOffer loser = offers.get(1);

        // when
        matchingService.applyAcceptByDreami(accepted.offerId());

        // then
        verify(sseService).send(eq(loser.dreamiId()), eq(MatchingEventType.OFFER_CLOSED), any());
    }

    @Test
    void 부르미가_거절하면_드리미에게_BOORMI_REJECTED를_보낸다() {
        // given
        UUID orderId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyStartMatching(order);
        MatchOffer offer =
                getOrderOfferGroups().get(orderId).offers().getFirst();
        matchingService.applyAcceptByDreami(offer.offerId());

        // when
        matchingService.applyRejectByBoormi(offer.offerId());

        // then
        verify(sseService).send(eq(offer.dreamiId()), eq(MatchingEventType.BOORMI_REJECTED), any());
    }

    @Test
    void 모든_드리미가_거절한_뒤_새_드리미가_등록되면_그_드리미에게_오퍼된다() {
        // given
        UUID orderId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyStartMatching(order);

        for (MatchOffer offer : getOrderOfferGroups().get(orderId).offers()) {
            matchingService.applyRejectByDreami(offer.offerId());
        }
        assertThat(getOrderOfferGroups().get(orderId).status())
                .isEqualTo(OrderOfferGroupStatus.CLOSED);

        UUID newDreamiId = UUID.randomUUID();

        // when
        matchingService.applyRegisterDreami(newDreamiId, location);

        // then
        OrderOfferGroup group = getOrderOfferGroups().get(orderId);
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.OPEN);
        assertThat(group.offers())
                .filteredOn(offer -> offer.status() == MatchOfferStatus.OFFERED)
                .extracting(MatchOffer::dreamiId)
                .containsExactly(newDreamiId);
        assertThat(getDreamiMap().get(newDreamiId).status())
                .isEqualTo(WaitingDreamiStatus.PROPOSED);
        verify(sseService).send(eq(newDreamiId), eq(MatchingEventType.OFFER_POPUP), any());
    }

    @Test
    void 오퍼가_소진되면_아직_제안받지_않은_대기_드리미에게_즉시_재오퍼된다() {
        // given (드리미 5명 중 3명만 오퍼받고 2명은 대기로 남는다)
        UUID orderId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        for (int i = 0; i < 5; i++) {
            matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        }
        matchingService.applyStartMatching(order);

        List<UUID> firstRoundDreamis = getOrderOfferGroups().get(orderId).offers().stream()
                .map(MatchOffer::dreamiId)
                .toList();

        // when (첫 라운드 3명 전원 거절 → 소진 즉시 재오퍼)
        for (MatchOffer offer : getOrderOfferGroups().get(orderId).offers()) {
            matchingService.applyRejectByDreami(offer.offerId());
        }

        // then
        OrderOfferGroup group = getOrderOfferGroups().get(orderId);
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.OPEN);

        List<UUID> liveOfferDreamis = group.offers().stream()
                .filter(offer -> offer.status() == MatchOfferStatus.OFFERED)
                .map(MatchOffer::dreamiId)
                .toList();

        assertThat(liveOfferDreamis).hasSize(2);
        assertThat(liveOfferDreamis).doesNotContainAnyElementsOf(firstRoundDreamis);
    }

    @Test
    void 재오퍼는_이미_거절한_드리미를_제외한다() {
        // given
        UUID orderId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(orderId);

        matchingService.applyRegisterDreami(UUID.randomUUID(), location);
        matchingService.applyStartMatching(order);

        MatchOffer firstOffer =
                getOrderOfferGroups().get(orderId).offers().getFirst();
        UUID rejectedDreamiId = firstOffer.dreamiId();
        matchingService.applyRejectByDreami(firstOffer.offerId());

        // 거절자는 다시 MATCHING 상태로 돌아오지만, 재오퍼 대상에서는 제외되어야 한다.
        assertThat(getDreamiMap().get(rejectedDreamiId).status())
                .isEqualTo(WaitingDreamiStatus.MATCHING);

        // when (다른 대기 드리미가 없으므로 재매칭 대기가 유지되어야 한다)
        OrderOfferGroup group = getOrderOfferGroups().get(orderId);

        // then
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.CLOSED);
        assertThat(group.rematchRequired()).isTrue();
        assertThat(group.offers())
                .noneMatch(offer -> offer.status() == MatchOfferStatus.OFFERED);
    }

    @Test
    void 존재하지_않는_주문을_취소하면_아무_일도_일어나지_않는다() {
        // given
        UUID orderId = UUID.randomUUID();

        // when
        matchingService.applyCancelOrderByBoormi(orderId);

        // then
        assertThat(getOrderOfferGroups()).doesNotContainKey(orderId);
        assertThat(getDreamiMap()).isEmpty();
    }

    @Test
    void OPEN이_아닌_그룹을_취소해도_상태가_그대로_보존된다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();

        MatchOffer offer = new MatchOffer(
                UUID.randomUUID(), orderId, dreamiId,
                MatchOfferStatus.OFFERED);
        OrderOfferGroup group =
                new OrderOfferGroup(orderId, UUID.randomUUID(), mock(GeoPoint.class), List.of(offer));
        group.closeForRematch();
        getOrderOfferGroups().put(orderId, group);
        getDreamiMap().put(dreamiId, new WaitingDreami(
                dreamiId, mock(GeoPoint.class),
                WaitingDreamiStatus.MATCHING, LocalDateTime.now()));

        // when
        matchingService.applyCancelOrderByBoormi(orderId);

        // then (기존 상태가 그대로 보존되어야 한다)
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.CLOSED);
        assertThat(group.rematchRequired()).isTrue();
        assertThat(offer.status()).isEqualTo(MatchOfferStatus.OFFERED);
        assertThat(getDreamiMap().get(dreamiId).status())
                .isEqualTo(WaitingDreamiStatus.MATCHING);
    }

    @Test
    void 모든_오퍼가_OFFERED인_상태에서_취소하면_WITHDRAWN되고_드리미는_MATCHING으로_복귀한다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID dreamiIdA = UUID.randomUUID();
        UUID dreamiIdB = UUID.randomUUID();
        UUID dreamiIdC = UUID.randomUUID();

        MatchOffer offerA = new MatchOffer(
                UUID.randomUUID(), orderId, dreamiIdA,
                MatchOfferStatus.OFFERED);
        MatchOffer offerB = new MatchOffer(
                UUID.randomUUID(), orderId, dreamiIdB,
                MatchOfferStatus.OFFERED);
        MatchOffer offerC = new MatchOffer(
                UUID.randomUUID(), orderId, dreamiIdC,
                MatchOfferStatus.OFFERED);
        OrderOfferGroup group = new OrderOfferGroup(
                orderId, UUID.randomUUID(), mock(GeoPoint.class), List.of(offerA, offerB, offerC));
        getOrderOfferGroups().put(orderId, group);
        for (UUID dreamiId : List.of(dreamiIdA, dreamiIdB, dreamiIdC)) {
            getDreamiMap().put(dreamiId, new WaitingDreami(
                    dreamiId, mock(GeoPoint.class),
                    WaitingDreamiStatus.PROPOSED, LocalDateTime.now()));
        }

        // when
        matchingService.applyCancelOrderByBoormi(orderId);

        // then
        assertThat(List.of(offerA, offerB, offerC))
                .allMatch(offer -> offer.status() == MatchOfferStatus.WITHDRAWN);
        assertThat(List.of(dreamiIdA, dreamiIdB, dreamiIdC))
                .allMatch(dreamiId -> getDreamiMap().get(dreamiId).status()
                        == WaitingDreamiStatus.MATCHING);
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.CLOSED);
        assertThat(group.rematchRequired()).isFalse();
    }

    @Test
    void 한명이_수락한_상태에서_부르미가_취소하면_수락자는_BOORMI_REJECTED로_나머지는_WITHDRAWN된다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID dreamiIdA = UUID.randomUUID();
        UUID dreamiIdB = UUID.randomUUID();
        UUID dreamiIdC = UUID.randomUUID();

        MatchOffer offerA = new MatchOffer(
                UUID.randomUUID(), orderId, dreamiIdA,
                MatchOfferStatus.PENDING_BOORMI_CONFIRMATION);
        MatchOffer offerB = new MatchOffer(
                UUID.randomUUID(), orderId, dreamiIdB,
                MatchOfferStatus.OFFERED);
        MatchOffer offerC = new MatchOffer(
                UUID.randomUUID(), orderId, dreamiIdC,
                MatchOfferStatus.OFFERED);
        OrderOfferGroup group = new OrderOfferGroup(
                orderId, UUID.randomUUID(), mock(GeoPoint.class), List.of(offerA, offerB, offerC));
        getOrderOfferGroups().put(orderId, group);
        for (UUID dreamiId : List.of(dreamiIdA, dreamiIdB, dreamiIdC)) {
            getDreamiMap().put(dreamiId, new WaitingDreami(
                    dreamiId, mock(GeoPoint.class),
                    WaitingDreamiStatus.PROPOSED, LocalDateTime.now()));
        }

        // when
        matchingService.applyCancelOrderByBoormi(orderId);

        // then
        assertThat(offerA.status()).isEqualTo(MatchOfferStatus.BOORMI_REJECTED);
        assertThat(offerB.status()).isEqualTo(MatchOfferStatus.WITHDRAWN);
        assertThat(offerC.status()).isEqualTo(MatchOfferStatus.WITHDRAWN);
        assertThat(List.of(dreamiIdA, dreamiIdB, dreamiIdC))
                .allMatch(dreamiId -> getDreamiMap().get(dreamiId).status()
                        == WaitingDreamiStatus.MATCHING);
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.CLOSED);
        assertThat(group.rematchRequired()).isFalse();
    }

    @Test
    void 이미_종료된_오퍼가_섞여있으면_해당_오퍼는_그대로_유지된다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID dreamiIdA = UUID.randomUUID();
        UUID dreamiIdB = UUID.randomUUID();
        UUID dreamiIdC = UUID.randomUUID();

        MatchOffer offerA = new MatchOffer(
                UUID.randomUUID(), orderId, dreamiIdA,
                MatchOfferStatus.PENDING_BOORMI_CONFIRMATION);
        MatchOffer offerB = new MatchOffer(
                UUID.randomUUID(), orderId, dreamiIdB,
                MatchOfferStatus.DREAMI_REJECTED);
        MatchOffer offerC = new MatchOffer(
                UUID.randomUUID(), orderId, dreamiIdC,
                MatchOfferStatus.DREAMI_EXPIRED);
        OrderOfferGroup group = new OrderOfferGroup(
                orderId, UUID.randomUUID(), mock(GeoPoint.class), List.of(offerA, offerB, offerC));
        getOrderOfferGroups().put(orderId, group);

        // when
        matchingService.applyCancelOrderByBoormi(orderId);

        // then (처리 대상은 OFFERED/PENDING_BOORMI_CONFIRMATION 뿐이다)
        assertThat(offerA.status()).isEqualTo(MatchOfferStatus.BOORMI_REJECTED);
        assertThat(offerB.status()).isEqualTo(MatchOfferStatus.DREAMI_REJECTED);
        assertThat(offerC.status()).isEqualTo(MatchOfferStatus.DREAMI_EXPIRED);
    }

    @Test
    void 같은_주문을_두번_취소해도_두번째_호출은_아무_영향이_없다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();

        MatchOffer offer = new MatchOffer(
                UUID.randomUUID(), orderId, dreamiId,
                MatchOfferStatus.OFFERED);
        OrderOfferGroup group = new OrderOfferGroup(
                orderId, UUID.randomUUID(), mock(GeoPoint.class), List.of(offer));
        getOrderOfferGroups().put(orderId, group);
        getDreamiMap().put(dreamiId, new WaitingDreami(
                dreamiId, mock(GeoPoint.class),
                WaitingDreamiStatus.PROPOSED, LocalDateTime.now()));

        // when
        matchingService.applyCancelOrderByBoormi(orderId);
        matchingService.applyCancelOrderByBoormi(orderId);

        // then
        assertThat(offer.status()).isEqualTo(MatchOfferStatus.WITHDRAWN);
        assertThat(getDreamiMap().get(dreamiId).status())
                .isEqualTo(WaitingDreamiStatus.MATCHING);
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.CLOSED);
        assertThat(group.rematchRequired()).isFalse();
    }

    @Test
    void 부르미가_진행중인_방을_취소하면_엔진_큐에_CancelOrderByBoormi_액션이_제출된다() {
        // given
        UUID orderId = UUID.randomUUID();
        OrderOfferGroup group =
                new OrderOfferGroup(orderId, UUID.randomUUID(), mock(GeoPoint.class), List.of());
        getOrderOfferGroups().put(orderId, group);
        when(matchingEngine.submit(any())).thenReturn(true);

        // when
        boolean result = matchingService.cancelOrderByBoormi(orderId);

        // then
        assertThat(result).isTrue();
        ArgumentCaptor<Action> captor = ArgumentCaptor.forClass(Action.class);
        verify(matchingEngine).submit(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(CancelOrderByBoormi.class);
        assertThat(((CancelOrderByBoormi) captor.getValue()).orderId()).isEqualTo(orderId);
    }

    @Test
    void 취소할_진행중인_방이_없으면_큐에_제출되지_않고_false를_반환한다() {
        // given
        UUID orderId = UUID.randomUUID();

        // when
        boolean result = matchingService.cancelOrderByBoormi(orderId);

        // then
        assertThat(result).isFalse();
        verify(matchingEngine, never()).submit(any());
    }

    @Test
    void 부르미_확정_이벤트를_받으면_엔진_큐에_AcceptByBoormi_액션이_제출된다() {
        // given
        UUID offerId = UUID.randomUUID();

        // when
        matchingService.onBoormiConfirmed(new BoormiConfirmedEvent(offerId));

        // then
        ArgumentCaptor<Action> captor = ArgumentCaptor.forClass(Action.class);
        verify(matchingEngine).submit(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(AcceptByBoormi.class);
        assertThat(((AcceptByBoormi) captor.getValue()).offerId()).isEqualTo(offerId);
    }

    @Test
    void 매칭시작_요청_이벤트를_받으면_엔진_큐에_StartMatching_액션이_제출된다() {
        // given
        Orders order = mock(Orders.class);
        when(order.getOrderId()).thenReturn(UUID.randomUUID());

        // when
        matchingService.onMatchingStartRequested(new MatchingStartRequestedEvent(order));

        // then
        ArgumentCaptor<Action> captor = ArgumentCaptor.forClass(Action.class);
        verify(matchingEngine).submit(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(StartMatching.class);
        assertThat(((StartMatching) captor.getValue()).order()).isSameAs(order);
    }

    @Test
    void 부르미_주문취소_이벤트를_받으면_엔진_큐에_CancelOrderByBoormi_액션이_제출된다() {
        // given
        UUID orderId = UUID.randomUUID();
        OrderOfferGroup group =
                new OrderOfferGroup(orderId, UUID.randomUUID(), mock(GeoPoint.class), List.of());
        getOrderOfferGroups().put(orderId, group);

        // when
        matchingService.onOrderCancelledByBoormi(new OrderCancelledByBoormiEvent(orderId));

        // then
        ArgumentCaptor<Action> captor = ArgumentCaptor.forClass(Action.class);
        verify(matchingEngine).submit(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(CancelOrderByBoormi.class);
        assertThat(((CancelOrderByBoormi) captor.getValue()).orderId()).isEqualTo(orderId);
    }

    @Test
    void 드리미_수락_이벤트를_받으면_엔진_큐에_AcceptByDreami_액션이_제출된다() {
        // given
        UUID offerId = UUID.randomUUID();

        // when
        matchingService.onDreamiAccepted(new DreamiAcceptedEvent(offerId));

        // then
        ArgumentCaptor<Action> captor = ArgumentCaptor.forClass(Action.class);
        verify(matchingEngine).submit(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(AcceptByDreami.class);
        assertThat(((AcceptByDreami) captor.getValue()).offerId()).isEqualTo(offerId);
    }

    @Test
    void 부르미_거절_이벤트를_받으면_엔진_큐에_RejectByBoormi_액션이_제출된다() {
        // given
        UUID offerId = UUID.randomUUID();

        // when
        matchingService.onBoormiRejectedDreami(new BoormiRejectedDreamiEvent(offerId));

        // then
        ArgumentCaptor<Action> captor = ArgumentCaptor.forClass(Action.class);
        verify(matchingEngine).submit(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(RejectByBoormi.class);
        assertThat(((RejectByBoormi) captor.getValue()).offerId()).isEqualTo(offerId);
    }

    @Test
    void 스케줄된_재매칭_트리거는_엔진_큐에_RematchWaitingGroups_액션을_제출한다() {
        // when
        matchingService.scheduleRematchWaitingGroups();

        // then
        ArgumentCaptor<Action> captor = ArgumentCaptor.forClass(Action.class);
        verify(matchingEngine).submit(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(RematchWaitingGroups.class);
    }

    @Test
    void 재매칭_대상_그룹이_있으면_스케줄된_재매칭_실행시_대기중인_드리미에게_오퍼가_간다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();
        UUID boormiId = UUID.randomUUID();
        GeoPoint location = mock(GeoPoint.class);

        matchingService.applyRegisterDreami(dreamiId, location);

        OrderOfferGroup group =
                new OrderOfferGroup(orderId, boormiId, mock(GeoPoint.class), List.of());
        group.closeForRematch();
        getOrderOfferGroups().put(orderId, group);

        // when
        matchingService.applyRematchWaitingGroups();

        // then
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.OPEN);
        assertThat(group.rematchRequired()).isFalse();
        assertThat(group.offers())
                .extracting(MatchOffer::dreamiId)
                .containsExactly(dreamiId);
        verify(sseService).send(eq(dreamiId), eq(MatchingEventType.OFFER_POPUP), any());
    }

    @Test
    void 재매칭_대상_그룹이_없으면_스케줄된_재매칭_실행시_아무일도_일어나지_않는다() {
        // given
        UUID orderId = UUID.randomUUID();
        UUID boormiId = UUID.randomUUID();

        OrderOfferGroup group =
                new OrderOfferGroup(orderId, boormiId, mock(GeoPoint.class), List.of());
        getOrderOfferGroups().put(orderId, group);

        // when
        matchingService.applyRematchWaitingGroups();

        // then (대기 대상이 아니므로 상태가 그대로 보존된다)
        assertThat(group.status()).isEqualTo(OrderOfferGroupStatus.OPEN);
    }

    @Test
    void 제안의_대상_드리미와_요청한_드리미가_같으면_isDreamiOfferOwner는_true를_반환한다() {
        UUID offerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();
        MatchOffer offer = new MatchOffer(
                offerId, orderId, dreamiId, MatchOfferStatus.OFFERED);
        getOffersById().put(offerId, offer);

        assertThat(matchingService.isDreamiOfferOwner(offerId, dreamiId)).isTrue();
    }

    @Test
    void 제안의_대상_드리미와_요청한_드리미가_다르면_isDreamiOfferOwner는_false를_반환한다() {
        UUID offerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();
        MatchOffer offer = new MatchOffer(
                offerId, orderId, dreamiId, MatchOfferStatus.OFFERED);
        getOffersById().put(offerId, offer);

        assertThat(matchingService.isDreamiOfferOwner(offerId, UUID.randomUUID())).isFalse();
    }

    @Test
    void 존재하지_않는_제안이면_isDreamiOfferOwner는_false를_반환한다() {
        assertThat(matchingService.isDreamiOfferOwner(UUID.randomUUID(), UUID.randomUUID())).isFalse();
    }

    @Test
    void 제안이_속한_주문의_부르미와_요청한_부르미가_같으면_isBoormiOfferOwner는_true를_반환한다() {
        UUID offerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID boormiId = UUID.randomUUID();
        MatchOffer offer = new MatchOffer(
                offerId, orderId, UUID.randomUUID(), MatchOfferStatus.OFFERED);
        getOffersById().put(offerId, offer);
        getOrderOfferGroups().put(orderId,
                new OrderOfferGroup(orderId, boormiId, mock(GeoPoint.class), List.of(offer)));

        assertThat(matchingService.isBoormiOfferOwner(offerId, boormiId)).isTrue();
    }

    @Test
    void 제안이_속한_주문의_부르미와_요청한_부르미가_다르면_isBoormiOfferOwner는_false를_반환한다() {
        UUID offerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID boormiId = UUID.randomUUID();
        MatchOffer offer = new MatchOffer(
                offerId, orderId, UUID.randomUUID(), MatchOfferStatus.OFFERED);
        getOffersById().put(offerId, offer);
        getOrderOfferGroups().put(orderId,
                new OrderOfferGroup(orderId, boormiId, mock(GeoPoint.class), List.of(offer)));

        assertThat(matchingService.isBoormiOfferOwner(offerId, UUID.randomUUID())).isFalse();
    }

    @Test
    void 존재하지_않는_제안이면_isBoormiOfferOwner는_false를_반환한다() {
        assertThat(matchingService.isBoormiOfferOwner(UUID.randomUUID(), UUID.randomUUID())).isFalse();
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, MatchOffer> getOffersById() {
        return (Map<UUID, MatchOffer>)
                ReflectionTestUtils.getField(
                        matchingService,
                        "offersById"
                );
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, OrderOfferGroup> getOrderOfferGroups() {
        return (Map<UUID, OrderOfferGroup>)
                ReflectionTestUtils.getField(
                        matchingService,
                        "orderOfferGroupsByOrderId"
                );
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, WaitingDreami> getDreamiMap() {
        return (Map<UUID, WaitingDreami>)
                ReflectionTestUtils.getField(
                        matchingService,
                        "dreamiMap"
                );
    }
}
