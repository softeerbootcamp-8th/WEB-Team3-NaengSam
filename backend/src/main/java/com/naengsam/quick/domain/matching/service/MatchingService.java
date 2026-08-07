package com.naengsam.quick.domain.matching.service;

import com.naengsam.quick.domain.delivery.service.DeliveryService;
import com.naengsam.quick.domain.matching.dto.GeoPoint;
import com.naengsam.quick.domain.matching.event.BoormiConfirmedEvent;
import com.naengsam.quick.domain.matching.event.BoormiRejectedDreamiEvent;
import com.naengsam.quick.domain.matching.event.BoormiRejectedPayload;
import com.naengsam.quick.domain.matching.event.DreamiAcceptedEvent;
import com.naengsam.quick.domain.matching.event.DreamiInfoPayload;
import com.naengsam.quick.domain.matching.event.MatchingEventType;
import com.naengsam.quick.domain.matching.event.MatchingStartRequestedEvent;
import com.naengsam.quick.domain.matching.event.NotificationErrorPayload;
import com.naengsam.quick.domain.matching.event.OfferClosedPayload;
import com.naengsam.quick.domain.matching.event.OfferPopupPayload;
import com.naengsam.quick.domain.matching.event.OrderCancelledByBoormiEvent;
import com.naengsam.quick.domain.matching.model.MatchOffer;
import com.naengsam.quick.domain.matching.model.MatchOfferStatus;
import com.naengsam.quick.domain.matching.model.OrderOfferGroup;
import com.naengsam.quick.domain.matching.model.OrderOfferGroupStatus;
import com.naengsam.quick.domain.matching.model.WaitingDreami;
import com.naengsam.quick.domain.matching.model.WaitingDreamiStatus;
import com.naengsam.quick.domain.matching.model.WaitingOrder;
import com.naengsam.quick.domain.order.entity.Orders;
import com.naengsam.quick.global.sse.SseService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 부르미 - 드리미 매칭 로직 스켈레톤. 로직 자체는 원본 그대로 두고, 컴파일/자료구조/네이밍 일관성만 보정한 버전.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingService {

    /**
     * 드리미 응답 제한시간. 제한은 30초 기준.
     */
    private static final Duration OFFER_TTL = Duration.ofSeconds(30);
    /**
     * 부르미 응답 제한시간. 제한은 30초 기준.
     */
    private static final Duration BOORMI_OFFER_TTL = Duration.ofSeconds(30);
    /**
     * 한 주문에 동시에 제안할 최대 드리미 수
     */
    private static final int MAX_OFFER_COUNT = 3;
    /**
     * 재매칭 대기 방을 스캔하는 스케줄 주기. Fallback이기 때문에 10분으로.
     */
    private static final Duration REMATCH_SCAN_INTERVAL = Duration.ofMinutes(10);

    // ────────────────────────────── 도메인 타입 ──────────────────────────────
    // 모든 mutation은 엔진 스레드 하나에서만 일어나지만, 조회(findOrderOfferGroup 등)는 호출 스레드에서 직접 일어난다.
    // 맵 자체의 내부 구조 변경(put에 의한 리사이즈 등)이 다른 스레드의 읽기와 겹치면 HashMap은 안전하지 않으므로
    // 단일 기록자/다중 판독자 상황에서도 안전한 ConcurrentHashMap을 쓴다.
    private final Map<UUID, MatchOffer> offersById = new ConcurrentHashMap<>();           // Map<OfferUUID, MatchOffer>
    private final Map<UUID, Set<UUID>> offerIdsByDreamiId = new ConcurrentHashMap<>();    // Map<DreamiUUID, Set<OfferUUID>>

    // 하나의 주문에 대해 동시에 뿌린 제안 묶음 = "방"
    private final Map<UUID, OrderOfferGroup> orderOfferGroupsByOrderId = new ConcurrentHashMap<>();

    // ────────────────────────────── 저장소 ──────────────────────────────
    private final Map<UUID, WaitingDreami> dreamiMap = new ConcurrentHashMap<>();
    private final MatchingEngine matchingEngine;
    private final SseService sseService;
    private final OfferTimeoutScheduler offerTimeoutScheduler;
    private final DeliveryService deliveryService;

    public List<WaitingDreami> waitingDreamis() {
        return List.copyOf(dreamiMap.values());
    }

    /**
     * 매칭 시작 후(OPEN) 아직 확정되지 않은, 대기 중인 주문 목록을 조회한다. 한 부르미가 여러 주문을 동시에 가질 수 있으므로 부르미 단위가 아니라 주문 단위로 도출한다. 별도 등록 큐 없이
     * {@link #startMatching}/{@link #cancelOrderByBoormi}로만 대기 상태가 결정되므로, 진행 중인 {@link OrderOfferGroup}에서 직접 도출한다.
     */
    public List<WaitingOrder> waitingOrders() {
        return orderOfferGroupsByOrderId.values().stream()
                .filter(group -> group.status() == OrderOfferGroupStatus.OPEN)
                .map(group -> new WaitingOrder(group.orderId(), group.location()))
                .toList();
    }

    // ────────────────────────────── 외부 API ──────────────────────────────
    // 외부에서는 이 메서드로 액션을 큐에 넣기만 한다. 실제 상태 변경은 엔진 스레드에서 apply*가 수행한다.

    /**
     * 드리미를 대기열에 등록한다. 호출 스레드에서 곧바로 확인 가능한 중복 등록만 빠르게 걸러내며, 이미 등록되어 있는 드리미면 큐에 넣지 않고 false를 반환하면서 실패 사유를 SSE로 알린다. 실제
     * 등록은 엔진 스레드에서 순차 처리된다.
     *
     * @param dreamiId 등록할 드리미 UUID
     * @param location 드리미의 현재 위치
     * @return 드리미 등록 액션이 큐에 제출되었으면 true, 이미 등록되어 있거나 큐 제출에 실패했을 경우 false
     */
    public boolean registerDreami(UUID dreamiId, GeoPoint location) {
        if (dreamiMap.containsKey(dreamiId)) {
            sseService.send(dreamiId, MatchingEventType.OFFER_ERROR,
                    new NotificationErrorPayload("이미 등록된 드리미입니다."));
            return false;
        }
        return matchingEngine.submit(new DreamiRegister(this, dreamiId, location));
    }

    /**
     * 드리미 등록을 해제한다. 호출 스레드에서 곧바로 확인 가능한 존재 여부만 빠르게 걸러내며, 등록되어 있지 않은 드리미면 큐에 넣지 않고 false를 반환하면서 실패 사유를 SSE로 알린다. 실제 제거는
     * 엔진 스레드에서 순차 처리된다.
     *
     * @param dreamiId 제거할 드리미 UUID
     * @return 드리미 제거 액션이 큐에 제출되었으면 true, 등록되어 있지 않거나 큐 제출에 실패했을 경우 false
     */
    public boolean removeDreami(UUID dreamiId) {
        if (!dreamiMap.containsKey(dreamiId)) {
            sseService.send(dreamiId, MatchingEventType.OFFER_ERROR,
                    new NotificationErrorPayload("등록되지 않은 드리미입니다."));
            return false;
        }
        return matchingEngine.submit(new DreamiRemove(this, dreamiId));
    }

    /**
     * 매칭 시작을 요청한다. 호출 스레드에서 곧바로 확인 가능한 중복 시작만 빠르게 걸러내며, 이미 진행 중인 방이 있으면 큐에 넣지 않고 false를 반환한다. 실제 방 생성은 엔진 스레드에서 순차
     * 처리되며, 그 결과는 {@link #findOrderOfferGroup(UUID)}로 별도 조회한다.
     *
     * @param order 매칭을 시작할 주문
     * @return 매칭 시작 액션이 큐에 제출되었으면 true, 이미 진행 중인 방이 있거나 큐 제출에 실패했을 경우 false
     */
    public boolean startMatching(Orders order) {
        if (isOpenGroupExists(order.getOrderId())) {
            return false;
        }
        return matchingEngine.submit(new StartMatching(this, order));
    }

    /**
     * 주문 접수 트랜잭션이 커밋된 뒤에만 매칭엔진에 매칭 시작을 제출한다. 커밋 전에 제출하면 엔진이 오퍼 팝업을 먼저 보내, 드리미가 아직 저장되지 않은 주문을 수락하려 할 수 있다.
     * 롤백된 접수는 이벤트가 폐기되어 엔진까지 가지 않는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMatchingStartRequested(MatchingStartRequestedEvent event) {
        startMatching(event.order());
    }

    /**
     * 부르미가 매칭 진행 중인 주문을 직접 취소한다. 호출 스레드에서 곧바로 확인 가능한 취소 가능 여부(진행 중인 방이 있는지)만 빠르게 걸러내며, 취소할 방이 없거나 이미 종료된 방이면 큐에 넣지 않고
     * false를 반환한다. 방이 존재했다면 실패 사유를 부르미에게 SSE로 알린다. 실제 취소는 엔진 스레드에서 순차 처리된다.
     *
     * @param orderId 취소할 주문 UUID
     * @return 주문 취소 액션이 큐에 제출되었으면 true, 취소 가능한 진행 중인 방이 없거나 큐 제출에 실패했을 경우 false
     */
    public boolean cancelOrderByBoormi(UUID orderId) {
        OrderOfferGroup group = orderOfferGroupsByOrderId.get(orderId);
        if (group == null || group.status() != OrderOfferGroupStatus.OPEN) {
            if (group != null) {
                sseService.send(group.boormiId(), MatchingEventType.OFFER_ERROR,
                        new NotificationErrorPayload("이미 종료된 주문입니다."));
            }
            return false;
        }
        return matchingEngine.submit(new CancelOrderByBoormi(this, orderId));
    }

    /**
     * 주문 취소 트랜잭션이 커밋된 뒤에만 매칭엔진에 제안 회수를 제출한다. 커밋 전에 제출하면 취소가 롤백돼도 인메모리 방은 이미 종료된 채로 남아, 주문이 영영 재매칭되지 않는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCancelledByBoormi(OrderCancelledByBoormiEvent event) {
        cancelOrderByBoormi(event.orderId());
    }

    /**
     * 드리미가 제안(팝업)을 수락한다. 큐 제출 전에는 유효성을 검사하지 않으며, 이미 종료/회수된 제안이거나 존재하지 않는 제안이면 엔진 스레드에서 실패를 판단해 SSE로 알린다. 수락이 확정되면 나머지
     * 오퍼는 회수(WITHDRAWN)되고 부르미에게 확인 팝업이 전달된다.
     *
     * @param offerId 수락할 제안 UUID
     */
    public void acceptByDreami(UUID offerId) {
        matchingEngine.submit(new AcceptByDreami(this, offerId));
    }

    /**
     * 드리미 수락 트랜잭션이 커밋된 뒤에만 매칭엔진에 수락을 제출한다. 엔진은 곧바로 부르미에게 확인 팝업을 보내는데, 부르미의 확정은 주문이
     * PENDING_BOORMI_CONFIRMATION 인지 검사하므로 커밋 전에 제출하면 그 확정이 실패한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDreamiAccepted(DreamiAcceptedEvent event) {
        acceptByDreami(event.offerId());
    }

    /**
     * 드리미가 제안(팝업)을 거절한다. 거절한 드리미는 다시 매칭 대기(MATCHING) 상태로 돌아가고, 방에 더 이상 진행 중인 오퍼가 없으면 즉시 재오퍼를 시도한다.
     *
     * @param offerId 거절할 제안 UUID
     */
    public void rejectByDreami(UUID offerId) {
        matchingEngine.submit(new RejectByDreami(this, offerId));
    }

    /**
     * 부르미가 드리미의 수락을 최종 승인한다. 승인되면 해당 오퍼는 확정(MATCHED)되고 방도 매칭 완료 상태가 된다.
     *
     * @param offerId 승인할 제안 UUID
     */
    public void acceptByBoormi(UUID offerId) {
        matchingEngine.submit(new AcceptByBoormi(this, offerId));
    }

    /**
     * 부르미 확정 트랜잭션이 커밋된 뒤에만 매칭엔진에 수락을 제출한다. 엔진 스레드는 별도 트랜잭션으로 주문을 다시 읽어 배달을 시작하므로, 커밋 전에 제출하면 아직
     * IN_PROGRESS 가 아닌 주문을 보고 배달 시작이 실패한다. 롤백된 확정은 이벤트가 폐기되어 엔진까지 가지 않는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBoormiConfirmed(BoormiConfirmedEvent event) {
        acceptByBoormi(event.offerId());
    }

    /**
     * 부르미가 드리미의 수락을 거절한다. 거절당한 드리미는 다시 매칭 대기(MATCHING) 상태로 돌아가고, 방은 재오퍼를 즉시 시도한다.
     *
     * @param offerId 거절할 제안 UUID
     */
    public void rejectByBoormi(UUID offerId) {
        matchingEngine.submit(new RejectByBoormi(this, offerId));
    }

    /**
     * 부르미 거절 트랜잭션이 커밋된 뒤에만 매칭엔진에 거절을 제출한다. 엔진은 곧바로 재오퍼를 돌리므로, 커밋 전에 제출하면 다른 드리미가 먼저 수락해 커밋한
     * PENDING_BOORMI_CONFIRMATION 을 이 트랜잭션의 MATCHING 복귀가 덮어써 주문이 고착된다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBoormiRejectedDreami(BoormiRejectedDreamiEvent event) {
        rejectByBoormi(event.offerId());
    }

    // ────────────────────────────── 내부 구현체 ──────────────────────────────
    void applyRegisterDreami(UUID dreamiId, GeoPoint location) {
        dreamiMap.put(dreamiId,
                new WaitingDreami(dreamiId, location, WaitingDreamiStatus.MATCHING, LocalDateTime.now()));
        log.debug("드리미 등록 처리 완료: dreamiId={}, location={}", dreamiId, location);
        // 재매칭 대기 중인 주문이 있으면 방금 등록된 드리미에게 오퍼를 시도한다.
        retryRematchWaitingGroups();
    }

    void applyRemoveDreami(UUID dreamiId) {
        dreamiMap.remove(dreamiId);
        log.debug("드리미 제거 처리 완료: dreamiId={}", dreamiId);
    }

    /**
     * 드리미 등록 없이도 재매칭 대기 방이 방치되지 않도록, 주기적으로 재매칭을 시도한다. 엔진의 단일 기록자 스레드가 아닌 스케줄러 스레드에서 실행되므로, 상태를 직접 건드리지 않고 다른 액션들과 동일하게
     * 큐에 제출만 한다.
     */
    @Scheduled(fixedRate = 600_000L) // REMATCH_SCAN_INTERVAL과 동일한 값(ms) — @Scheduled는 상수 표현식만 허용
    public void scheduleRematchWaitingGroups() {
        matchingEngine.submit(new RematchWaitingGroups(this));
    }

    void applyRematchWaitingGroups() {
        retryRematchWaitingGroups();
    }

    /**
     * 재매칭 대기(CLOSED + rematchRequired) 상태의 방들에 대해 오퍼 라운드를 다시 시도한다. {@link #attemptOfferRound}는 그룹 맵의 키를 추가/삭제하지 않으므로
     * 스냅샷 순회로 안전하다.
     */
    private void retryRematchWaitingGroups() {
        List<OrderOfferGroup> waitingGroups = orderOfferGroupsByOrderId.values().stream()
                .filter(group -> group.status() == OrderOfferGroupStatus.CLOSED && group.rematchRequired())
                .toList();
        for (OrderOfferGroup group : waitingGroups) {
            attemptOfferRound(group);
        }
    }

    void applyStartMatching(Orders order) {
        log.debug("매칭 시작 액션 실행: orderId={}", order.getOrderId());

        // 큐에 쌓여 있는 동안 다른 액션이 먼저 방을 만들었을 수 있으므로 엔진 스레드에서 다시 확인한다.
        if (isOpenGroupExists(order.getOrderId())) {
            log.debug("이미 진행 중인 방이 있어 매칭 시작을 건너뜀: orderId={}", order.getOrderId());
            return;
        }

        GeoPoint boormiLocation = new GeoPoint(order.getOriginLatitude(), order.getOriginLongitude());
        OrderOfferGroup group = new OrderOfferGroup(order.getOrderId(), order.getBoormiId(), boormiLocation,
                new ArrayList<>());
        orderOfferGroupsByOrderId.put(order.getOrderId(), group);
        attemptOfferRound(group);
    }

    /**
     * 방에 아직 제안받지 않은 대기 드리미가 있으면 다음 오퍼 라운드를 진행하고, 없으면 재매칭 대기(CLOSED)로 둔다. 최초 매칭 시작과 소진 후 재매칭이 모두 이 메서드를 재사용한다.
     * {@link MatchOffer#shouldExcludeFromRematch()}에 따라, 명시적으로 거절했거나 드리미 응답 timeout(DREAMI_EXPIRED)인 드리미는 재제안 대상에서 제외하고
     * 타의로 회수됐거나(WITHDRAWN) 부르미 응답 timeout(BOORMI_EXPIRED)인 드리미는 다시 후보에 포함한다.
     */
    private void attemptOfferRound(OrderOfferGroup group) {
        Set<UUID> excludedDreamiIds = group.offers().stream()
                .filter(MatchOffer::shouldExcludeFromRematch)
                .map(MatchOffer::dreamiId)
                .collect(Collectors.toSet());

        List<WaitingDreami> candidates = dreamiMap.values().stream()
                .filter(dreami -> dreami.status() == WaitingDreamiStatus.MATCHING)
                .filter(dreami -> !excludedDreamiIds.contains(dreami.dreamiId()))
                .sorted(orderingComparator())
                .limit(MAX_OFFER_COUNT)
                .toList();

        if (candidates.isEmpty()) {
            // 아직 붙일 드리미가 없으면 재매칭 대기 상태로 남긴다. (드리미 등록/소진 시 재시도)
            group.closeForRematch();
            return;
        }

        List<MatchOffer> newOffers = new ArrayList<>();
        for (WaitingDreami dreami : candidates) {
            UUID offerId = UUID.randomUUID(); // 제안UUID (드리미 1명당 1개)
            MatchOffer offer = new MatchOffer(offerId, group.orderId(), dreami.dreamiId(), MatchOfferStatus.OFFERED);
            newOffers.add(offer);

            offersById.put(offerId, offer);
            offerIdsByDreamiId.computeIfAbsent(dreami.dreamiId(), k -> new HashSet<>()).add(offerId);
            dreami.markProposed();
            offerTimeoutScheduler.scheduleDreamiOfferTimeout(offerId, OFFER_TTL);
        }
        group.addOffersAndOpen(newOffers);

        // 제안받은 드리미 각각에게 제안 팝업을 띄운다.
        for (MatchOffer offer : newOffers) {
            sseService.send(offer.dreamiId(), MatchingEventType.OFFER_POPUP, OfferPopupPayload.from(offer));
        }
    }

    void applyCancelOrderByBoormi(UUID orderId) {
        log.debug("부르미 주문 취소 액션 실행: orderId={}", orderId);

        OrderOfferGroup group = orderOfferGroupsByOrderId.get(orderId);
        if (group == null) {
            log.debug("존재하지 않는 주문 취소 요청, 무시: orderId={}", orderId);
            return;
        }
        if (group.status() != OrderOfferGroupStatus.OPEN) {
            log.debug("이미 종료된 주문 취소 요청, 무시: orderId={}", orderId);
            return;
        }

        for (MatchOffer offer : group.offers()) {
            if (offer.status() == MatchOfferStatus.OFFERED) {
                offer.withdraw();
                findDreami(offer.dreamiId()).ifPresent(WaitingDreami::markMatching);
                sseService.send(offer.dreamiId(), MatchingEventType.OFFER_CLOSED,
                        new OfferClosedPayload(offer.offerId(), "부르미가 주문을 취소함"));
            } else if (offer.status() == MatchOfferStatus.PENDING_BOORMI_CONFIRMATION) {
                offer.rejectByBoormi();
                findDreami(offer.dreamiId()).ifPresent(WaitingDreami::markMatching);
                sseService.send(offer.dreamiId(), MatchingEventType.OFFER_CLOSED,
                        new OfferClosedPayload(offer.offerId(), "부르미가 주문을 취소함"));
            }
        }
        group.cancel();
    }

    void applyAcceptByDreami(UUID offerId) {
        log.debug("드리미 수락 액션 실행: offerId={}", offerId);

        Optional<MatchOffer> optionalMatchOffer = acceptableOffer(offerId);
        if (optionalMatchOffer.isEmpty()) {
            return;
        }
        MatchOffer matchOffer = optionalMatchOffer.get();
        OrderOfferGroup group = orderOfferGroupsByOrderId.get(matchOffer.orderId());
        if (group == null) {
            sseService.send(matchOffer.dreamiId(), MatchingEventType.OFFER_ERROR,
                    new NotificationErrorPayload("존재하지 않는 주문입니다."));
            return;
        }
        UUID acceptedDreamiId = matchOffer.dreamiId();

        // 수락한 사람의 상태를 PENDING_BOORMI_CONFIRMATION로 변경
        // 나머지 매칭오퍼 상태를 WITHDRAW로 변경
        for (MatchOffer offer : group.offers()) {
            // 수락한사람은 PENDING_BOORMI_CONFIRMATION
            // 나머지 사람은 WITHDRAWN
            if (offer.dreamiId().equals(acceptedDreamiId)) {
                offer.acceptByDreami();
                offerTimeoutScheduler.scheduleBoormiOfferTimeout(offer.offerId(), BOORMI_OFFER_TTL);
                // 부르미에게 수락한 드리미 정보를 넘겨 확인 팝업을 띄운다.
                sseService.send(group.boormiId(), MatchingEventType.DREAMI_INFO, DreamiInfoPayload.from(offer));
            } else if (offer.status() == MatchOfferStatus.OFFERED) {
                // 아직 응답 대기중(OFFERED)인 오퍼만 회수한다.
                // 이미 거절/만료됐거나 다른 방으로 넘어간 드리미의 상태는 건드리지 않는다.
                offer.withdraw();
                // 선착순에서 패배한 드리미를 다시 매칭 수락가능한 상태로 변경
                findDreami(offer.dreamiId())
                        .ifPresent(WaitingDreami::markMatching);
                sseService.send(offer.dreamiId(), MatchingEventType.OFFER_CLOSED,
                        new OfferClosedPayload(offer.offerId(), "선착순 마감"));
            }
        }
    }

    void applyRejectByDreami(UUID offerId) {
        log.debug("드리미 거절 액션 실행: offerId={}", offerId);

        findOffer(offerId).ifPresentOrElse(
                offer -> {
                    findDreami(offer.dreamiId()).ifPresent(WaitingDreami::markMatching);
                    offer.rejectByDreami();
                    sseService.send(offer.dreamiId(), MatchingEventType.OFFER_CLOSED,
                            new OfferClosedPayload(offer.offerId(), "거절 완료"));
                    closeGroupIfExhausted(offer.orderId());
                },
                () -> log.debug("존재하지 않는 제안 거절 요청, 무시: offerId={}", offerId)
        );
    }

    void applyAcceptByBoormi(UUID offerId) {
        log.debug("부르미 수락 액션 실행: offerId={}", offerId);

        findOffer(offerId).ifPresentOrElse(
                matchOffer -> {
                    matchOffer.confirmByBoormi(); // 부르미까지 수락 완료
                    findOrderOfferGroup(matchOffer.orderId())
                            .ifPresentOrElse(
                                    group -> {
                                        proceedToDelivery(matchOffer, group.boormiId());
                                        cleanUpAfterMatched(matchOffer, group);
                                    },
                                    () -> log.warn("부르미 수락 처리 중 주문 제안 그룹을 찾을 수 없어 배달을 시작하지 못함: offerId={}, orderId={}",
                                            matchOffer.offerId(), matchOffer.orderId())
                            );
                },
                () -> log.debug("존재하지 않는 제안 부르미 수락 요청, 무시: offerId={}", offerId)
        );
    }

    void applyRejectByBoormi(UUID offerId) {
        log.debug("부르미 거절 액션 실행: offerId={}", offerId);

        // 해당 match가 PENDING_BOORMI_CONFIRMATION 상태가 아니라면 이미 수락/만료/취소 등으로 처리가 된거임
        findOffer(offerId)
                .filter(matchOffer -> matchOffer.status() == MatchOfferStatus.PENDING_BOORMI_CONFIRMATION)
                .ifPresentOrElse(
                        matchOffer -> {
                            matchOffer.rejectByBoormi();

                            // 거절당한 드리미에게 부르미가 거절했음을 알리고, 다시 배달가능 상태로 변경
                            sseService.send(matchOffer.dreamiId(), MatchingEventType.BOORMI_REJECTED,
                                    new BoormiRejectedPayload(matchOffer.offerId(), matchOffer.orderId()));
                            findDreami(matchOffer.dreamiId())
                                    .ifPresent(WaitingDreami::markMatching);

                            closeGroupForRematch(matchOffer.orderId());
                        },
                        () -> log.debug("거절 가능한 상태가 아닌 제안 부르미 거절 요청, 무시: offerId={}", offerId)
                );
    }

    public void expireDreamiOffer(UUID offerId) {
        matchingEngine.submit(new ExpireDreamiOffer(this, offerId));
    }

    void applyExpireDreamiOffer(UUID offerId) {
        log.debug("드리미 응답시간 만료 액션 실행: offerId={}", offerId);

        // 해당 match가 OFFERED 상태가 아니라면 다른 로직에 의해서 처리가 된거임
        findOffer(offerId)
                .filter(matchOffer -> matchOffer.status() == MatchOfferStatus.OFFERED)
                .ifPresent(matchOffer -> {
                    matchOffer.expireByDreami();
                    findDreami(matchOffer.dreamiId())
                            .ifPresent(WaitingDreami::markMatching);
                    closeGroupIfExhausted(matchOffer.orderId());
                });
    }

    public void expireBoormiOffer(UUID offerId) {
        matchingEngine.submit(new ExpireBoormiOffer(this, offerId));
    }

    void applyExpireBoormiOffer(UUID offerId) {
        log.debug("부르미 응답시간 만료 액션 실행: offerId={}", offerId);

        // 해당 match가 PENDING_BOORMI_CONFIRMATION 상태가 아니라면 이미 수락/거절/취소 등으로 처리가 된거임
        findOffer(offerId)
                .filter(matchOffer -> matchOffer.status() == MatchOfferStatus.PENDING_BOORMI_CONFIRMATION)
                .ifPresent(matchOffer -> {
                    // 드리미가 다시 배달이 가능하게 바꿔야함
                    matchOffer.expireByBoormi();
                    findDreami(matchOffer.dreamiId())
                            .ifPresent(WaitingDreami::markMatching);
                    closeGroupForRematch(matchOffer.orderId());
                });
    }

    /**
     * TODO: 거리순 등 실제 정렬 기준 확정 전까지는 대기 오래한 순
     */
    private Comparator<WaitingDreami> orderingComparator() {
        return Comparator.comparing(WaitingDreami::updatedAt);
    }

    /**
     * 해당 주문에 진행 중(OPEN)인 방이 이미 있는지 확인한다. 주문 접수 시 중복 매칭 시작을 트랜잭션 안에서 걸러내는 데도 쓴다.
     */
    public boolean isOpenGroupExists(UUID orderId) {
        OrderOfferGroup existingGroup = orderOfferGroupsByOrderId.get(orderId);
        return existingGroup != null && existingGroup.status() == OrderOfferGroupStatus.OPEN;
    }

    private Optional<MatchOffer> findOffer(UUID offerId) {
        return Optional.ofNullable(offersById.get(offerId));
    }

    /**
     * offerId 로 확정 대상 드리미를 조회한다. 부르미 확정 시 ORDERS.dreami_id 반영에 사용한다. 해당 오퍼가 없으면 empty.
     */
    public Optional<UUID> findDreamiIdByOfferId(UUID offerId) {
        return findOffer(offerId).map(MatchOffer::dreamiId);
    }

    /**
     * offerId 로 해당 제안이 속한 주문을 조회한다. 드리미 수락 시 ORDERS.order_cd 반영에 사용한다. 해당 오퍼가 없으면 empty.
     */
    public Optional<UUID> findOrderIdByOfferId(UUID offerId) {
        return findOffer(offerId).map(MatchOffer::orderId);
    }

    Optional<WaitingDreami> findDreami(UUID dreamiId) {
        return Optional.ofNullable(dreamiMap.get(dreamiId));
    }

    public Optional<OrderOfferGroup> findOrderOfferGroup(UUID orderId) {
        return Optional.ofNullable(orderOfferGroupsByOrderId.get(orderId));
    }

    /**
     * 해당 제안이 주어진 드리미에게 온 것인지 확인한다. 제안이 존재하지 않으면 false.
     *
     * @param offerId  확인할 제안 UUID
     * @param dreamiId 요청한 드리미 UUID
     * @return 제안의 대상 드리미가 dreamiId와 일치하면 true
     */
    public boolean isDreamiOfferOwner(UUID offerId, UUID dreamiId) {
        return findOffer(offerId).map(offer -> offer.dreamiId().equals(dreamiId)).orElse(false);
    }

    /**
     * 해당 제안이 속한 주문이 주어진 부르미의 것인지 확인한다. 제안이나 방이 존재하지 않으면 false.
     *
     * @param offerId  확인할 제안 UUID
     * @param boormiId 요청한 부르미 UUID
     * @return 제안이 속한 방의 부르미가 boormiId와 일치하면 true
     */
    public boolean isBoormiOfferOwner(UUID offerId, UUID boormiId) {
        return findOffer(offerId)
                .flatMap(offer -> findOrderOfferGroup(offer.orderId()))
                .map(group -> group.boormiId().equals(boormiId))
                .orElse(false);
    }

    /**
     * 확정 후보(수락자)를 부르미가 거절/만료시킨 경우 - 남은 오퍼가 없으므로 아직 제안받지 않은 드리미에게 즉시 재오퍼를 시도한다. 후보가 없으면 재매칭 대기 상태가 된다.
     */
    private void closeGroupForRematch(UUID orderId) {
        findOrderOfferGroup(orderId).ifPresent(this::attemptOfferRound);
    }

    /**
     * 방 안의 모든 오퍼가 거절/만료/철회로 끝나 더 이상 진행 중인 오퍼가 없으면, 아직 제안받지 않은 드리미에게 즉시 재오퍼를 시도한다. 후보가 없으면 재매칭 대기 상태가 된다.
     */
    private void closeGroupIfExhausted(UUID orderId) {
        findOrderOfferGroup(orderId).ifPresent(group -> {
            boolean anyStillLive = group.offers().stream()
                    .anyMatch(offer -> offer.status() == MatchOfferStatus.OFFERED
                            || offer.status() == MatchOfferStatus.PENDING_BOORMI_CONFIRMATION
                            || offer.status() == MatchOfferStatus.MATCHED);
            if (!anyStillLive) {
                attemptOfferRound(group);
            }
        });
    }

    /**
     * 드리미가 정상적으로 수락 가능한 오퍼만 반환한다. 없거나 이미 종료된 상태면 실패 알림을 보내고 empty를 반환한다.
     */
    private Optional<MatchOffer> acceptableOffer(UUID offerId) {
        MatchOffer offer = offersById.get(offerId);
        if (offer == null) {
            // 대상 드리미를 특정할 수 없으므로 SSE로 알릴 수 없다. 로그만 남긴다.
            log.debug("존재하지 않는 제안 수락 요청, 무시: offerId={}", offerId);
            return Optional.empty();
        }
        // 이미 자신 matchOffer상태가 WITHDRAWN이면? -> 실패메시지
        if (offer.status() == MatchOfferStatus.WITHDRAWN) {
            sseService.send(offer.dreamiId(), MatchingEventType.OFFER_ERROR,
                    new NotificationErrorPayload("이미 다른 드리미가 수락한 주문입니다."));
            return Optional.empty();
        }
        // 정상적으로 수락 가능한 상태는 OFFERED 뿐. (거절/만료된 제안은 수락 불가)
        if (offer.status() != MatchOfferStatus.OFFERED) {
            sseService.send(offer.dreamiId(), MatchingEventType.OFFER_ERROR,
                    new NotificationErrorPayload("이미 종료된 제안입니다."));
            return Optional.empty();
        }
        return Optional.of(offer);
    }

    /**
     * 매칭이 성사된 후 인메모리의 매칭 정보를 삭제한다.
     *
     * @param matchOffer 해당하는 offer
     * @param group      모든 offer들을 담은 그룹
     */
    private void cleanUpAfterMatched(MatchOffer matchOffer, OrderOfferGroup group) {
        dreamiMap.remove(matchOffer.dreamiId());

        // 상태에 관계 없이 삭제를 진행한다.
        for (MatchOffer offer : group.offers()) {
            offersById.remove(offer.offerId());
        }
        orderOfferGroupsByOrderId.remove(group.orderId());
    }
    // ────────────────────────────── 배달 연동 ──────────────────────────────

    private void proceedToDelivery(MatchOffer matchOffer, UUID boormiId) {
        deliveryService.startDelivery(matchOffer.orderId(), matchOffer.dreamiId(), boormiId);
    }
}
