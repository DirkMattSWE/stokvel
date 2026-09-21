package com.stokvel.service;

import com.stokvel.model.Allocation;
import com.stokvel.model.Cycle;
import com.stokvel.model.Member;
import com.stokvel.model.Payment;
import com.stokvel.model.Payout;
import com.stokvel.repository.AllocationRepository;
import com.stokvel.repository.CycleRepository;
import com.stokvel.repository.PaymentRepository;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rules 1 and 6 — the payout fires on the due date regardless of what the pot holds,
 * and arrears never block it. The member still takes their turn; what they owe comes
 * out of what they receive.
 *
 * Pass 3b. The shape being asserted is that the payout row is GROSS and the deduction
 * is a separate payment flowing back the other way, because that is what puts both
 * lines on the ledger:
 *
 *   PAYOUT   Sarah receives          R1,500
 *   PAYMENT  Sarah — auto-deducted     -R500
 *                                    -------
 *                                     R1,000
 *
 * The floor is a min on the deduction, never a clamp on the payout, so
 * max(0, pot - owed) falls out with no branch — and the cap can never exceed what is
 * owed, which is what makes recordArrearsDeduction's refusal unreachable in normal
 * operation. The last two tests reach for it directly to prove it still refuses.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Import({StokvelSetupService.class, PaymentService.class, ArrearsService.class, PayoutService.class})
class PayoutServiceArrearsDeductionTest {

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
    private PayoutService payoutService;
    @Autowired
    private CycleRepository cycleRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PayoutRepository payoutRepository;
    @Autowired
    private AllocationRepository allocationRepository;
    @Autowired
    private ArrearsService arrearsService;

    private Member john;
    private Member sarah;
    private Member thabo;

    /** Cycle 1 pays John (28 Feb), 2 pays Sarah (31 Mar), 3 pays Thabo (30 Apr). */
    @BeforeEach
    void createStokvelWithThreeMembers() {
        setupService.createStokvel(CONTRIBUTION, START, 1);
        john = setupService.addMember("John").member();
        sarah = setupService.addMember("Sarah").member();
        thabo = setupService.addMember("Thabo").member();
    }

    // ------------------------------------------------------- the ordinary payout

    /**
     * Everyone paid and the recipient owes nothing. The payout row is the whole pot
     * and no deduction payment exists at all — not a zero-amount one. A payout
     * without a deduction is the normal case.
     */
    @Test
    void a_payout_with_no_arrears_writes_no_deduction_payment() {
        everyonePays(CONTRIBUTION);

        Payout payout = payoutService.firePayout(cycleOne()).payout();

        assertThat(payout.getAmountPaid()).isEqualByComparingTo("1500.00");
        assertThat(payout.getRecipient().getId()).isEqualTo(john.getId());
        assertThat(paymentRepository.findByPayoutId(payout.getId()))
                .as("no arrears, so nothing flows back")
                .isEmpty();
    }

    /**
     * Rule 1 in its bluntest form. Nobody paid a cent, and the payout still fires on
     * the due date — for R0. There is no branch in firePayout that can decline, and
     * CHECK (amount_paid >= 0) allows the row precisely so this case is recordable.
     */
    @Test
    void an_empty_pot_still_fires_a_payout_for_zero() {
        Payout payout = payoutService.firePayout(cycleOne()).payout();

        assertThat(payout.getAmountPaid()).isEqualByComparingTo("0.00");
        assertThat(payoutRepository.findAll()).hasSize(1);
    }

    // --------------------------------------------------------------- the deduction

