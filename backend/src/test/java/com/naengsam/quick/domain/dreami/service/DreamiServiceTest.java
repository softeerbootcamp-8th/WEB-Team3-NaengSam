package com.naengsam.quick.domain.dreami.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.naengsam.quick.domain.boormi.entity.Boormi;
import com.naengsam.quick.domain.boormi.repository.BoormiRepository;
import com.naengsam.quick.domain.dreami.dto.DreamiDashboardDto;
import com.naengsam.quick.domain.dreami.dto.DreamiProfileDto;
import com.naengsam.quick.domain.dreami.dto.MonthlyRevenueDto;
import com.naengsam.quick.domain.dreami.dto.NearbyCallDto;
import com.naengsam.quick.domain.dreami.entity.Dreami;
import com.naengsam.quick.domain.dreami.entity.DreamiCd;
import com.naengsam.quick.domain.dreami.exception.DreamiErrorCode;
import com.naengsam.quick.domain.dreami.repository.DreamiRepository;
import com.naengsam.quick.domain.dreami.repository.DreamiRequestDeniedDetailsRepository;
import com.naengsam.quick.domain.matching.dto.GeoPoint;
import com.naengsam.quick.domain.matching.dto.NearbyOrderDto;
import com.naengsam.quick.domain.matching.dto.NearbyOrderRequest;
import com.naengsam.quick.domain.matching.event.DreamiAcceptedEvent;
import com.naengsam.quick.domain.matching.exception.MatchingErrorCode;
import com.naengsam.quick.domain.matching.service.MatchingService;
import com.naengsam.quick.domain.matching.service.NearbyOrderFinder;
import com.naengsam.quick.domain.order.dto.OrderSummaryDto;
import com.naengsam.quick.domain.order.entity.OrderCd;
import com.naengsam.quick.domain.order.entity.Orders;
import com.naengsam.quick.domain.order.exception.OrderErrorCode;
import com.naengsam.quick.domain.order.repository.OrderRepository;
import com.naengsam.quick.domain.payment.dto.MonthlyMoneyAggregate;
import com.naengsam.quick.domain.payment.entity.MoneyTxStatusCd;
import com.naengsam.quick.domain.payment.entity.MoneyTxTypeCd;
import com.naengsam.quick.domain.payment.repository.MoneyTxRepository;
import com.naengsam.quick.global.code.BaseErrorCode;
import com.naengsam.quick.global.exception.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 드리미 서비스 단위 테스트. 프로필 조회 시 이름/평점/거절횟수를, 현재 배달 카드 조회 시 주문 상세를,
 * 주변 콜 조회 시 위치/거리와 주문 상세를 올바르게 조합하는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class DreamiServiceTest {

    @Mock
    private DreamiRepository dreamiRepository;

    @Mock
    private BoormiRepository boormiRepository;

    @Mock
    private DreamiRequestDeniedDetailsRepository dreamiRequestDeniedDetailsRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private NearbyOrderFinder nearbyOrderFinder;

    @Mock
    private MatchingService matchingService;

    @Mock
    private MoneyTxRepository moneyTxRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private DreamiService dreamiService;

    private static BaseErrorCode errorCodeOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).getErrorCode();
    }

    private static Boormi activeBoormi() {
        return Boormi.create("dreami@test.com", "pass123", "김드림", "01098765432",
                LocalDate.of(1995, 5, 5));
    }

    // ---------- getDreamiProfile ----------

    @Test
    void 프로필조회_정상이면_이름_평점_거절횟수를_담아_반환한다() {
        Boormi boormi = activeBoormi();
        UUID id = boormi.getBoormiId();
        Dreami dreami = Dreami.create(id, "idCardKey", "criminalRecordKey");
        given(dreamiRepository.findById(id)).willReturn(Optional.of(dreami));
        given(boormiRepository.findById(id)).willReturn(Optional.of(boormi));
        given(dreamiRequestDeniedDetailsRepository.countByDreamiId(id)).willReturn(2L);

        DreamiProfileDto result = dreamiService.getDreamiProfile(id);

        assertThat(result.name()).isEqualTo("김드림");
        assertThat(result.dreamiAvgScore()).isEqualByComparingTo(dreami.getDreamiAvgScore());
        assertThat(result.rejectCount()).isEqualTo(2L);
    }

    @Test
    void 프로필조회_드리미가_없으면_NOT_FOUND_예외() {
        UUID id = UUID.randomUUID();
        given(dreamiRepository.findById(id)).willReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> dreamiService.getDreamiProfile(id));

        assertThat(errorCodeOf(thrown)).isEqualTo(DreamiErrorCode.NOT_FOUND);
    }

    @Test
    void 프로필조회_부르미가_없으면_NOT_FOUND_예외() {
        UUID id = UUID.randomUUID();
        Dreami dreami = Dreami.create(id, "idCardKey", "criminalRecordKey");
        given(dreamiRepository.findById(id)).willReturn(Optional.of(dreami));
        given(boormiRepository.findById(id)).willReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> dreamiService.getDreamiProfile(id));

        assertThat(errorCodeOf(thrown)).isEqualTo(DreamiErrorCode.NOT_FOUND);
    }

    // ---------- saveVerificationFileKeys ----------

    @Test
    void 저장_직전_재확인에서_이미_승인됐으면_ALREADY_APPROVED_예외이고_저장하지_않는다() {
        UUID dreamiId = UUID.randomUUID();
        Dreami dreami = Dreami.create(dreamiId, "oldIdCardKey", "oldCriminalRecordKey");
        ReflectionTestUtils.setField(dreami, "requestCd", DreamiCd.APPROVED);
        given(dreamiRepository.findByDreamiId(dreamiId)).willReturn(Optional.of(dreami));

        Throwable thrown = catchThrowable(
                () -> dreamiService.saveVerificationFileKeys(dreamiId, "newIdCardKey", "newCriminalRecordKey"));

        assertThat(errorCodeOf(thrown)).isEqualTo(DreamiErrorCode.ALREADY_APPROVED);
        verify(dreamiRepository, never()).save(any());
    }

    @Test
    void 저장_직전_재확인에서_승인_안됐으면_정상_저장한다() {
        UUID dreamiId = UUID.randomUUID();
        Dreami dreami = Dreami.create(dreamiId, "oldIdCardKey", "oldCriminalRecordKey"); // 기본 상태 REQUESTED
        given(dreamiRepository.findByDreamiId(dreamiId)).willReturn(Optional.of(dreami));

        dreamiService.saveVerificationFileKeys(dreamiId, "newIdCardKey", "newCriminalRecordKey");

        verify(dreamiRepository).save(any());
    }

    @Test
    void 저장_직전_재확인에서_신청기록이_없으면_최초_제출로_정상_저장한다() {
        UUID dreamiId = UUID.randomUUID();
        given(dreamiRepository.findByDreamiId(dreamiId)).willReturn(Optional.empty());

        dreamiService.saveVerificationFileKeys(dreamiId, "idCardKey", "criminalRecordKey");

        verify(dreamiRepository).save(any());
    }

    // ---------- assertNotAlreadyApproved ----------

    @Test
    void 승인된_드리미면_ALREADY_APPROVED_예외() {
        UUID dreamiId = UUID.randomUUID();
        Dreami dreami = Dreami.create(dreamiId, "idCardKey", "criminalRecordKey");
        ReflectionTestUtils.setField(dreami, "requestCd", DreamiCd.APPROVED);
        given(dreamiRepository.findById(dreamiId)).willReturn(Optional.of(dreami));

        Throwable thrown = catchThrowable(() -> dreamiService.assertNotAlreadyApproved(dreamiId));

        assertThat(errorCodeOf(thrown)).isEqualTo(DreamiErrorCode.ALREADY_APPROVED);
    }

    @Test
    void 승인되지_않은_드리미면_예외없이_통과한다() {
        UUID dreamiId = UUID.randomUUID();
        Dreami dreami = Dreami.create(dreamiId, "idCardKey", "criminalRecordKey"); // 기본 상태 REQUESTED
        given(dreamiRepository.findById(dreamiId)).willReturn(Optional.of(dreami));

        assertThatCode(() -> dreamiService.assertNotAlreadyApproved(dreamiId)).doesNotThrowAnyException();
    }

    @Test
    void 신청기록이_없으면_예외없이_통과한다() {
        UUID dreamiId = UUID.randomUUID();
        given(dreamiRepository.findById(dreamiId)).willReturn(Optional.empty());

        assertThatCode(() -> dreamiService.assertNotAlreadyApproved(dreamiId)).doesNotThrowAnyException();
    }

    // ---------- findCurrentDeliveryCard ----------

    @Test
    void 현재배달카드조회_진행중인_주문이_있으면_카드정보를_반환한다() {
        UUID dreamiId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        GeoPoint point = new GeoPoint(new BigDecimal("37.5"), new BigDecimal("127.0"));
        Orders order = Orders.create(orderId, UUID.randomUUID(), point, point);
        ReflectionTestUtils.setField(order, "itemName", "서류봉투");
        ReflectionTestUtils.setField(order, "orderCd", OrderCd.IN_PROGRESS);
        given(orderRepository.findByDreamiIdAndOrderCd(dreamiId, OrderCd.IN_PROGRESS))
                .willReturn(Optional.of(order));

        OrderSummaryDto result = dreamiService.findCurrentDeliveryCard(dreamiId);

        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.itemName()).isEqualTo("서류봉투");
        assertThat(result.orderCd()).isEqualTo(OrderCd.IN_PROGRESS);
    }

    @Test
    void 현재배달카드조회_진행중인_주문이_없으면_null을_반환한다() {
        UUID dreamiId = UUID.randomUUID();
        given(orderRepository.findByDreamiIdAndOrderCd(dreamiId, OrderCd.IN_PROGRESS))
                .willReturn(Optional.empty());

        OrderSummaryDto result = dreamiService.findCurrentDeliveryCard(dreamiId);

        assertThat(result).isNull();
    }

    // ---------- findNearbyCalls ----------

    @Test
    void 주변콜조회_정상이면_거리와_주문상세를_조합해_반환한다() {
        NearbyOrderRequest request = new NearbyOrderRequest(
                new BigDecimal("37.5"), new BigDecimal("127.0"), 1000.0, 10);
        UUID orderId = UUID.randomUUID();
        GeoPoint location = new GeoPoint(new BigDecimal("37.501"), new BigDecimal("127.001"));
        NearbyOrderDto nearbyOrder = new NearbyOrderDto(orderId, location, 120.5);
        given(nearbyOrderFinder.find(request)).willReturn(List.of(nearbyOrder));

        UUID boormiId = UUID.randomUUID();
        Orders order = Orders.create(orderId, boormiId, location, location);
        ReflectionTestUtils.setField(order, "itemName", "서류봉투");
        ReflectionTestUtils.setField(order, "deliveryAmount", 3500L);
        ReflectionTestUtils.setField(order, "deliveryEta", 15);
        given(orderRepository.findById(orderId)).willReturn(Optional.of(order));

        List<NearbyCallDto> result = dreamiService.findNearbyCalls(request);

        assertThat(result).hasSize(1);
        NearbyCallDto dto = result.getFirst();
        assertThat(dto.orderId()).isEqualTo(orderId);
        assertThat(dto.distanceMeters()).isEqualTo(120.5);
        assertThat(dto.itemName()).isEqualTo("서류봉투");
        assertThat(dto.expectedRevenue()).isEqualTo(3500L);
        assertThat(dto.expectedEtaMinutes()).isEqualTo(15);
    }

    @Test
    void 주변콜조회_주문을_찾을_수_없으면_ORDER_NOT_FOUND_예외() {
        NearbyOrderRequest request = new NearbyOrderRequest(
                new BigDecimal("37.5"), new BigDecimal("127.0"), 1000.0, 10);
        UUID orderId = UUID.randomUUID();
        NearbyOrderDto nearbyOrder = new NearbyOrderDto(orderId,
                new GeoPoint(new BigDecimal("37.501"), new BigDecimal("127.001")), 120.5);
        given(nearbyOrderFinder.find(request)).willReturn(List.of(nearbyOrder));
        given(orderRepository.findById(orderId)).willReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> dreamiService.findNearbyCalls(request));

        assertThat(errorCodeOf(thrown)).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
    }

    // ---------- goOnline ----------

    @Test
    void 온라인전환_드리미가_없으면_NOT_FOUND_예외() {
        UUID dreamiId = UUID.randomUUID();
        GeoPoint location = new GeoPoint(new BigDecimal("37.5"), new BigDecimal("127.0"));
        given(dreamiRepository.findById(dreamiId)).willReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> dreamiService.goOnline(dreamiId, location));

        assertThat(errorCodeOf(thrown)).isEqualTo(DreamiErrorCode.NOT_FOUND);
        verify(matchingService, never()).registerDreami(any(), any());
    }

    @Test
    void 온라인전환_승인되지_않았으면_NOT_APPROVED_예외() {
        UUID dreamiId = UUID.randomUUID();
        GeoPoint location = new GeoPoint(new BigDecimal("37.5"), new BigDecimal("127.0"));
        Dreami dreami = Dreami.create(dreamiId, "idCardKey", "criminalRecordKey"); // 기본 상태 REQUESTED
        given(dreamiRepository.findById(dreamiId)).willReturn(Optional.of(dreami));

        Throwable thrown = catchThrowable(() -> dreamiService.goOnline(dreamiId, location));

        assertThat(errorCodeOf(thrown)).isEqualTo(DreamiErrorCode.NOT_APPROVED);
        verify(matchingService, never()).registerDreami(any(), any());
    }

    @Test
    void 온라인전환_수행중인_주문이_있으면_ALREADY_HAS_ACTIVE_ORDER_예외() {
        UUID dreamiId = UUID.randomUUID();
        GeoPoint location = new GeoPoint(new BigDecimal("37.5"), new BigDecimal("127.0"));
        Dreami dreami = Dreami.create(dreamiId, "idCardKey", "criminalRecordKey");
        ReflectionTestUtils.setField(dreami, "requestCd", DreamiCd.APPROVED);
        given(dreamiRepository.findById(dreamiId)).willReturn(Optional.of(dreami));
        given(orderRepository.countActiveOrders(dreamiId)).willReturn(1L);

        Throwable thrown = catchThrowable(() -> dreamiService.goOnline(dreamiId, location));

        assertThat(errorCodeOf(thrown)).isEqualTo(DreamiErrorCode.ALREADY_HAS_ACTIVE_ORDER);
        verify(matchingService, never()).registerDreami(any(), any());
    }

    @Test
    void 온라인전환_승인됐고_활성주문이_없으면_매칭서비스에_등록한다() {
        UUID dreamiId = UUID.randomUUID();
        GeoPoint location = new GeoPoint(new BigDecimal("37.5"), new BigDecimal("127.0"));
        Dreami dreami = Dreami.create(dreamiId, "idCardKey", "criminalRecordKey");
        ReflectionTestUtils.setField(dreami, "requestCd", DreamiCd.APPROVED);
        given(dreamiRepository.findById(dreamiId)).willReturn(Optional.of(dreami));
        given(orderRepository.countActiveOrders(dreamiId)).willReturn(0L);

        dreamiService.goOnline(dreamiId, location);

        verify(matchingService).registerDreami(dreamiId, location);
    }

    // ---------- getDashboard ----------

    @Test
    void 대시보드조회_완료건수_이번달수익_증감률_이번달건수_최근6개월을_조합해_반환한다() {
        UUID dreamiId = UUID.randomUUID();
        YearMonth thisMonth = YearMonth.now();
        YearMonth lastMonth = thisMonth.minusMonths(1);
        given(orderRepository.countByDreamiIdAndOrderCd(dreamiId, OrderCd.COMPLETED)).willReturn(7L);
        given(moneyTxRepository.aggregateByBoormiIdAndTypeBetween(eq(dreamiId), eq(MoneyTxTypeCd.SETTLEMENT), eq(MoneyTxStatusCd.SETTLED), any(), any()))
                .willReturn(List.of(
                        new MonthlyMoneyAggregate(thisMonth.getYear(), thisMonth.getMonthValue(), 116_000L, 10L),
                        new MonthlyMoneyAggregate(lastMonth.getYear(), lastMonth.getMonthValue(), 100_000L, 8L)));

        DreamiDashboardDto result = dreamiService.getDashboard(dreamiId);

        assertThat(result.completedCount()).isEqualTo(7L);
        assertThat(result.thisMonthRevenue()).isEqualTo(116_000L);
        assertThat(result.monthOverMonthGrowthPercent()).isEqualTo(16L); // (116000-100000)/100000*100, 반올림
        assertThat(result.thisMonthCount()).isEqualTo(10L);
        assertThat(result.recentSixMonths()).hasSize(6);
        MonthlyRevenueDto latest = result.recentSixMonths().getLast();
        assertThat(latest.month()).isEqualTo(thisMonth);
        assertThat(latest.revenue()).isEqualTo(116_000L);
    }

    @Test
    void 대시보드조회_지난달_수익이_0이면_증감률은_0퍼센트다() {
        UUID dreamiId = UUID.randomUUID();
        given(orderRepository.countByDreamiIdAndOrderCd(dreamiId, OrderCd.COMPLETED)).willReturn(0L);
        given(moneyTxRepository.aggregateByBoormiIdAndTypeBetween(eq(dreamiId), eq(MoneyTxTypeCd.SETTLEMENT), eq(MoneyTxStatusCd.SETTLED), any(), any()))
                .willReturn(List.of());

        DreamiDashboardDto result = dreamiService.getDashboard(dreamiId);

        assertThat(result.monthOverMonthGrowthPercent()).isEqualTo(0L);
        assertThat(result.thisMonthCount()).isEqualTo(0L);
    }

    // ---------- acceptOffer ----------

    @Test
    void 제안수락_본인에게_온_제안이_아니면_NOT_OFFER_OWNER_예외() {
        UUID offerId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();
        given(matchingService.isDreamiOfferOwner(offerId, dreamiId)).willReturn(false);

        Throwable thrown = catchThrowable(() -> dreamiService.acceptOffer(offerId, dreamiId));

        assertThat(errorCodeOf(thrown)).isEqualTo(MatchingErrorCode.NOT_OFFER_OWNER);
        verify(eventPublisher, never()).publishEvent(any(DreamiAcceptedEvent.class));
        verify(orderRepository, never()).findById(any());
    }

    @Test
    void 제안수락_제안에_해당하는_주문을_찾을_수_없으면_ORDER_NOT_FOUND_예외() {
        UUID offerId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();
        given(matchingService.isDreamiOfferOwner(offerId, dreamiId)).willReturn(true);
        given(matchingService.findOrderIdByOfferId(offerId)).willReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> dreamiService.acceptOffer(offerId, dreamiId));

        assertThat(errorCodeOf(thrown)).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
        verify(eventPublisher, never()).publishEvent(any(DreamiAcceptedEvent.class));
    }

    @Test
    void 제안수락_정상이면_주문을_PENDING_BOORMI_CONFIRMATION으로_전이하고_커밋후_처리용_이벤트를_발행한다() {
        UUID offerId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        GeoPoint point = new GeoPoint(new BigDecimal("37.5"), new BigDecimal("127.0"));
        Orders order = Orders.create(orderId, UUID.randomUUID(), point, point);
        given(matchingService.isDreamiOfferOwner(offerId, dreamiId)).willReturn(true);
        given(matchingService.findOrderIdByOfferId(offerId)).willReturn(Optional.of(orderId));
        given(orderRepository.findById(orderId)).willReturn(Optional.of(order));

        dreamiService.acceptOffer(offerId, dreamiId);

        assertThat(order.getOrderCd()).isEqualTo(OrderCd.PENDING_BOORMI_CONFIRMATION);
        verify(matchingService, never()).acceptByDreami(any()); // 커밋 전에는 엔진에 직접 제출하지 않는다
        verify(eventPublisher).publishEvent(new DreamiAcceptedEvent(offerId));
    }

    // ---------- rejectOffer ----------

    @Test
    void 제안거절_본인에게_온_제안이_아니면_NOT_OFFER_OWNER_예외() {
        UUID offerId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();
        given(matchingService.isDreamiOfferOwner(offerId, dreamiId)).willReturn(false);

        Throwable thrown = catchThrowable(() -> dreamiService.rejectOffer(offerId, dreamiId));

        assertThat(errorCodeOf(thrown)).isEqualTo(MatchingErrorCode.NOT_OFFER_OWNER);
        verify(matchingService, never()).rejectByDreami(any());
    }

    @Test
    void 제안거절_정상이면_매칭엔진에_거절을_알리고_주문은_건드리지_않는다() {
        UUID offerId = UUID.randomUUID();
        UUID dreamiId = UUID.randomUUID();
        given(matchingService.isDreamiOfferOwner(offerId, dreamiId)).willReturn(true);

        dreamiService.rejectOffer(offerId, dreamiId);

        verify(matchingService).rejectByDreami(offerId);
        verify(orderRepository, never()).findById(any());
    }
}
