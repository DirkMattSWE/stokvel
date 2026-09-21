package com.stokvel.service;

import com.stokvel.model.Allocation;
import com.stokvel.model.Cycle;
import com.stokvel.model.Debt;
import com.stokvel.model.Member;
import com.stokvel.model.Payout;
import com.stokvel.repository.AllocationRepository;
import com.stokvel.repository.CycleRepository;
import com.stokvel.repository.DebtRepository;
import com.stokvel.repository.PayoutRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule 7 — a payment's allocations clear outstanding debts in created_at order,
 * oldest first, and only the remainder reaches the current cycle's pot.
 *
 * These are the happy-path assertions: what the split produces when the input is
 * ordinary. The invariants (the debt row is never decremented, the pot is derived)
 * and the refusals (a deduction capped at what is owed) are separate passes.
 *
 * The debt rows here are written directly by the test, not produced by a payout —
 * PayoutService does not exist yet. That is the cost CLAUDE.md's build order records
 * knowingly: finishing PaymentService pulled Rule 7 ahead of the service that writes
 * the debt rows it settles.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Import({StokvelSetupService.class, PaymentService.class, ArrearsService.class})
class PaymentServiceDebtAllocationTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 15);
    private static final BigDecimal CONTRIBUTION = new BigDecimal("500.00");

    /**
     * Two timestamps a month apart, for stamping debt rows in a known order — Rule 7
     * settles oldest first, so only their order matters here, not the dates
     * themselves.
     */
    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 31);
    private static final LocalDate FEBRUARY = LocalDate.of(2026, 2, 28);

    @Autowired
    private StokvelSetupService setupService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private CycleRepository cycleRepository;
    @Autowired
    private AllocationRepository allocationRepository;
    @Autowired
    private DebtRepository debtRepository;
    @Autowired
    private PayoutRepository payoutRepository;
    @Autowired
    private ArrearsService arrearsService;

    @PersistenceContext
    private EntityManager entityManager;

    private Member john;
    private Member sarah;
    private Member thabo;

    /**
     * Three members, so three cycles: 1 pays John (28 Feb), 2 pays Sarah (31 Mar),
     * 3 pays Thabo (30 Apr). One rotation.
     *
     * The stokvel is created on 15 Jan but its first cycle is due at the end of
     * February, not January — a stokvel starts on the first of the month after it is
     * created, so no cycle is ever shorter than a full month.
     */
    @BeforeEach
    void createStokvelWithThreeMembers() {
        setupService.createStokvel(CONTRIBUTION, START, 1);
        john = setupService.addMember("John").member();
        sarah = setupService.addMember("Sarah").member();
        thabo = setupService.addMember("Thabo").member();
    }

    /**
     * Case A — pays more than owed. Sarah missed January, so she owes John R200.
     * She pays her full R500 in February: R200 settles the debt, R300 reaches the
     * February pot. One payment row, two allocation rows.
     */
    @Test
    void a_payment_settles_debt_first_and_the_remainder_reaches_the_pot() {
        Cycle january = closeCycle(openCycle());
        Debt owedToJohn = debtFrom(sarah, john, january, "200.00", JANUARY);

        List<Allocation> allocations =
                paymentService.recordPayment(sarah.getId(), CONTRIBUTION).allocations();

        assertThat(allocations).hasSize(2);

        Allocation toDebt = allocations.get(0);
        assertThat(toDebt.getDebt().getId()).isEqualTo(owedToJohn.getId());
        assertThat(toDebt.getCycle()).as("a debt allocation has no cycle").isNull();
        assertThat(toDebt.getAmount()).isEqualByComparingTo("200.00");

        Allocation toPot = allocations.get(1);
        assertThat(toPot.getCycle().getId()).isEqualTo(openCycle().getId());
        assertThat(toPot.getDebt()).as("a pot allocation has no debt").isNull();
        assertThat(toPot.getAmount()).isEqualByComparingTo("300.00");
    }

    /**
     * Case B — pays less than owed. Every cent goes to the debt and the pot sees
     * nothing. The debt is left partially settled, which needed no code of its own.
     */
    @Test
    void a_payment_smaller_than_the_debt_reaches_no_pot_at_all() {
        Cycle january = closeCycle(openCycle());
        debtFrom(sarah, john, january, "900.00", JANUARY);

        List<Allocation> allocations =
                paymentService.recordPayment(sarah.getId(), CONTRIBUTION).allocations();

        assertThat(allocations).hasSize(1);
        assertThat(allocations.getFirst().getDebt()).isNotNull();
        assertThat(allocations.getFirst().getAmount()).isEqualByComparingTo("500.00");
        assertThat(potFor(openCycle())).as("nothing survived the debt").isEqualByComparingTo("0.00");
    }

    /**
     * The boundary: paid exactly what is owed, to the cent. The remainder is exactly
     * zero, so no pot allocation is written at all — a zero-amount row would be a
     * fact about nothing, and CHECK (amount > 0) would reject it anyway.
     */
    @Test
    void a_payment_matching_the_debt_exactly_writes_no_pot_allocation() {
        Cycle january = closeCycle(openCycle());
        debtFrom(sarah, john, january, "500.00", JANUARY);

        List<Allocation> allocations =
                paymentService.recordPayment(sarah.getId(), CONTRIBUTION).allocations();

        assertThat(allocations).hasSize(1);
        assertThat(allocations.getFirst().getCycle()).isNull();
        assertThat(potFor(openCycle())).isEqualByComparingTo("0.00");
    }

    /**
     * Case C — several debts, oldest first. Thabo missed both January and February,
     * so he owes John R200 (written first) and Sarah R300 (written second). He pays
     * R250 in March: John's debt is cleared, Sarah's takes what is left.
     *
     * The order is not decided here. It is decided by the query that built the list
     * — findAllByDebtorOrderByCreatedAtAscIdAsc — and settleDebts walks it as given.
     */
    @Test
    void several_debts_are_settled_oldest_first() {
        Cycle january = closeCycle(openCycle());
        Cycle february = closeCycle(openCycle());
        Debt owedToJohn = debtFrom(thabo, john, january, "200.00", JANUARY);
        Debt owedToSarah = debtFrom(thabo, sarah, february, "300.00", FEBRUARY);

        List<Allocation> allocations =
                paymentService.recordPayment(thabo.getId(), new BigDecimal("250.00")).allocations();

        assertThat(allocations).hasSize(2);

        assertThat(allocations.get(0).getDebt().getId()).isEqualTo(owedToJohn.getId());
        assertThat(allocations.get(0).getAmount())
                .as("the older debt is cleared in full first").isEqualByComparingTo("200.00");

        assertThat(allocations.get(1).getDebt().getId()).isEqualTo(owedToSarah.getId());
        assertThat(allocations.get(1).getAmount())
                .as("the newer debt takes what survived").isEqualByComparingTo("50.00");

        assertThat(potFor(openCycle())).isEqualByComparingTo("0.00");
    }

    /**
     * The break. Thabo owes the same two debts but pays only R150 — the money runs
     * out before the list does, so the loop stops rather than reaching Sarah's debt
     * with nothing to give it. One allocation, not two with a zero on the end.
     */
    @Test
    void settlement_stops_when_the_money_runs_out_before_the_debts_do() {
        Cycle january = closeCycle(openCycle());
        Cycle february = closeCycle(openCycle());
        Debt owedToJohn = debtFrom(thabo, john, january, "200.00", JANUARY);
        Debt owedToSarah = debtFrom(thabo, sarah, february, "300.00", FEBRUARY);

        List<Allocation> allocations =
                paymentService.recordPayment(thabo.getId(), new BigDecimal("150.00")).allocations();

        assertThat(allocations).hasSize(1);
        assertThat(allocations.getFirst().getDebt().getId()).isEqualTo(owedToJohn.getId());
        assertThat(allocations.getFirst().getAmount()).isEqualByComparingTo("150.00");

        assertThat(allocationRepository.sumByDebtId(owedToSarah.getId()))
                .as("the loop never reached the second debt").isEqualByComparingTo("0.00");
    }

    /**
     * The invariant the whole schema is built around: a debt is a fact, and facts
     * are not edited. After R500 is settled against a R900 debt, the debt row still
     * says R900 — what changed is that an allocation row now exists beside it.
     *
     * The flush/clear is what makes this assertion mean anything. Without it the
     * re-read returns the same in-memory instance the test already has, and an
     * UPDATE that never reached the database would pass unnoticed.
     */
    @Test
    void settling_a_debt_never_changes_the_debt_row() {
        Cycle january = closeCycle(openCycle());
        Debt owedToJohn = debtFrom(sarah, john, january, "900.00", JANUARY);

        paymentService.recordPayment(sarah.getId(), CONTRIBUTION);

        entityManager.flush();
        entityManager.clear();

        Debt fromDatabase = debtRepository.findById(owedToJohn.getId()).orElseThrow();
        assertThat(fromDatabase.getAmount())
                .as("never decremented — no status, no amount_remaining").isEqualByComparingTo("900.00");
        assertThat(allocationRepository.sumByDebtId(fromDatabase.getId()))
                .as("the settlement lives in the allocation row instead").isEqualByComparingTo("500.00");
    }

    /**
     * One payment, two destinations, and the pot query picks out only its own half.
     * Nothing filters debt allocations explicitly — they carry cycle_id = null, so
     * SUM(amount) WHERE cycle_id = ? excludes them for free. That is the property
     * that let Rule 7 be added without touching the pot query at all.
     */
    @Test
    void the_pot_counts_only_what_survived_the_debt() {
        Cycle january = closeCycle(openCycle());
        debtFrom(sarah, john, january, "200.00", JANUARY);

        paymentService.recordPayment(sarah.getId(), CONTRIBUTION);

        assertThat(potFor(openCycle()))
                .as("R500 arrived but R200 of it went to a debt").isEqualByComparingTo("300.00");
    }

    /**
     * Outstanding is re-derived on every ask, not carried on the row. Sarah pays
     * R500 against a R900 debt, then R500 again: the second payment sees R400 owed,
     * not R900, and the R100 that survives reaches the pot.
     *
     * This is the test that fails the day someone adds an amount_remaining column
     * and forgets to keep it in step.
     */
    @Test
    void a_second_payment_settles_against_what_is_left_not_the_original_amount() {
        Cycle january = closeCycle(openCycle());
        Debt owedToJohn = debtFrom(sarah, john, january, "900.00", JANUARY);

        paymentService.recordPayment(sarah.getId(), CONTRIBUTION);
        List<Allocation> second =
                paymentService.recordPayment(sarah.getId(), CONTRIBUTION).allocations();

        assertThat(second).hasSize(2);
        assertThat(second.get(0).getAmount())
                .as("only the R400 still outstanding").isEqualByComparingTo("400.00");
        assertThat(second.get(1).getAmount()).isEqualByComparingTo("100.00");

        assertThat(allocationRepository.sumByDebtId(owedToJohn.getId())).isEqualByComparingTo("900.00");
        assertThat(potFor(openCycle())).isEqualByComparingTo("100.00");
    }

    /**
     * A settled debt is dropped from the arrears list rather than returned as zero,
     * so the next payment has nothing to settle and goes straight to the pot. No
     * flag was flipped to make that happen — the subtraction simply stopped being
     * positive.
     */
    @Test
    void a_fully_settled_debt_leaves_the_arrears_list_entirely() {
        Cycle january = closeCycle(openCycle());
        debtFrom(sarah, john, january, "500.00", JANUARY);

        paymentService.recordPayment(sarah.getId(), CONTRIBUTION);
        assertThat(arrearsService.outstandingFor(sarah)).isEmpty();

        List<Allocation> next =
                paymentService.recordPayment(sarah.getId(), CONTRIBUTION).allocations();

        assertThat(next).hasSize(1);
        assertThat(next.getFirst().getCycle()).as("straight to the pot").isNotNull();
        assertThat(potFor(openCycle())).isEqualByComparingTo("500.00");
    }

    /**
     * The figure Rule 6 will deduct from a payout, and it is a sum taken fresh each
     * time. Thabo owes R200 and R300; after R250 is settled the total is R250, with
     * neither debt row touched.
     */
    @Test
    void total_arrears_is_summed_from_the_allocations_every_time_it_is_asked() {
        Cycle january = closeCycle(openCycle());
        Cycle february = closeCycle(openCycle());
        debtFrom(thabo, john, january, "200.00", JANUARY);
        debtFrom(thabo, sarah, february, "300.00", FEBRUARY);

        assertThat(arrearsService.totalOutstandingFor(thabo)).isEqualByComparingTo("500.00");

        paymentService.recordPayment(thabo.getId(), new BigDecimal("250.00"));

        assertThat(arrearsService.totalOutstandingFor(thabo)).isEqualByComparingTo("250.00");
    }

    private Cycle openCycle() {
        return cycleRepository.findOpenCycle().orElseThrow();
    }

    private BigDecimal potFor(Cycle cycle) {
        return allocationRepository.sumByCycleId(cycle.getId());
    }

    /**
     * Closes a cycle the way the system does — by the existence of a payout row, not
     * by a date. Returns the cycle it closed, since the debts written against it are
     * the point of closing it.
     */
    private Cycle closeCycle(Cycle cycle) {
        payoutRepository.save(new Payout(cycle, cycle.getRecipient(), potFor(cycle), instantOn(START)));
        return cycle;
    }

    /**
     * A debt row written directly. PayoutService will be what writes these for real
     * (Rule 2), and when it exists these tests keep working unchanged — the rows are
     * the same rows.
     */
    private Debt debtFrom(Member debtor, Member creditor, Cycle cycle, String amount, LocalDate writtenOn) {
        return debtRepository.save(
                new Debt(debtor, creditor, cycle, new BigDecimal(amount), instantOn(writtenOn)));
    }

    private static Instant instantOn(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
