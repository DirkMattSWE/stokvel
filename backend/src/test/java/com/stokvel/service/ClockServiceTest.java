package com.stokvel.service;

import com.stokvel.model.Member;
import jakarta.persistence.EntityManager;
import com.stokvel.repository.CycleRepository;
import com.stokvel.repository.MemberRepository;
import com.stokvel.repository.PayoutRepository;
import com.stokvel.repository.StokvelConfigRepository;
import com.stokvel.service.PayoutService.PayoutOutcome;
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
 * Rule 8 — the clock. current_date lives in the database and business logic never
 * reads system time, so the whole of this class is: set a date, call a method,
 * assert. No Thread.sleep, no mocking of LocalDate.now(), nothing flaky.
 *
 * Rule 1 rides along with it — a payout fires because a due date passed, and for
 * whatever the pot happens to hold. advanceClock pays nobody; it moves the date and
 * asks what that made due.
 *
 * The stokvel is created with a START in 2020 on purpose. Every assertion below
 * would still have to hold in 2030, and a test that only passes because the real
 * calendar happens to agree is not testing Rule 8 at all.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Import({StokvelSetupService.class, PaymentService.class, ArrearsService.class,
        PayoutService.class, ClockService.class})
class ClockServiceTest {

    /** Well in the past, so no assertion here can be satisfied by today's real date. */
    private static final LocalDate START = LocalDate.of(2020, 1, 15);
    private static final BigDecimal CONTRIBUTION = new BigDecimal("500.00");

    // A stokvel created on 2020-01-15 opens on the first month-end after that, and
    // chains a month at a time from there — one cycle per member (Rule 9).
    private static final LocalDate CYCLE_1_DUE = LocalDate.of(2020, 2, 29);
    private static final LocalDate CYCLE_2_DUE = LocalDate.of(2020, 3, 31);
    private static final LocalDate CYCLE_3_DUE = LocalDate.of(2020, 4, 30);

    /**
     * Transport, not a rule — mocked rather than wired. The services call it at the
     * end of a mutating method; what it sends is LedgerService's job and is tested
     * there, and a STOMP broker has no business inside a @DataJpaTest.
     */
    @MockitoBean
    private LedgerBroadcaster broadcaster;

    @Autowired
    private ClockService clockService;
    @Autowired
    private StokvelSetupService setupService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private CycleRepository cycleRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private PayoutRepository payoutRepository;
    @Autowired
    private StokvelConfigRepository configRepository;
    @Autowired
    private EntityManager entityManager;

    // ------------------------------------------------------------- moving the date

    /**
     * The date is written to the database, not held in a field. A restart mid-demo
     * must not reset the clock, which is why stokvel.db is file-backed — so the
     * assertion is made after flushing and clearing, against what actually landed.
     */
    @Test
    void advancing_the_clock_stores_the_new_date() {
        createStokvel(1, "John", "Sarah", "Thabo");

        clockService.advanceClock(LocalDate.of(2020, 1, 31));

        entityManager.flush();
        entityManager.clear();
        assertThat(configRepository.require().getCurrentDate())
                .isEqualTo(LocalDate.of(2020, 1, 31));
    }

