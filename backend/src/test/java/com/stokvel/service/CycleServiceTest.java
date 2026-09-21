package com.stokvel.service;

import com.stokvel.model.Member;
import com.stokvel.model.StokvelConfig;
import com.stokvel.repository.MemberRepository;
import com.stokvel.repository.StokvelConfigRepository;
import com.stokvel.service.CycleService.CycleState;
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
 * Both sides of a cycle's pot, derived on every call. Nothing here is stored, so the
 * tests that matter are the ones that would fail if someone later cached a total or
 * counted the wrong members.
 *
 * Created in 2020, like the clock's tests: an assertion that passes because the real
 * calendar agrees is not testing anything.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Import({StokvelSetupService.class, PaymentService.class, ArrearsService.class,
        PayoutService.class, ClockService.class, CycleService.class})
class CycleServiceTest {

    private static final LocalDate START = LocalDate.of(2020, 1, 15);
    private static final BigDecimal CONTRIBUTION = new BigDecimal("500.00");
    private static final LocalDate CYCLE_1_DUE = LocalDate.of(2020, 2, 29);

    /** Transport — the services call it at the end of a mutating method. */
    @MockitoBean
    private LedgerBroadcaster broadcaster;

    @Autowired
    private CycleService cycleService;
    @Autowired
    private StokvelSetupService setupService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private ClockService clockService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private StokvelConfigRepository configRepository;

    /**
     * A fresh stokvel: one cycle per member (Rule 9), every pot empty, every target
     * the full contribution from everyone. An empty pot is a knowable zero, not
     * missing data — which is why the sum is COALESCE'd in the query rather than
     * unwrapped by every caller.
     */
    @Test
    void a_fresh_stokvel_has_empty_pots_and_a_full_target() {
        createStokvel(1, "John", "Sarah", "Thabo");

        List<CycleState> cycles = cycleService.cycles();

        assertThat(cycles).hasSize(3);
        assertThat(cycles).allSatisfy(state -> {
            assertThat(state.collected()).isEqualByComparingTo("0.00");
            assertThat(state.target()).isEqualByComparingTo("1500.00");
            assertThat(state.shortfall()).isEqualByComparingTo("1500.00");
        });
    }

