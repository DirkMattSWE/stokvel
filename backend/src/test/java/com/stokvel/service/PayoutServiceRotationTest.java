package com.stokvel.service;

import com.stokvel.model.Cycle;
import com.stokvel.model.Member;
import com.stokvel.model.StokvelConfig;
import com.stokvel.repository.CycleRepository;
import com.stokvel.repository.StokvelConfigRepository;
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
 * Rule 9 — the rotation is exactly as long as the member count, and the stokvel runs
 * rotation_count of them. Rotations are generated one at a time, as a whole, and only
 * once the previous one has finished.
 *
 * Pass 3c. The generation runs inline inside firePayout because whether a cycle closed
 * its rotation is only knowable once its payout exists, and because the new rows have
 * to be in the database before the caller queries for due cycles again.
 *
 * Nobody pays anything in these tests. Rule 1 fires the payout regardless of the pot,
 * so an empty pot is the cheapest way to walk a rotation to its end — and the fact
 * that it works at all is itself the assertion that payouts are not conditional.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Import({StokvelSetupService.class, BuyinService.class, PaymentService.class, ArrearsService.class, PayoutService.class})
class PayoutServiceRotationTest {

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
    private PayoutService payoutService;
    @Autowired
    private CycleRepository cycleRepository;
    @Autowired
    private StokvelConfigRepository configRepository;

    /**
     * Three members and two rotations. Running rotation 1 to its end generates
     * rotation 2 whole: one cycle per member, same recipients in the same order,
     * because rotation order is creation order and is never reordered (Rule 5).
     */
    @Test
    void closing_a_rotation_generates_the_next_one_whole() {
        createStokvel(2, "John", "Sarah", "Thabo");

        runRotationToItsEnd();

        List<Cycle> cycles = cycleRepository.findAllWithRecipient();
        assertThat(cycles).hasSize(6);
        assertThat(cycles.subList(3, 6))
                .extracting(cycle -> cycle.getRecipient().getName())
                .containsExactly("John", "Sarah", "Thabo");
        assertThat(cycles.subList(3, 6))
                .allSatisfy(cycle -> assertThat(cycle.getRotationNumber()).isEqualTo(2));
    }

    /**
     * Sequence numbers carry straight on and due dates chain a month at a time off
     * the cycle that closed the rotation. Nothing existing is renumbered or moved —
     * that is the guarantee sequence_number is allowed to exist on.
     */
    @Test
    void the_new_rotation_continues_the_numbering_and_the_calendar() {
        createStokvel(2, "John", "Sarah", "Thabo");

        runRotationToItsEnd();

        assertThat(cycleRepository.findAllByOrderBySequenceNumberAsc())
                .extracting(Cycle::getSequenceNumber)
                .containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(cycleRepository.findAllByOrderBySequenceNumberAsc())
                .extracting(Cycle::getDueDate)
                .containsExactly(
                        LocalDate.of(2026, 2, 28),   // rotation 1
                        LocalDate.of(2026, 3, 31),
                        LocalDate.of(2026, 4, 30),
                        LocalDate.of(2026, 5, 31),   // rotation 2 picks up from there
                        LocalDate.of(2026, 6, 30),
                        LocalDate.of(2026, 7, 31));
    }

    /**
     * Rule 3 and Rule 9 meeting. Erik joins during rotation 1: addMember appends his
     * cycle at that rotation's tail, so rotation 1 becomes four cycles and closes on
     * *his* payout, not Thabo's. Rotation 2 is then read from the live member list
     * and he is simply in it — four cycles, no special case anywhere.
     */
    @Test
    void a_member_who_joined_mid_rotation_is_present_in_the_next_one() {
        createStokvel(2, "John", "Sarah", "Thabo");
        setClockTo(LocalDate.of(2026, 3, 15));
        setupService.addMember("Erik");

        runRotationToItsEnd();

        List<Cycle> cycles = cycleRepository.findAllWithRecipient();
        assertThat(cycles).hasSize(8);
        assertThat(cycles.subList(0, 4))
                .as("addMember extended rotation 1 at join time")
                .extracting(cycle -> cycle.getRecipient().getName())
                .containsExactly("John", "Sarah", "Thabo", "Erik");
        assertThat(cycles.subList(4, 8))
                .as("and rotation 2 is generated from the live list")
                .extracting(cycle -> cycle.getRecipient().getName())
                .containsExactly("John", "Sarah", "Thabo", "Erik");
    }

    /**
     * A one-rotation stokvel is genuinely finished when its last cycle pays out.
     * findOpenCycle() empty means the rotation is complete, not that more should be
     * generated — the rotation_number check against rotation_count is what tells
     * the two apart.
     */
    @Test
    void the_final_rotation_generates_nothing_and_the_stokvel_ends() {
        createStokvel(1, "John", "Sarah", "Thabo");

        runRotationToItsEnd();

        assertThat(cycleRepository.findAll()).hasSize(3);
        assertThat(cycleRepository.findOpenCycle())
                .as("no open cycle and nothing more to generate — this stokvel is over")
                .isEmpty();
    }

    /** Mid-rotation payouts generate nothing: the rotation still has cycles to run. */
    @Test
    void a_payout_that_does_not_close_the_rotation_generates_nothing() {
        createStokvel(2, "John", "Sarah", "Thabo");

        payoutService.firePayout(cycleRepository.findOpenCycle().orElseThrow());

        assertThat(cycleRepository.findAll()).hasSize(3);
        assertThat(cycleRepository.findOpenCycle().orElseThrow().getSequenceNumber()).isEqualTo(2);
    }

    /**
     * Three rotations, run end to end — the generation is not a one-off that happens
     * only at the first boundary. Nine cycles, three rotations of three, and nothing
     * left open at the end.
     */
    @Test
    void rotations_keep_generating_until_the_configured_count_is_reached() {
        createStokvel(3, "John", "Sarah", "Thabo");

        runRotationToItsEnd();

        assertThat(cycleRepository.findAll()).hasSize(9);
        assertThat(cycleRepository.findAllByOrderBySequenceNumberAsc())
                .extracting(Cycle::getRotationNumber)
                .containsExactly(1, 1, 1, 2, 2, 2, 3, 3, 3);
        assertThat(cycleRepository.findOpenCycle()).isEmpty();
    }

    // ----------------------------------------------------------------- fixtures

    private void createStokvel(int rotationCount, String... names) {
        setupService.createStokvel(CONTRIBUTION, START, rotationCount);
        for (String name : names) {
            setupService.addMember(name);
        }
    }

    /**
     * Fires every cycle that is open, one at a time, re-reading after each payout —
     * which is the shape ClockService.checkDue needs for the same reason: a payout
     * that closes a rotation adds cycles that were not there when the loop started.
     */
    private void runRotationToItsEnd() {
        for (Cycle open = openCycleOrNull(); open != null; open = openCycleOrNull()) {
            payoutService.firePayout(open);
        }
    }

    private Cycle openCycleOrNull() {
        return cycleRepository.findOpenCycle().orElse(null);
    }

    private void setClockTo(LocalDate date) {
        StokvelConfig config = configRepository.require();
        config.setCurrentDate(date);
        configRepository.save(config);
    }
}