    /**
     * Time is monotonic. Rewinding is not corruption — a payout row closes its cycle,
     * so nothing can re-fire — but it would leave debts and payouts stamped after
     * "today", and a ledger that reads as impossible is worse than a refusal.
     */
    @Test
    void the_clock_cannot_move_backwards() {
        createStokvel(1, "John", "Sarah", "Thabo");

        assertThatThrownBy(() -> clockService.advanceClock(START.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot move backwards");

        assertThat(configRepository.require().getCurrentDate())
                .as("a refused advance leaves the clock where it was")
                .isEqualTo(START);
    }

    // ------------------------------------------------------- what the date makes due

    /**
     * Rule 1, stated as its own absence: moving the clock to the day before a due
     * date fires nothing. The payout is not triggered by advancing, by the pot being
     * full, or by anyone asking — only by the date arriving.
     */
    @Test
    void a_date_short_of_the_due_date_fires_nothing() {
        createStokvel(1, "John", "Sarah", "Thabo");

        assertThat(clockService.advanceClock(CYCLE_1_DUE.minusDays(1))).isEmpty();
        assertThat(payoutRepository.findAll()).isEmpty();
    }

    /**
     * And on the due date itself it fires — for R0, because nobody paid. Rule 1 pays
     * whatever is actually in the pot, with no delay and no judgment call.
     */
    @Test
    void the_due_date_arriving_fires_the_payout_for_whatever_the_pot_holds() {
        createStokvel(1, "John", "Sarah", "Thabo");

        List<PayoutOutcome> fired = clockService.advanceClock(CYCLE_1_DUE);

        assertThat(fired).hasSize(1);
        assertThat(fired.getFirst().payout().getRecipient().getName()).isEqualTo("John");
        assertThat(fired.getFirst().payout().getAmountPaid()).isEqualByComparingTo("0.00");
    }

    /**
     * A jump of several months fires every cycle it passed, oldest first. The order
     * is load-bearing rather than cosmetic: each payout writes debt rows that the
     * next payout's arrears deduction has to see (Rule 6), so cycle 2 must finish
     * before cycle 3 begins.
     */
    @Test
    void a_multi_month_jump_fires_every_cycle_it_passed_oldest_first() {
        createStokvel(1, "John", "Sarah", "Thabo");

        List<PayoutOutcome> fired = clockService.advanceClock(CYCLE_3_DUE);

        assertThat(fired)
                .extracting(outcome -> outcome.payout().getCycle().getSequenceNumber())
                .containsExactly(1, 2, 3);
        assertThat(fired)
                .extracting(outcome -> outcome.payout().getRecipient().getName())
                .containsExactly("John", "Sarah", "Thabo");
    }

    /**
     * The invariant one, and the reason checkDue re-queries instead of iterating a
     * single findDueCycles() result.
     *
     * Rotation 2 does not exist when the loop starts — rotations are generated one at
     * a time, never in advance (Rule 9) — and it is written inline by the payout that
     * closes rotation 1. Jumping to 2020-06-30 passes rotation 1 entirely (Feb, Mar,
     * Apr) and then two cycles of a rotation that was created *during* the loop
     * (May 31, Jun 30). Five payouts, not three.
     *
     * A for-each over one snapshot returns three and leaves the clock sitting in June
     * with two payouts that silently never happened. That failure has no error and no
     * missing column — just a ledger quietly short of two entries — which is why it
     * is asserted here rather than trusted.
     */
    @Test
    void a_jump_that_closes_a_rotation_also_fires_the_new_rotations_due_cycles() {
        createStokvel(2, "John", "Sarah", "Thabo");

        List<PayoutOutcome> fired = clockService.advanceClock(LocalDate.of(2020, 6, 30));

        assertThat(fired)
                .as("three from rotation 1, then two from a rotation born mid-loop")
                .extracting(outcome -> outcome.payout().getCycle().getSequenceNumber())
                .containsExactly(1, 2, 3, 4, 5);

        assertThat(cycleRepository.findAll()).hasSize(6);
        assertThat(cycleRepository.findOpenCycle().orElseThrow().getDueDate())
                .as("cycle 6 falls in July, so the loop correctly stopped short of it")
                .isEqualTo(LocalDate.of(2020, 7, 31));
    }

    /**
     * Advancing again over ground already covered fires nothing a second time. What
     * closes a cycle is the existence of its payout row, not the date — so a cycle
     * that has paid out can never come back from findDueCycles(), which is also why
     * the loop in checkDue terminates.
     */
    @Test
    void a_cycle_that_has_paid_out_never_fires_again() {
        createStokvel(1, "John", "Sarah", "Thabo");
        clockService.advanceClock(CYCLE_2_DUE);

        assertThat(clockService.advanceClock(CYCLE_2_DUE.plusDays(1))).isEmpty();
        assertThat(payoutRepository.findAll()).hasSize(2);
    }

    // ------------------------------------------------------ what comes back out (Rule 6)

    /**
     * Rule 6 through the clock: the recipient still takes their turn, and what they
     * owe comes off what they receive.
     *
     * John is paid out in cycle 1 with an empty pot, which leaves Sarah and Thabo
     * each owing him R500. John then pays R500 into cycle 2 — Sarah's turn — and he
     * owes nothing, so all of it reaches the pot. When cycle 2 fires, Sarah's payout
     * is gross R500 and every cent of it goes straight back out against her debt to
     * John. She receives nothing, and she is square.
     *
     * Asserted on the PayoutOutcome rather than by re-reading rows, because that pair
     * is exactly what the clock hands its caller: gross, deducted, and the net
     * derived from the two.
     */
    @Test
    void a_payout_carries_the_deduction_that_came_back_out_of_it() {
        createStokvel(1, "John", "Sarah", "Thabo");
        clockService.advanceClock(CYCLE_1_DUE);
        paymentService.recordPayment(memberNamed("John"), new BigDecimal("500.00"));

        PayoutOutcome sarah = clockService.advanceClock(CYCLE_2_DUE).getFirst();

        assertThat(sarah.payout().getAmountPaid()).as("gross").isEqualByComparingTo("500.00");
        assertThat(sarah.deducted()).as("her arrears to John").isEqualByComparingTo("500.00");
        assertThat(sarah.netReceived()).as("net").isEqualByComparingTo("0.00");
        assertThat(sarah.deduction().allocations())
                .extracting(allocation -> allocation.getDebt().getCreditor().getName())
                .containsExactly("John");
    }

    /** No deduction row when nothing is owed — the ordinary case, not a missing value. */
    @Test
    void a_payout_to_a_member_who_owes_nothing_carries_no_deduction() {
        createStokvel(1, "John", "Sarah", "Thabo");

        PayoutOutcome john = clockService.advanceClock(CYCLE_1_DUE).getFirst();

        assertThat(john.deduction()).isNull();
        assertThat(john.deducted()).isEqualByComparingTo("0.00");
        assertThat(john.netReceived()).isEqualByComparingTo(john.payout().getAmountPaid());
    }

    // ----------------------------------------------------------------- fixtures

    private void createStokvel(int rotationCount, String... names) {
        setupService.createStokvel(CONTRIBUTION, START, rotationCount);
        for (String name : names) {
            setupService.addMember(name);
        }
    }

    private Long memberNamed(String name) {
        return memberRepository.findAllByOrderByCreatedAtAscIdAsc().stream()
                .filter((Member member) -> member.getName().equals(name))
                .findFirst()
                .orElseThrow()
                .getId();
    }
}
