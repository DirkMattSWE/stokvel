package com.stokvel.service;

import com.stokvel.model.Cycle;
import com.stokvel.model.Member;
import com.stokvel.model.Payment;
import com.stokvel.model.Payout;
import com.stokvel.repository.AllocationRepository;
import com.stokvel.repository.CycleRepository;
import com.stokvel.repository.PayoutRepository;
import org.junit.jupiter.api.BeforeEach;
import com.stokvel.websocket.LedgerBroadcaster;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Rule 7 lives in PaymentService v2 — these are the v1 assertions: a payment is a
 * fact, and an allocation is where that fact landed. The pot is never written to,
 * only summed, so every assertion here reads the pot back out of allocation rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Import({StokvelSetupService.class, BuyinService.class, PaymentService.class, ArrearsService.class})
class PaymentServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 15);
    private static final BigDecimal CONTRIBUTION = new BigDecimal("500.00");

    /**
     * Transport, not a rule — mocked rather than wired. The services call it at the
     * end of a mutating method; what it sends is LedgerService's job and is tested
     * there, and a STOMP broker has no business inside a @DataJpaTest.
     */
    @MockitoBean
    private LedgerBroadcaster broadcaster;

    @Autowired
    private StokvelSetupService setupService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private CycleRepository cycleRepository;
    @Autowired
    private AllocationRepository allocationRepository;
    @Autowired
    private PayoutRepository payoutRepository;

    private Member john;
    private Member sarah;
    private Member thabo;

    @BeforeEach
    void createStokvelWithThreeMembers() {
        setupService.createStokvel(CONTRIBUTION, START, 1);
        john = setupService.addMember("John").member();
        sarah = setupService.addMember("Sarah").member();
        thabo = setupService.addMember("Thabo").member();
    }

    @Test
    void a_payment_allocates_in_full_to_the_open_cycle() {
        Payment payment = paymentService.recordPayment(sarah.getId(), CONTRIBUTION).payment();

        assertThat(payment.getMember().getId()).isEqualTo(sarah.getId());
        assertThat(payment.getPayout()).as("a voluntary payment is not a payout deduction").isNull();
        assertThat(potFor(openCycle())).isEqualByComparingTo("500.00");
    }

    /** The pot is a SUM over allocations. Nothing anywhere does pot += amount. */
    @Test
    void the_pot_is_the_sum_of_every_payment_allocated_to_the_cycle() {
        paymentService.recordPayment(john.getId(), CONTRIBUTION);
        paymentService.recordPayment(sarah.getId(), CONTRIBUTION);
        paymentService.recordPayment(thabo.getId(), CONTRIBUTION);

        assertThat(potFor(openCycle())).isEqualByComparingTo("1500.00");
    }

    /** Partial payment needs no special case: two facts, two allocations, one sum. */
    @Test
    void partial_payments_accumulate_in_the_same_pot() {
        paymentService.recordPayment(john.getId(), new BigDecimal("200.00"));
        paymentService.recordPayment(john.getId(), new BigDecimal("300.00"));

        assertThat(potFor(openCycle())).isEqualByComparingTo("500.00");
    }

    /**
     * The payout row is what closes a cycle, so the handover to the next pot is
     * instant — a payment recorded the same afternoon lands in the cycle that can
     * still be received, not in the one that was just paid out.
     */
    @Test
    void payments_land_in_the_next_cycle_once_the_current_one_has_paid_out() {
        Cycle first = openCycle();
        paymentService.recordPayment(john.getId(), CONTRIBUTION);
        closeCycle(first);

        paymentService.recordPayment(john.getId(), CONTRIBUTION);

        Cycle second = openCycle();
        assertThat(second.getSequenceNumber()).isEqualTo(2);
        assertThat(potFor(first)).as("the paid-out pot is untouched").isEqualByComparingTo("500.00");
        assertThat(potFor(second)).isEqualByComparingTo("500.00");
    }

    /**
     * created_at comes from the stored clock, never Instant.now() (Rule 8). It is
     * business data: Rule 7 settles debts in created_at order.
     */
    @Test
    void a_payment_is_timestamped_by_the_simulated_clock() {
        Payment payment = paymentService.recordPayment(john.getId(), CONTRIBUTION).payment();

        assertThat(payment.getCreatedAt()).isEqualTo(instantOn(START));
    }

    @Test
    void a_payment_must_be_greater_than_zero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> paymentService.recordPayment(john.getId(), BigDecimal.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> paymentService.recordPayment(john.getId(), new BigDecimal("-100.00")));
    }

    /**
     * Rounding would store something other than what the caller said happened, and
     * every total in this system is a sum of these rows read back.
     */
    @Test
    void a_payment_cannot_carry_more_precision_than_money_has() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> paymentService.recordPayment(john.getId(), new BigDecimal("100.005")));

        paymentService.recordPayment(john.getId(), new BigDecimal("100.5000"));
        assertThat(potFor(openCycle())).as("trailing zeros are not extra precision")
                .isEqualByComparingTo("100.50");
    }

    @Test
    void a_payment_needs_a_member_who_exists() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> paymentService.recordPayment(9999L, CONTRIBUTION));
    }

    /**
     * No open cycle means the rotation is complete. Refusing is the honest answer —
     * the alternative is money allocated to a pot nobody will ever receive.
     */
    @Test
    void a_payment_is_refused_when_every_cycle_has_paid_out() {
        cycleRepository.findAll().forEach(this::closeCycle);

        assertThatIllegalStateException()
                .isThrownBy(() -> paymentService.recordPayment(john.getId(), CONTRIBUTION));
    }

    private Cycle openCycle() {
        return cycleRepository.findOpenCycle().orElseThrow();
    }

    private BigDecimal potFor(Cycle cycle) {
        return allocationRepository.sumByCycleId(cycle.getId());
    }

    /** Closes a cycle the way the system does: by the existence of a payout row. */
    private void closeCycle(Cycle cycle) {
        payoutRepository.save(
                new Payout(cycle, cycle.getRecipient(), potFor(cycle), instantOn(START)));
    }

    private static Instant instantOn(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