    /**
     * Rule 6. Sarah skipped January, so she owes John R500. When her own turn comes
     * she is not blocked — she receives her turn, and the R500 comes out of it.
     *
     * The payout row says R1,000, gross. The deduction is a separate R500 payment
     * with payout_id set. Net is the subtraction of the two, derived rather than
     * stored, which is the figure the ledger shows.
     *
     * Sarah deliberately does not contribute to her own cycle here. If she did, Rule
     * 7 would settle the January debt out of that payment the moment it arrived and
     * there would be nothing left for the payout to deduct — a real behaviour, and
     * the subject of its own test below, but not the one this test is about.
     */
    @Test
    void arrears_are_deducted_from_the_payout_and_the_row_stays_gross() {
        sarahMissesJanuaryAndOwesJohn();
        paymentService.recordPayment(john.getId(), CONTRIBUTION);
        paymentService.recordPayment(thabo.getId(), CONTRIBUTION);

        Payout payout = payoutService.firePayout(cycleTwo()).payout();

        assertThat(payout.getAmountPaid())
                .as("the row records what the pot held, before anything came out")
                .isEqualByComparingTo("1000.00");

        Payment deduction = paymentRepository.findByPayoutId(payout.getId()).orElseThrow();
        assertThat(deduction.getAmount()).isEqualByComparingTo("500.00");
        assertThat(deduction.getMember().getId()).isEqualTo(sarah.getId());
        assertThat(netReceived(payout)).isEqualByComparingTo("500.00");
    }

    /**
     * The interaction between Rules 6 and 7, which is easy to miss: a member who
     * clears their arrears *before* their own payout has nothing left to deduct.
     *
     * Sarah owes John R500 and pays her R500 contribution during her own cycle. Rule
     * 7 sends every cent of it to the January debt, so no deduction payment is
     * written when her payout fires — and her own pot is R500 lighter, because her
     * contribution never reached it. She takes no debt row for that shortfall either:
     * it is her own cycle, and nobody owes themselves.
     *
     * The money ends up in the same place by either route. What differs is which line
     * the ledger shows it on, and that is not a discrepancy — it is the difference
     * between paying a debt and having it taken off you.
     */
    @Test
    void arrears_cleared_before_the_payout_leave_nothing_to_deduct() {
        sarahMissesJanuaryAndOwesJohn();
        everyonePays(CONTRIBUTION);

        Payout payout = payoutService.firePayout(cycleTwo()).payout();

        assertThat(arrearsService.totalOutstandingFor(sarah))
                .as("her own payment already settled it, under Rule 7")
                .isEqualByComparingTo("0.00");
        assertThat(paymentRepository.findByPayoutId(payout.getId()))
                .as("nothing left to take")
                .isEmpty();
        assertThat(payout.getAmountPaid())
                .as("R500 short, because her contribution went to the debt instead")
                .isEqualByComparingTo("1000.00");
    }

    /** The deduction settles the debt it was raised against, and names the creditor. */
    @Test
    void the_deduction_settles_the_debt_and_leaves_the_creditor_named() {
        sarahMissesJanuaryAndOwesJohn();
        paymentService.recordPayment(john.getId(), CONTRIBUTION);
        paymentService.recordPayment(thabo.getId(), CONTRIBUTION);

        Payout payout = payoutService.firePayout(cycleTwo()).payout();
        Payment deduction = paymentRepository.findByPayoutId(payout.getId()).orElseThrow();

        List<Allocation> settled = allocationRepository.findByPaymentId(deduction.getId());
        assertThat(settled).hasSize(1);
        assertThat(settled.getFirst().getCycle()).as("a deduction never reaches a pot").isNull();
        assertThat(settled.getFirst().getDebt().getCreditor().getId()).isEqualTo(john.getId());
        assertThat(arrearsService.totalOutstandingFor(sarah))
                .as("settled in full, and derived — not a flag that was flipped")
                .isEqualByComparingTo("0.00");
    }

    /**
     * The floor, and the whole reason it is a min rather than a clamp. Sarah owes
     * R500 and her pot holds only R300, so the deduction is capped at R300 and she
     * receives nothing — max(0, 300 - 500) with no branch anywhere.
     *
     * She is still R200 short afterwards. The debt row was never decremented; what
     * changed is that R300 of allocations now sit against it. That remainder is the
     * "standing risk, deliberately visible" note under Rule 7: the software cannot
     * compel her to repay it, and it stays on the ledger until she does.
     */
    @Test
    void a_deduction_larger_than_the_pot_is_capped_and_the_member_receives_nothing() {
        sarahMissesJanuaryAndOwesJohn();
        paymentService.recordPayment(john.getId(), new BigDecimal("300.00"));

        Payout payout = payoutService.firePayout(cycleTwo()).payout();

        assertThat(payout.getAmountPaid()).isEqualByComparingTo("300.00");
        assertThat(paymentRepository.findByPayoutId(payout.getId()).orElseThrow().getAmount())
                .as("capped at the pot, not at what she owed")
                .isEqualByComparingTo("300.00");
        assertThat(netReceived(payout)).isEqualByComparingTo("0.00");
        assertThat(arrearsService.totalOutstandingFor(sarah))
                .as("partially settled, and still visible")
                .isEqualByComparingTo("200.00");
    }

