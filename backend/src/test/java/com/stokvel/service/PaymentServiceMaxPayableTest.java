package com.stokvel.service;

import com.stokvel.model.Member;
import com.stokvel.repository.PaymentRepository;
import com.stokvel.service.PaymentService.MaxPayable;
import com.stokvel.websocket.LedgerBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Overpayment is refused. A member can pay at most their outstanding arrears plus
 * what is left of the open cycle's contribution — and nothing towards a cycle they
 * were never liable for (Rule 3). The same figure prefills the payment form and is
 * what recordPayment refuses above, so the two cannot disagree.
 *
 * Created in 2020 for the same reason ClockServiceTest is: nothing here should pass
 * because the real calendar happens to agree.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Import({StokvelSetupService.class, BuyinService.class, PaymentService.class,
        ArrearsService.class, PayoutService.class, ClockService.class})
class PaymentServiceMaxPayableTest {

    private static final LocalDate START = LocalDate.of(2020, 1, 15);
    private static final BigDecimal CONTRIBUTION = new BigDecimal("500.00");

    /** Cycle 1 pays John on 29 Feb 2020; just after it, cycle 2 (Sarah's) is open. */
    private static final LocalDate AFTER_CYCLE_ONE = LocalDate.of(2020, 3, 5);

    @MockitoBean
    private LedgerBroadcaster broadcaster;

    @Autowired
    private StokvelSetupService setupService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private ClockService clockService;
    @Autowired
    private PaymentRepository paymentRepository;

    private Member john;
    private Member sarah;

    @BeforeEach
    void createStokvelWithTwoMembers() {
        setupService.createStokvel(CONTRIBUTION, START, 1);
        john = setupService.addMember("John").member();
        sarah = setupService.addMember("Sarah").member();
    }

    // ---------------------------------------------------------------- the answer

    @Test
    void a_member_who_owes_nothing_else_can_pay_one_contribution() {
        MaxPayable max = paymentService.maxPayable(sarah.getId());

        assertThat(max.arrears()).isEqualByComparingTo("0.00");
        assertThat(max.contributionDue()).isEqualByComparingTo("500.00");
        assertThat(max.total()).isEqualByComparingTo("500.00");
    }

    @Test
    void a_part_payment_leaves_only_the_rest_of_the_contribution() {
        paymentService.recordPayment(sarah.getId(), new BigDecimal("300.00"));

        assertThat(paymentService.maxPayable(sarah.getId()).total()).isEqualByComparingTo("200.00");
    }

    /** Sarah skips cycle 1 and owes John R500; cycle 2 wants another R500 on top. */
    @Test
    void arrears_and_the_open_contribution_add_up() {
        clockService.advanceClock(AFTER_CYCLE_ONE);

        MaxPayable max = paymentService.maxPayable(sarah.getId());
        assertThat(max.arrears()).isEqualByComparingTo("500.00");
        assertThat(max.contributionDue()).isEqualByComparingTo("500.00");
        assertThat(max.total()).isEqualByComparingTo("1000.00");
    }

    /** The cap is inclusive: paying exactly the maximum is accepted and leaves nothing. */
    @Test
    void paying_exactly_the_maximum_is_accepted_and_leaves_nothing_payable() {
        clockService.advanceClock(AFTER_CYCLE_ONE);

        paymentService.recordPayment(sarah.getId(), new BigDecimal("1000.00"));

        assertThat(paymentService.maxPayable(sarah.getId()).total()).isEqualByComparingTo("0.00");
    }

    /**
     * One cent over is refused, and the refusal comes before any write — there is no
     * payment row for money the system would not accept.
     */
    @Test
    void one_cent_over_the_maximum_is_refused_and_writes_nothing() {
        long before = paymentRepository.count();

        assertThatIllegalStateException()
                .isThrownBy(() -> paymentService.recordPayment(sarah.getId(), new BigDecimal("500.01")))
                .withMessageContaining("500.00");
        assertThat(paymentRepository.count()).isEqualTo(before);
    }

    /**
     * Rule 3. Erik joins partway through cycle 2 — his buy-in covered that round, so
     * he owes it no contribution and cannot pay one. Without the liability check the
     * cap would let R500 into a pot sized for the members who were liable for it.
     */
    @Test
    void a_mid_cycle_joiner_can_pay_nothing_into_the_round_they_walked_in_on() {
        clockService.advanceClock(AFTER_CYCLE_ONE);
        Member erik = setupService.addMember("Erik").member();

        assertThat(paymentService.maxPayable(erik.getId()).total()).isEqualByComparingTo("0.00");
        assertThatIllegalStateException()
                .isThrownBy(() -> paymentService.recordPayment(erik.getId(), CONTRIBUTION));
    }

    // ------------------------------------------------------------ the invariants

    /**
     * The one that matters. Sarah owes R500 and pays R500: Rule 7 sends all of it to
     * the debt, so cycle 2's pot saw nothing from her and she can still pay a full
     * contribution. A cap summed from payment.amount would call her paid up and lock
     * her out of the cycle she is about to be charged a debt row for.
     */
    @Test
    void money_swallowed_by_arrears_does_not_count_towards_the_contribution() {
        clockService.advanceClock(AFTER_CYCLE_ONE);

        paymentService.recordPayment(sarah.getId(), CONTRIBUTION);

        MaxPayable max = paymentService.maxPayable(sarah.getId());
        assertThat(max.arrears()).isEqualByComparingTo("0.00");
        assertThat(max.contributionDue()).isEqualByComparingTo("500.00");
    }

    /** Every cycle paid out and nothing owed: nothing payable, and the payment is refused. */
    @Test
    void after_the_last_payout_a_square_member_can_pay_nothing() {
        paymentService.recordPayment(john.getId(), CONTRIBUTION);
        paymentService.recordPayment(sarah.getId(), CONTRIBUTION);
        clockService.advanceClock(AFTER_CYCLE_ONE);
        paymentService.recordPayment(john.getId(), CONTRIBUTION);
        paymentService.recordPayment(sarah.getId(), CONTRIBUTION);
        clockService.advanceClock(LocalDate.of(2020, 6, 30));

        assertThat(paymentService.maxPayable(john.getId()).total()).isEqualByComparingTo("0.00");
        assertThatIllegalStateException()
                .isThrownBy(() -> paymentService.recordPayment(john.getId(), CONTRIBUTION));
    }

    /**
     * John can't prepay cycle 2 before it opens — the "no credit" half. He pays
     * cycle 1, and the cap is zero until his payout fires and cycle 2 becomes the
     * open one.
     */
    @Test
    void a_paid_up_member_cannot_prepay_the_next_cycle() {
        paymentService.recordPayment(john.getId(), CONTRIBUTION);

        assertThat(paymentService.maxPayable(john.getId()).total()).isEqualByComparingTo("0.00");
    }
}
