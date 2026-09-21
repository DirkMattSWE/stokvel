package com.stokvel.service;

import com.stokvel.model.Cycle;
import com.stokvel.model.Debt;
import com.stokvel.model.Member;
import com.stokvel.model.Payout;
import com.stokvel.model.StokvelConfig;
import com.stokvel.repository.AllocationRepository;
import com.stokvel.repository.CycleRepository;
import com.stokvel.repository.DebtRepository;
import com.stokvel.repository.PayoutRepository;
import com.stokvel.repository.StokvelConfigRepository;
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
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule 2 — a cycle's shortfall becomes tracked debt. One row per member who was
 * liable and did not cover their contribution, naming the cycle's recipient as
 * creditor: a person who was under-paid, not "the group" in the abstract.
 *
 * Pass 3a. firePayout does not yet write a payout row or deduct arrears (3b), nor
 * continue the rotation (3c), so these tests assert only what the debt table holds
 * afterwards.
 *
 * The suite splits the way the Rule 7 suite does. The first half checks the answer —
 * who gets a row and for how much. The second half checks that the answer is still
 * being *derived*: that liability comes from created_at against the due date rather
 * than from membership, that the recipient is exempted rather than rescued by the
 * database, and that "paid" means money that reached this pot rather than money that
 * changed hands.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Import({StokvelSetupService.class, BuyinService.class, PaymentService.class, ArrearsService.class, PayoutService.class})
class PayoutServiceDebtTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 15);
    private static final BigDecimal CONTRIBUTION = new BigDecimal("500.00");

    /** Cycle 1's due date: end of the month after creation, so February, not January. */
    private static final LocalDate CYCLE_ONE_DUE = LocalDate.of(2026, 2, 28);

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
    private DebtRepository debtRepository;
    @Autowired
    private AllocationRepository allocationRepository;
    @Autowired
    private PayoutRepository payoutRepository;
    @Autowired
    private StokvelConfigRepository configRepository;

    private Member john;
    private Member sarah;
    private Member thabo;

    /**
     * Three members, so three cycles: 1 pays John (28 Feb), 2 pays Sarah (31 Mar),
     * 3 pays Thabo (30 Apr). One rotation, R500 each.
     */
    @BeforeEach
    void createStokvelWithThreeMembers() {
        setupService.createStokvel(CONTRIBUTION, START, 1);
        john = setupService.addMember("John").member();
        sarah = setupService.addMember("Sarah").member();
        thabo = setupService.addMember("Thabo").member();
    }

    // ---------------------------------------------------------------- the answer

    /**
     * Nobody paid anything. Both non-recipients owe the full contribution, and John —
     * whose cycle it was — is owed by both of them.
     */
    @Test
    void every_liable_member_who_paid_nothing_owes_the_full_contribution() {
        payoutService.firePayout(cycleOne());

        assertThat(debtRepository.findAll()).hasSize(2);
        assertThat(debtOwedBy(sarah).getAmount()).isEqualByComparingTo("500.00");
        assertThat(debtOwedBy(thabo).getAmount()).isEqualByComparingTo("500.00");
        assertThat(debtOwedBy(sarah).getCreditor().getId())
                .as("the creditor is the member whose turn it was, by name")
                .isEqualTo(john.getId());
    }

    /** Part-paid owes only the difference; paid-in-full owes nothing at all. */
    @Test
    void a_part_payment_owes_the_difference_and_a_full_payment_owes_nothing() {
        paymentService.recordPayment(sarah.getId(), new BigDecimal("300.00"));
        paymentService.recordPayment(thabo.getId(), CONTRIBUTION);

        payoutService.firePayout(cycleOne());

        assertThat(debtRepository.findAll()).hasSize(1);
        assertThat(debtOwedBy(sarah).getAmount()).isEqualByComparingTo("200.00");
    }

    /**
     * Overpayment is not a negative debt. Sarah puts in R700; the excess lands in the
     * same pot, and the subtraction is floored at zero rather than writing a row with
     * a negative amount the CHECK would reject anyway.
     */
    @Test
    void overpaying_writes_no_row_and_no_negative_debt() {
        paymentService.recordPayment(sarah.getId(), new BigDecimal("700.00"));
        paymentService.recordPayment(thabo.getId(), CONTRIBUTION);

        payoutService.firePayout(cycleOne());

        assertThat(debtRepository.findAll()).isEmpty();
        assertThat(potFor(cycleOne())).isEqualByComparingTo("1200.00");
    }

    // ------------------------------------------------------------ the invariants

    /**
     * The recipient underpaid their own cycle. No debt row is written — nobody owes
     * themselves — and no exception is thrown either: the loop skips them, so
     * CHECK (debtor_id <> creditor_id) is never reached.
     *
     * The penalty is already paid, and it is visible in the arithmetic: John put in
     * R300 instead of R500, so the pot he is about to receive is R200 lighter. That
     * is the whole mechanism, and it needs no accounting of its own.
     */
    @Test
    void the_recipient_owes_nothing_for_their_own_cycle_and_simply_receives_less() {
        paymentService.recordPayment(john.getId(), new BigDecimal("300.00"));
        paymentService.recordPayment(sarah.getId(), CONTRIBUTION);
        paymentService.recordPayment(thabo.getId(), CONTRIBUTION);

        payoutService.firePayout(cycleOne());

        assertThat(debtRepository.findAll()).as("nobody owes themselves").isEmpty();
        assertThat(potFor(cycleOne()))
                .as("his own shortfall is the R200 missing from his own pot")
                .isEqualByComparingTo("1300.00");
    }

    /**
     * Rule 3. Erik joins in March, after cycle 1 fell due in February, and takes no
     * debt row for a round that was over before he arrived.
     *
     * Liability is derived from created_at against the due date, not from being in
     * the member list — which is why Rule 3 costs no code and no is_active column.
     */
    @Test
    void a_member_who_joined_after_the_due_date_was_never_liable_for_it() {
        everyoneExcept(john).forEach(member -> paymentService.recordPayment(member.getId(), CONTRIBUTION));
        setClockTo(LocalDate.of(2026, 3, 10));
        Member erik = setupService.addMember("Erik").member();

        payoutService.firePayout(cycleOne());

        assertThat(debtRepository.findAll()).isEmpty();
        assertThat(debtRepository.findAllByDebtorOrderByCreatedAtAscIdAsc(erik)).isEmpty();
    }

    /**
     * The boundary itself, and the reason it is strict. Erik joins *on* 28 February —
     * the day cycle 1 falls due and the day the payout fires. He had no opportunity to
     * pay, so a debt row would be punishing him for the hour he signed up.
     */
    @Test
    void a_member_who_joined_on_the_due_date_itself_is_out_not_in() {
        everyoneExcept(john).forEach(member -> paymentService.recordPayment(member.getId(), CONTRIBUTION));
        setClockTo(CYCLE_ONE_DUE);
        setupService.addMember("Erik");

        payoutService.firePayout(cycleOne());

        assertThat(debtRepository.findAll()).isEmpty();
    }

    /**
     * "Paid" means money that reached this cycle's pot — not money that changed hands.
     *
     * Thabo owes John R400 from cycle 1 and hands over R500 during cycle 2. Rule 7
     * settles the old debt first, so only R100 reaches the February pot and he takes a
     * fresh R400 debt row despite having paid a full contribution in cash.
     *
     * This is the standing risk CLAUDE.md records under Rule 7 arriving in practice:
     * debt compounds into debt. It is also the test that would fail the moment someone
     * "optimised" the comparison to sum payment.amount instead of allocations — which
     * would call Thabo paid up for a pot that never saw his money.
     */
    @Test
    void a_payment_swallowed_by_older_debt_still_leaves_the_member_short() {
        Cycle cycleOne = closeCycleWithoutPayingOut(cycleOne());
        debtRepository.save(new Debt(thabo, john, cycleOne, new BigDecimal("400.00"), instantOn(START)));

        paymentService.recordPayment(thabo.getId(), CONTRIBUTION);
        paymentService.recordPayment(john.getId(), CONTRIBUTION);

        payoutService.firePayout(cycleTwo());

        Debt fresh = debtOwedBy(thabo, cycleTwo());
        assertThat(fresh.getAmount())
                .as("R500 handed over, R100 in the pot, R400 still short")
                .isEqualByComparingTo("400.00");
        assertThat(fresh.getCreditor().getId())
                .as("owed to March's recipient, not to the member the old debt named")
                .isEqualTo(sarah.getId());
    }

    /**
     * Rule 8. The debt's created_at comes from the simulated clock, never the wall
     * clock — it is business data, because Rule 7 settles oldest first and this
     * timestamp is what decides the order a later payment pays them off in.
     */
    @Test
    void debt_rows_are_stamped_from_the_simulated_clock_not_the_wall_clock() {
        setClockTo(CYCLE_ONE_DUE);

        payoutService.firePayout(cycleOne());

        assertThat(debtRepository.findAll())
                .isNotEmpty()
                .allSatisfy(debt -> assertThat(debt.getCreatedAt()).isEqualTo(instantOn(CYCLE_ONE_DUE)));
    }

    // ----------------------------------------------------------------- fixtures

    private Cycle cycleOne() {
        return cycleRepository.findAllByOrderBySequenceNumberAsc().get(0);
    }

    private Cycle cycleTwo() {
        return cycleRepository.findAllByOrderBySequenceNumberAsc().get(1);
    }

    private BigDecimal potFor(Cycle cycle) {
        return allocationRepository.sumByCycleId(cycle.getId());
    }

    private List<Member> everyoneExcept(Member excluded) {
        return Stream.of(john, sarah, thabo)
                .filter(member -> !member.getId().equals(excluded.getId()))
                .toList();
    }

    /**
     * Moves cycle 2's payments off cycle 1 by closing it the way the system does —
     * the existence of a payout row, not a date. Deliberately not via firePayout:
     * that is 3b's job and does not exist yet.
     */
    private Cycle closeCycleWithoutPayingOut(Cycle cycle) {
        payoutRepository.save(new Payout(cycle, cycle.getRecipient(), potFor(cycle), instantOn(START)));
        return cycle;
    }

    private Debt debtOwedBy(Member debtor) {
        return debtRepository.findAllByDebtorOrderByCreatedAtAscIdAsc(debtor).getFirst();
    }

    private Debt debtOwedBy(Member debtor, Cycle cycle) {
        return debtRepository.findAllByDebtorOrderByCreatedAtAscIdAsc(debtor).stream()
                .filter(debt -> debt.getCycle().getId().equals(cycle.getId()))
                .findFirst()
                .orElseThrow();
    }

    /** The one mutable value in the system. ClockService will own this in pass 4. */
    private void setClockTo(LocalDate date) {
        StokvelConfig config = configRepository.require();
        config.setCurrentDate(date);
        configRepository.save(config);
    }

    private static Instant instantOn(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