    /**
     * Owed everything, pot holds nothing. min(0, owed) is zero, so no payment row is
     * written at all — the guard is what stops CHECK (amount > 0) rejecting a
     * zero-amount deduction and rolling back an otherwise valid payout.
     */
    @Test
    void an_empty_pot_deducts_nothing_even_when_arrears_are_owed() {
        sarahMissesJanuaryAndOwesJohn();

        Payout payout = payoutService.firePayout(cycleTwo()).payout();

        assertThat(payout.getAmountPaid()).isEqualByComparingTo("0.00");
        assertThat(paymentRepository.findByPayoutId(payout.getId())).isEmpty();
        assertThat(arrearsService.totalOutstandingFor(sarah))
                .as("nothing was available to settle with")
                .isEqualByComparingTo("500.00");
    }

    // ------------------------------------------------------------- the refusals

    /**
     * recordArrearsDeduction refuses more than the member owes. PayoutService caps at
     * min(pot, owed) so this cannot happen through the normal path — which is exactly
     * why it is worth asserting directly. A remainder here would have to land in a
     * pot, turning a deduction into a contribution the member never made.
     */
    @Test
    void a_deduction_exceeding_what_is_owed_is_refused() {
        sarahMissesJanuaryAndOwesJohn();     // she owes exactly R500
        Payout payout = payoutRepository.save(new Payout(
                cycleTwo(), sarah, new BigDecimal("1500.00"), instantNow()));

        assertThatThrownBy(() -> paymentService.recordArrearsDeduction(payout, new BigDecimal("900.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot exceed what the member owes");
    }

    /** Nothing owed at all — every cent of the deduction would be left over. */
    @Test
    void a_deduction_against_a_member_who_owes_nothing_is_refused() {
        everyonePays(CONTRIBUTION);
        Payout payout = payoutRepository.save(new Payout(
                cycleOne(), john, new BigDecimal("1500.00"), instantNow()));

        assertThatThrownBy(() -> paymentService.recordArrearsDeduction(payout, CONTRIBUTION))
                .isInstanceOf(IllegalStateException.class);
    }

    // ----------------------------------------------------------------- fixtures

    private Cycle cycleOne() {
        return cycleRepository.findAllByOrderBySequenceNumberAsc().get(0);
    }

    private Cycle cycleTwo() {
        return cycleRepository.findAllByOrderBySequenceNumberAsc().get(1);
    }

    /** Everyone contributes to whichever cycle is currently open. */
    private void everyonePays(BigDecimal amount) {
        List.of(john, sarah, thabo).forEach(m -> paymentService.recordPayment(m.getId(), amount));
    }

    /**
     * Runs cycle 1 for real: John and Thabo pay, Sarah does not, and the payout
     * writes her debt to John. Produced by firePayout rather than by a hand-written
     * debt row, so what 3b is tested against is what 3a actually writes.
     */
    private void sarahMissesJanuaryAndOwesJohn() {
        paymentService.recordPayment(john.getId(), CONTRIBUTION);
        paymentService.recordPayment(thabo.getId(), CONTRIBUTION);
        payoutService.firePayout(cycleOne());
        assertThat(arrearsService.totalOutstandingFor(sarah)).isEqualByComparingTo("500.00");
    }

    /**
     * Rule 6's net, derived the way CLAUDE.md specifies: the gross payout minus the
     * deduction payment's own amount, not a re-sum of the allocations underneath it.
     */
    private BigDecimal netReceived(Payout payout) {
        return payout.getAmountPaid().subtract(paymentRepository.findByPayoutId(payout.getId())
                .map(Payment::getAmount)
                .orElse(BigDecimal.ZERO));
    }

    private java.time.Instant instantNow() {
        return START.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }
}
