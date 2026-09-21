package com.stokvel.service;

import com.stokvel.model.Member;
import com.stokvel.repository.MemberRepository;
import com.stokvel.service.LedgerService.EntryType;
import com.stokvel.service.LedgerService.LedgerEntry;
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

/**
 * The ledger is a view, not a table. Every test here writes rows through the ordinary
 * services and then asks what the ledger says about them — because a ledger assembled
 * from hand-built rows would prove only that the mapping compiles.
 *
 * The two claims worth testing are that it hides nothing and that it is in order.
 * The order half is the one with a real failure mode: every row written on the same
 * simulated day carries a byte-identical created_at (Rule 8), so the sort cannot
 * lean on the timestamp alone.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Import({StokvelSetupService.class, PaymentService.class, ArrearsService.class,
        PayoutService.class, ClockService.class, LedgerService.class})
class LedgerServiceTest {

    private static final LocalDate START = LocalDate.of(2020, 1, 15);
    private static final BigDecimal CONTRIBUTION = new BigDecimal("500.00");
    private static final LocalDate CYCLE_1_DUE = LocalDate.of(2020, 2, 29);
    private static final LocalDate CYCLE_2_DUE = LocalDate.of(2020, 3, 31);

    /** Transport. The services call it; what it sends is asserted here directly. */
    @MockitoBean
    private LedgerBroadcaster broadcaster;

    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private StokvelSetupService setupService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private ClockService clockService;
    @Autowired
    private MemberRepository memberRepository;

    /** Nothing has happened yet, so the ledger says nothing. Not an error state. */
    @Test
    void a_stokvel_where_nothing_has_happened_has_an_empty_ledger() {
        createStokvel(1, "John", "Sarah", "Thabo");

        assertThat(ledgerService.getLedger()).isEmpty();
    }

    /**
     * One payment is one line, and where the money went hangs underneath it rather
     * than sitting beside it.
     *
     * John owes nothing, so his R500 goes straight to the open cycle's pot — one
     * slice, named. The allocation is not its own entry: it has no created_at of its
     * own and could never sort anywhere except next to this payment.
     */
    @Test
    void a_payment_is_one_line_with_its_allocations_underneath() {
        createStokvel(1, "John", "Sarah", "Thabo");

        paymentService.recordPayment(idOf("John"), new BigDecimal("500.00"));

        assertThat(ledgerService.getLedger()).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo(EntryType.PAYMENT);
            assertThat(entry.member()).isEqualTo("John");
            assertThat(entry.amount()).isEqualByComparingTo("500.00");
            assertThat(entry.slices()).singleElement().satisfies(slice -> {
                assertThat(slice.amount()).isEqualByComparingTo("500.00");
                assertThat(slice.destination()).isEqualTo("cycle 1 pot");
            });
        });
    }

    /**
     * Rule 7 splitting a payment across a debt and the pot shows as two slices on
     * one line — still one event, because one payment is what actually happened.
     *
     * Sarah owes John R500 from cycle 1. She pays R800: R500 settles the debt, R300
     * reaches cycle 2's pot.
     */
    @Test
    void a_split_payment_is_still_one_line_with_two_slices() {
        createStokvel(1, "John", "Sarah", "Thabo");
        clockService.advanceClock(CYCLE_1_DUE);

        paymentService.recordPayment(idOf("Sarah"), new BigDecimal("800.00"));

        LedgerEntry payment = onlyOfType(EntryType.PAYMENT);
        assertThat(payment.amount()).isEqualByComparingTo("800.00");
        assertThat(payment.slices())
                .extracting(slice -> slice.destination())
                .containsExactly("debt to John", "cycle 2 pot");
    }

    /**
     * Rule 2 — the debt names a creditor. "Thabo owes the group" would be the answer
     * that cannot be enforced or disputed; "Thabo owes John" is the one a member can
     * be held to.
     */
    @Test
    void a_debt_line_names_both_sides() {
        createStokvel(1, "John", "Sarah", "Thabo");

        clockService.advanceClock(CYCLE_1_DUE);

        assertThat(ledgerService.getLedger())
                .filteredOn(entry -> entry.type() == EntryType.DEBT)
                .extracting(LedgerEntry::member, LedgerEntry::counterparty)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Sarah", "John"),
                        org.assertj.core.groups.Tuple.tuple("Thabo", "John"));
    }

    /**
     * The one that matters. Three lines, one simulated day, one identical timestamp
     * on every row — and they still come back in the order the events happened.
     *
     * The clock reaches cycle 2's due date: Thabo's shortfall becomes a debt, Sarah
     * takes the pot gross, and her arrears to John come straight back out. All three
     * rows carry the same created_at to the byte, because simulatedNow() is the
     * clock's midnight (Rule 8). Sorting on the timestamp alone would shuffle them.
     *
     * This is the demo's key pair: PAYOUT R500 immediately followed by
     * DEDUCTION -R500, never the other way round.
     */
    @Test
    void lines_written_on_the_same_simulated_day_still_come_back_in_order() {
        createStokvel(1, "John", "Sarah", "Thabo");
        clockService.advanceClock(CYCLE_1_DUE);
        paymentService.recordPayment(idOf("John"), new BigDecimal("500.00"));

        clockService.advanceClock(CYCLE_2_DUE);

        List<LedgerEntry> sameDay = ledgerService.getLedger().stream()
                .filter(entry -> entry.at().equals(instantOf(CYCLE_2_DUE)))
                .toList();

        assertThat(sameDay)
                .extracting(LedgerEntry::type)
                .containsExactly(EntryType.DEBT,         // Thabo falls short
                        EntryType.PAYOUT,                 // Sarah takes the pot, gross
                        EntryType.DEDUCTION);             // and it goes back out again

        assertThat(sameDay).extracting(LedgerEntry::at).containsOnly(instantOf(CYCLE_2_DUE));
    }

    /**
     * The limitation, asserted rather than left to be discovered on stage.
     *
     * The simulated clock is a date with no time of day, so two events on one day are
     * genuinely indistinguishable by timestamp. John pays *after* cycle 1's payout has
     * already fired, and the ledger still lists his payment first, because within a
     * day the order is causal — money in, shortfalls, payout, deduction — rather than
     * the order the calls happened to arrive in.
     *
     * That is the right trade: the causal order is what makes a payout and its
     * deduction inseparable, which is the pair the whole of Rule 6 is explained with.
     * Arrival order would need a time of day on the clock, and a clock with a time of
     * day is a clock someone has to decide the cut-off hour for.
     */
    @Test
    void within_one_simulated_day_the_order_is_causal_not_the_order_of_arrival() {
        createStokvel(1, "John", "Sarah", "Thabo");
        clockService.advanceClock(CYCLE_1_DUE);

        paymentService.recordPayment(idOf("John"), new BigDecimal("500.00"));

        assertThat(ledgerService.getLedger())
                .filteredOn(entry -> entry.at().equals(instantOf(CYCLE_1_DUE)))
                .extracting(LedgerEntry::type)
                .as("the payment was recorded last, and is listed first")
                .containsExactly(EntryType.PAYMENT, EntryType.DEBT, EntryType.DEBT,
                        EntryType.PAYOUT);
    }

    /**
     * Rule 6 as the ledger tells it: gross and deduction are two lines, not one net
     * number. R500 received and R500 taken back is a truer account than "R0", because
     * it says who was paid and which debt was settled.
     */
    @Test
    void a_payout_and_its_deduction_are_two_lines_not_one_net_figure() {
        createStokvel(1, "John", "Sarah", "Thabo");
        clockService.advanceClock(CYCLE_1_DUE);
        paymentService.recordPayment(idOf("John"), new BigDecimal("500.00"));

        clockService.advanceClock(CYCLE_2_DUE);

        LedgerEntry payout = onlyOnCycle(EntryType.PAYOUT, 2);
        assertThat(payout.member()).isEqualTo("Sarah");
        assertThat(payout.amount()).as("gross, never net").isEqualByComparingTo("500.00");

        LedgerEntry deduction = onlyOfType(EntryType.DEDUCTION);
        assertThat(deduction.member()).isEqualTo("Sarah");
        assertThat(deduction.amount()).isEqualByComparingTo("500.00");
        assertThat(deduction.slices())
                .extracting(slice -> slice.destination())
                .containsExactly("debt to John");
    }

    /**
     * An auto-deduction is told apart from a voluntary payment by payout_id being
     * set — an explicit link, never inferred from timing or from the fact that it
     * settled a debt. A member settling arrears by choice on the same day is a
     * different event: paying a debt, rather than having it taken off you.
     */
    @Test
    void a_voluntary_payment_that_settles_a_debt_is_not_a_deduction() {
        createStokvel(1, "John", "Sarah", "Thabo");
        clockService.advanceClock(CYCLE_1_DUE);

        paymentService.recordPayment(idOf("Thabo"), new BigDecimal("500.00"));

        LedgerEntry entry = onlyOfType(EntryType.PAYMENT);
        assertThat(entry.member()).isEqualTo("Thabo");
        assertThat(entry.slices())
                .extracting(slice -> slice.destination())
                .containsExactly("debt to John");
        assertThat(ledgerService.getLedger())
                .noneMatch(line -> line.type() == EntryType.DEDUCTION);
    }

    /**
     * The invariant. The ledger is derived on every call, never accumulated — so a
     * fact that lands after someone has already read it is simply there the next
     * time, with no cache to invalidate and no line that can go missing.
     */
    @Test
    void the_ledger_is_derived_fresh_on_every_call() {
        createStokvel(1, "John", "Sarah", "Thabo");
        assertThat(ledgerService.getLedger()).isEmpty();

        paymentService.recordPayment(idOf("John"), new BigDecimal("500.00"));
        assertThat(ledgerService.getLedger()).hasSize(1);

        paymentService.recordPayment(idOf("Sarah"), new BigDecimal("500.00"));
        assertThat(ledgerService.getLedger()).hasSize(2);
    }

    // ----------------------------------------------------------------- fixtures

    private void createStokvel(int rotationCount, String... names) {
        setupService.createStokvel(CONTRIBUTION, START, rotationCount);
        for (String name : names) {
            setupService.addMember(name);
        }
    }

    private Long idOf(String name) {
        return memberRepository.findAllByOrderByCreatedAtAscIdAsc().stream()
                .filter((Member member) -> member.getName().equals(name))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private LedgerEntry onlyOnCycle(EntryType type, int cycleSequenceNumber) {
        List<LedgerEntry> matching = ledgerService.getLedger().stream()
                .filter(entry -> entry.type() == type)
                .filter(entry -> entry.cycleSequenceNumber() == cycleSequenceNumber)
                .toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private LedgerEntry onlyOfType(EntryType type) {
        List<LedgerEntry> matching = ledgerService.getLedger().stream()
                .filter(entry -> entry.type() == type)
                .toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    /** The simulated clock's instant for a date — midnight UTC, as simulatedNow() builds it. */
    private static java.time.Instant instantOf(LocalDate date) {
        return date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }
}