    /** A payment lands in the open cycle, and that is the pot that grows. */
    @Test
    void a_payment_raises_the_open_cycles_pot_and_nothing_else() {
        createStokvel(1, "John", "Sarah", "Thabo");

        paymentService.recordPayment(idOf("John"), CONTRIBUTION);

        // Compared numerically, never as strings: COALESCE(SUM(...), 0.00) comes back
        // from SQLite without the scale the DECIMAL(19,2) columns carry, so "500" and
        // "500.00" are the same money and a string comparison would be asserting the
        // driver's formatting rather than the amount.
        assertThat(cycleService.cycles())
                .extracting(CycleState::collected)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("500.00"),
                        new BigDecimal("0.00"),
                        new BigDecimal("0.00"));
    }

    /**
     * The invariant. Nothing caches: the figure moves on the next call with no
     * refresh, no invalidation and nothing to keep in step.
     */
    @Test
    void the_pot_is_derived_fresh_on_every_call() {
        createStokvel(1, "John", "Sarah", "Thabo");
        assertThat(firstCycle().collected()).isEqualByComparingTo("0.00");

        paymentService.recordPayment(idOf("John"), CONTRIBUTION);
        assertThat(firstCycle().collected()).isEqualByComparingTo("500.00");

        paymentService.recordPayment(idOf("Sarah"), CONTRIBUTION);
        assertThat(firstCycle().collected()).isEqualByComparingTo("1000.00");
    }

    /**
     * Money that settles a debt never reaches a pot, and the pot figure has to agree.
     *
     * Cycle 1 fires with nothing in it, leaving Sarah and Thabo owing John R500 each.
     * Sarah then pays R500 — Rule 7 sends all of it to her debt, so cycle 2's pot is
     * still empty. Reading the pot from payments rather than allocations would show
     * R500 here, and the recipient of cycle 2 would be promised money that is not
     * there.
     */
    @Test
    void money_that_settled_a_debt_never_shows_up_in_a_pot() {
        createStokvel(1, "John", "Sarah", "Thabo");
        clockService.advanceClock(CYCLE_1_DUE);

        paymentService.recordPayment(idOf("Sarah"), CONTRIBUTION);

        assertThat(cycleService.cycles().get(1).collected())
                .as("all of it went to her arrears")
                .isEqualByComparingTo("0.00");
    }

    /**
     * The one that earns wasLiableFor its place. Erik joins on 15 March, after cycle
     * 1 has already fallen due on 29 February.
     *
     * Cycle 1's target stays R1,500 forever. Counting today's four members would show
     * February as R500 short of a contribution Erik never owed — and it would
     * contradict the debt rows, which are written from the very same boundary.
     */
    @Test
    void a_cycles_target_counts_only_the_members_who_were_liable_for_it() {
        createStokvel(1, "John", "Sarah", "Thabo");
        setClockTo(LocalDate.of(2020, 3, 15));
        setupService.addMember("Erik");

        assertThat(cycleService.cycles())
                .extracting(state -> state.cycle().getDueDate())
                .containsExactly(
                        LocalDate.of(2020, 2, 29),
                        LocalDate.of(2020, 3, 31),
                        LocalDate.of(2020, 4, 30),
                        LocalDate.of(2020, 5, 31));   // the cycle Erik brought with him

        assertThat(cycleService.cycles())
                .extracting(CycleState::target)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(
                        new BigDecimal("1500.00"),    // Erik had not joined yet
                        new BigDecimal("2000.00"),    // joined 15 March, so liable
                        new BigDecimal("2000.00"),
                        new BigDecimal("2000.00"));
    }

    /**
     * Rule 3's boundary is strict. A member added *on* the due date is out: the
     * payout fires that same day, so they had no chance at all to pay.
     */
    @Test
    void a_member_added_on_the_due_date_is_not_counted_in_that_cycles_target() {
        createStokvel(1, "John", "Sarah", "Thabo");
        setClockTo(CYCLE_1_DUE);

        setupService.addMember("Erik");

        assertThat(firstCycle().target())
                .as("joined on 29 February, the day cycle 1 falls due")
                .isEqualByComparingTo("1500.00");
    }

    /**
     * A payout does not empty the pot. The allocations stay exactly where they were —
     * nothing is deleted, nothing decremented — so the figure a closed cycle shows is
     * the record of what its recipient actually received.
     */
    @Test
    void a_cycle_that_has_paid_out_still_shows_what_its_pot_held() {
        createStokvel(1, "John", "Sarah", "Thabo");
        paymentService.recordPayment(idOf("Sarah"), CONTRIBUTION);
        paymentService.recordPayment(idOf("Thabo"), CONTRIBUTION);

        clockService.advanceClock(CYCLE_1_DUE);

        assertThat(firstCycle().collected())
                .as("John received R1,000, and the record of it does not move")
                .isEqualByComparingTo("1000.00");
    }

    // ----------------------------------------------------------------- fixtures

    private void createStokvel(int rotationCount, String... names) {
        setupService.createStokvel(CONTRIBUTION, START, rotationCount);
        for (String name : names) {
            setupService.addMember(name);
        }
    }

    private CycleState firstCycle() {
        return cycleService.cycles().getFirst();
    }

    private Long idOf(String name) {
        return memberRepository.findAllByOrderByCreatedAtAscIdAsc().stream()
                .filter((Member member) -> member.getName().equals(name))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    /** Moves the clock without firing anything — these tests are about figures, not payouts. */
    private void setClockTo(LocalDate date) {
        StokvelConfig config = configRepository.require();
        config.setCurrentDate(date);
        configRepository.save(config);
    }
}
