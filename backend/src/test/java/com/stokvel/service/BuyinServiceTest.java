package com.stokvel.service;

import com.stokvel.model.Buyin;
import com.stokvel.model.BuyinDistribution;
import com.stokvel.model.Member;
import com.stokvel.model.Payment;
import com.stokvel.model.StokvelConfig;
import com.stokvel.repository.BuyinDistributionRepository;
import com.stokvel.repository.BuyinRepository;
import com.stokvel.repository.DebtRepository;
import com.stokvel.repository.MemberRepository;
import com.stokvel.repository.PaymentRepository;
import com.stokvel.repository.StokvelConfigRepository;
import com.stokvel.service.CycleService.CycleState;
import com.stokvel.service.StokvelSetupService.MemberAdded;
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
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule 4. A member joining a rotation already under way pays for the rounds they
 * missed, and that money goes to the members those rounds belonged to.
 *
 * The tests that matter here are the conservation ones. A happy-path assertion says
 * "R1,000 was distributed"; only adding up what every member puts in and takes out
 * across a whole rotation says the buy-in was the *right* R1,000. That total is what
 * fails if the amount, the boundary or the compensated set is wrong by one cycle —
 * and each of those three was a live design question during this pass.
 *
 * Created in 2020, like the clock's tests: an assertion that passes because the real
 * calendar happens to agree is not testing anything.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Import({StokvelSetupService.class, BuyinService.class, PaymentService.class,
        ArrearsService.class, PayoutService.class, ClockService.class,
        CycleService.class, LedgerService.class})
class BuyinServiceTest {

    private static final LocalDate START = LocalDate.of(2020, 1, 15);
    private static final BigDecimal CONTRIBUTION = new BigDecimal("500.00");

    /** Cycle 1 falls due here; each later cycle is one calendar month on. */
    private static final LocalDate CYCLE_1_DUE = LocalDate.of(2020, 2, 29);
    private static final LocalDate CYCLE_2_DUE = LocalDate.of(2020, 3, 31);
    private static final LocalDate CYCLE_3_DUE = LocalDate.of(2020, 4, 30);

    @MockitoBean
    private LedgerBroadcaster broadcaster;

    @Autowired
    private StokvelSetupService setupService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private ClockService clockService;
    @Autowired
    private CycleService cycleService;
    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private BuyinRepository buyinRepository;
    @Autowired
    private BuyinDistributionRepository distributionRepository;
    @Autowired
    private DebtRepository debtRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private StokvelConfigRepository configRepository;

    // ------------------------------------------------------------ nothing owed

    /**
     * The founding case, and it is the ordinary one. Nobody who was there from the
     * start missed a round, so there is no buy-in — not a zero-amount row, which
     * CHECK (amount > 0) would reject and which would be a fact about nothing.
     */
    @Test
    void a_founding_member_buys_into_nothing() {
        createStokvel(1, "John", "Sarah", "Thabo");

        assertThat(buyinRepository.findAll()).isEmpty();
        assertThat(distributionRepository.findAll()).isEmpty();
    }

    /**
     * Setup does not have to happen in a single day. The first cycle has no cycle
     * before it to open its window, so it falls back to its own due date — without
     * that, a member added on the second day of setup would be charged a buy-in for
     * joining a stokvel that has not started, compensating a member who has received
     * nothing.
     */
    @Test
    void founding_members_added_on_different_days_still_owe_nothing() {
        setupService.createStokvel(CONTRIBUTION, START, 1);
        setupService.addMember("John");
        setClockTo(LocalDate.of(2020, 1, 20));
        setupService.addMember("Sarah");
        setClockTo(LocalDate.of(2020, 2, 3));
        setupService.addMember("Thabo");

        assertThat(buyinRepository.findAll())
                .as("nobody has been paid out yet, so there is nobody to compensate")
                .isEmpty();
    }

    // --------------------------------------------------------- who is compensated

    /**
     * The test this pass exists for, and the one that decided Rule 4's scope.
     *
     * Erik joins on 15 March. Cycle 1 is over — John was paid a pot sized for three
     * members and will now contribute to four. Cycle 2 is *in progress*: Sarah has
     * not been paid yet, but her pot is sized for three as well, because Erik is not
     * liable for the round he walked in on (Rule 3).
     *
     * So both are compensated, and Thabo is not: his cycle has Erik in it. Driving
     * this off payout rows instead of cycles would compensate John and miss Sarah
     * entirely, which is the bug this assertion exists to catch.
     */
    @Test
    void a_joiner_compensates_the_round_already_paid_and_the_round_in_progress() {
        createStokvel(1, "John", "Sarah", "Thabo");
        clockService.advanceClock(CYCLE_1_DUE);
        setClockTo(LocalDate.of(2020, 3, 15));

        MemberAdded erik = setupService.addMember("Erik");

        assertThat(erik.buyin()).isNotNull();
        assertThat(erik.buyin().buyin().getAmount()).isEqualByComparingTo("1000.00");
        assertThat(erik.buyin().distributions())
                .extracting(topUp -> topUp.getRecipient().getName())
                .as("cycle 1 paid out, cycle 2 in progress — Thabo's round has Erik in it")
                .containsExactly("John", "Sarah");
        assertThat(erik.buyin().distributions())
                .extracting(BuyinDistribution::getAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .as("one contribution each — there is no proportional split to run")
                .containsExactly(CONTRIBUTION, CONTRIBUTION);
    }

    /**
     * Rule 3, restored and load-bearing. Erik is not liable for the round in
     * progress, so he takes no debt row for it when it falls due — his buy-in is
     * what reached Sarah, and the two must not both happen.
     *
     * Sarah still ends the cycle whole: R1,500 in the pot from the three members who
     * were liable, and R500 from Erik that never touched it.
     *
     * Cycle 1 is paid up before the clock moves, deliberately. Leaving it unfunded
     * would have the three of them take debt rows to John, and Rule 7 would then
     * swallow their March contributions settling those — cycle 2's pot would read
     * R500 for a reason that has nothing to do with the boundary under test.
     */
    @Test
    void the_round_a_joiner_missed_produces_a_top_up_and_never_a_debt_row() {
        createStokvel(1, "John", "Sarah", "Thabo");
        everyone().forEach(member -> paymentService.recordPayment(member.getId(), CONTRIBUTION));
        clockService.advanceClock(CYCLE_1_DUE);
        setClockTo(LocalDate.of(2020, 3, 15));
        setupService.addMember("Erik");
        everyoneExcept("Erik").forEach(member -> paymentService.recordPayment(member.getId(), CONTRIBUTION));

        clockService.advanceClock(CYCLE_2_DUE);

        assertThat(debtRepository.findAllByDebtorOrderByCreatedAtAscIdAsc(memberNamed("Erik")))
                .as("he was never liable for the round he walked in on")
                .isEmpty();
        assertThat(cycleService.cycles().get(1).collected())
                .as("three liable members, not four")
                .isEqualByComparingTo("1500.00");
        assertThat(totalToppedUp("Sarah"))
                .as("and the fourth contribution reached her outside the pot")
                .isEqualByComparingTo("500.00");
    }

    /**
     * A second joiner compensates the original members and *not* the first joiner.
     *
     * Erik joined 15 March, so he is liable for cycle 3 onward — including his own
     * round, cycle 4. Zanele joins 20 April, missing cycles 1, 2 and 3, so she tops
     * up John, Sarah and Thabo. Erik's round has her in it, so he is owed nothing.
     *
     * This is what makes "one contribution per missed cycle" worth preferring over a
     * per-member gap calculation: there is no correction term for the buy-in Erik
     * already paid, because nothing here asks about a member's net position.
     */
    @Test
    void a_second_joiner_compensates_the_original_members_and_not_the_first_joiner() {
        createStokvel(1, "John", "Sarah", "Thabo");
        setClockTo(LocalDate.of(2020, 3, 15));
        setupService.addMember("Erik");
        setClockTo(LocalDate.of(2020, 4, 20));

        MemberAdded zanele = setupService.addMember("Zanele");

        assertThat(zanele.buyin().buyin().getAmount()).isEqualByComparingTo("1500.00");
        assertThat(zanele.buyin().distributions())
                .extracting(topUp -> topUp.getRecipient().getName())
                .containsExactly("John", "Sarah", "Thabo");
    }

    /**
     * Rotation scoping. A member joining rotation 2 buys into rotation 2's missed
     * rounds only — rotation 1 was already square, because everyone in it paid for as
     * many rounds as they received.
     *
     * Zanele joins after rotation 1 has closed and cycle 4 (John's second turn) is
     * under way, so she misses exactly one round and tops up exactly one member.
     * Without the rotation filter she would buy into all four earlier cycles and pay
     * four times what she owes.
     */
    @Test
    void a_joiner_in_the_second_rotation_does_not_buy_into_the_first() {
        createStokvel(2, "John", "Sarah", "Thabo");
        clockService.advanceClock(CYCLE_3_DUE);          // rotation 1 closes, rotation 2 is generated
        setClockTo(LocalDate.of(2020, 5, 10));

        MemberAdded zanele = setupService.addMember("Zanele");

        assertThat(zanele.buyin().buyin().getAmount())
                .as("one missed round in rotation 2, and rotation 1 is none of her business")
                .isEqualByComparingTo("500.00");
        assertThat(zanele.buyin().distributions())
                .extracting(topUp -> topUp.getRecipient().getName())
                .containsExactly("John");
    }

    /**
     * And nothing more is owed afterwards. A rotation generated whole has every
     * member liable for every cycle and every pot the same size, so the next one
     * computes to zero with no flag anywhere saying "already bought in".
     */
    @Test
    void the_buy_in_happens_once_and_the_next_rotation_needs_none() {
        createStokvel(2, "John", "Sarah", "Thabo");
        setClockTo(LocalDate.of(2020, 3, 15));
        setupService.addMember("Erik");

        assertThat(buyinRepository.findAll()).hasSize(1);
        runToTheEnd();

        assertThat(buyinRepository.findAll())
                .as("a whole rotation later, still exactly the one")
                .hasSize(1);
    }

    // ------------------------------------------------------------- the invariants

    /**
     * The one that proves the amount is right rather than merely consistent.
     *
     * Everyone pays every contribution they owe, the rotation runs to its end, and
     * every member's total out equals their total in. Erik's buy-in is the only
     * thing making that true: without it John and Sarah each finish R500 down and
     * Erik R1,000 up, and the books do not balance.
     *
     * It fails if the buy-in is one cycle too small (the "already-paid members only"
     * reading of Rule 4), one cycle too large, or paid to the wrong members.
     */
    @Test
    void across_a_whole_rotation_every_member_puts_in_exactly_what_they_take_out() {
        createStokvel(1, "John", "Sarah", "Thabo");
        payEveryone(CYCLE_1_DUE);
        setClockTo(LocalDate.of(2020, 3, 15));
        setupService.addMember("Erik");
        payEveryone(CYCLE_2_DUE);
        payEveryone(CYCLE_3_DUE);
        payEveryone(LocalDate.of(2020, 5, 31));

        for (Member member : memberRepository.findAllByOrderByCreatedAtAscIdAsc()) {
            assertThat(paidIn(member))
                    .as("%s put in and took out the same money", member.getName())
                    .isEqualByComparingTo(tookOut(member));
        }
    }

    /**
     * Rule 4's hard edge: buy-in money never enters a pot.
     *
     * Erik hands over R1,000 on 15 March and cycle 2's pot — the round in progress
     * that day — does not move. Routing the distributions through recordPayment
     * would land the whole R1,000 there, handing Sarah a bonus for the timing of
     * someone else's arrival, and this is the assertion that catches it.
     */
    @Test
    void buy_in_money_never_reaches_a_pot() {
        createStokvel(1, "John", "Sarah", "Thabo");
        clockService.advanceClock(CYCLE_1_DUE);
        setClockTo(LocalDate.of(2020, 3, 15));

        List<BigDecimal> before = cycleService.cycles().stream().map(CycleState::collected).toList();
        setupService.addMember("Erik");

        List<CycleState> after = cycleService.cycles();
        assertThat(after.subList(0, before.size()))
                .extracting(CycleState::collected)
                .usingElementComparator(BigDecimal::compareTo)
                .as("R1,000 changed hands and no pot moved")
                .containsExactlyElementsOf(before);
        assertThat(after.getLast().collected())
                .as("and the cycle he brought with him starts empty like any other")
                .isEqualByComparingTo("0.00");
    }

    /**
     * The buy-in total agrees with its slices by construction — the amount is
     * derived from the missed cycles, never passed in, so there is nothing for a
     * caller to get wrong and nothing to keep in step.
     *
     * The same relationship payment.amount has to its allocations, and asserted for
     * the same reason: it is the claim that would quietly stop being true if anyone
     * gave this method an amount parameter.
     */
    @Test
    void a_buy_ins_amount_is_exactly_what_it_handed_out() {
        createStokvel(1, "John", "Sarah", "Thabo");
        setClockTo(LocalDate.of(2020, 4, 20));

        setupService.addMember("Erik");

        Buyin buyin = buyinRepository.findAll().getFirst();
        BigDecimal distributed = distributionRepository.findAllForLedger().stream()
                .map(BuyinDistribution::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(buyin.getAmount()).isEqualByComparingTo(distributed);
    }

    // ------------------------------------------------------------------ the ledger

    /**
     * One line with its slices underneath, exactly like a payment and its
     * allocations. A distribution has no created_at, so it could only ever sort
     * beside its buy-in — which is what makes it detail of an event rather than an
     * event.
     */
    @Test
    void the_ledger_shows_a_buy_in_as_one_line_with_its_top_ups_as_slices() {
        createStokvel(1, "John", "Sarah", "Thabo");
        clockService.advanceClock(CYCLE_1_DUE);
        setClockTo(LocalDate.of(2020, 3, 15));
        setupService.addMember("Erik");

        List<LedgerService.LedgerEntry> buyins = ledgerService.getLedger().stream()
                .filter(entry -> entry.type() == LedgerService.EntryType.BUYIN)
                .toList();

        assertThat(buyins).hasSize(1);
        assertThat(buyins.getFirst().member()).isEqualTo("Erik");
        assertThat(buyins.getFirst().amount()).isEqualByComparingTo("1000.00");
        assertThat(buyins.getFirst().slices())
                .extracting(LedgerService.LedgerSlice::destination)
                .containsExactly("top-up to John", "top-up to Sarah");
    }

    /**
     * A buy-in and the payout it compensates can land on the same simulated day —
     * every row written on one day carries a byte-identical created_at, because the
     * clock is a date. Erik joins on cycle 2's due date, so he is out of cycle 2 and
     * tops Sarah up, and the payout fires the same day.
     *
     * The rank puts the top-up first. It is the causal order: the money that made up
     * Sarah's shortfall arrived before she was paid. Sorting on the timestamp alone
     * would leave the two in whichever order the merge produced.
     */
    @Test
    void a_buy_in_sorts_before_the_payout_it_compensates_on_the_same_day() {
        createStokvel(1, "John", "Sarah", "Thabo");
        clockService.advanceClock(CYCLE_1_DUE);
        setClockTo(CYCLE_2_DUE);
        setupService.addMember("Erik");

        clockService.advanceClock(CYCLE_2_DUE);

        List<LedgerService.EntryType> onTheDay = ledgerService.getLedger().stream()
                .filter(entry -> entry.at().equals(configRepository.require().simulatedNow()))
                .map(LedgerService.LedgerEntry::type)
                .toList();
        assertThat(onTheDay).startsWith(LedgerService.EntryType.BUYIN);
        assertThat(onTheDay).contains(LedgerService.EntryType.PAYOUT);
    }

    // ----------------------------------------------------------------- fixtures

    private void createStokvel(int rotationCount, String... names) {
        setupService.createStokvel(CONTRIBUTION, START, rotationCount);
        for (String name : names) {
            setupService.addMember(name);
        }
    }

    /** Everyone liable pays their contribution, then the clock moves to the due date. */
    private void payEveryone(LocalDate dueDate) {
        for (Member member : memberRepository.findAllByOrderByCreatedAtAscIdAsc()) {
            if (isLiableOn(member, dueDate)) {
                paymentService.recordPayment(member.getId(), CONTRIBUTION);
            }
        }
        clockService.advanceClock(dueDate);
    }

    /**
     * Liable if they joined on or before the day this cycle's month began. Spelled
     * out here rather than borrowed from the service: a fixture that called the code
     * under test would agree with it by construction, including when both are wrong.
     */
    private boolean isLiableOn(Member member, LocalDate dueDate) {
        LocalDate monthBegan = dueDate.minusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
        return !LocalDate.ofInstant(member.getCreatedAt(), ZoneOffset.UTC).isAfter(monthBegan);
    }

    /**
     * Contributions plus buy-in — every rand this member handed over.
     *
     * Deduction payments are left out: a payout's arrears deduction is money going
     * the other way, already netted off what they received.
     */
    private BigDecimal paidIn(Member member) {
        BigDecimal in = paymentRepository.findAllForLedger().stream()
                .filter(payment -> payment.getMember().getId().equals(member.getId()))
                .filter(payment -> payment.getPayout() == null)
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return in.add(buyinRepository.findAllForLedger().stream()
                .filter(buyin -> buyin.getMember().getId().equals(member.getId()))
                .map(Buyin::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Pot received plus top-ups received — every rand that reached this member. */
    private BigDecimal tookOut(Member member) {
        BigDecimal out = BigDecimal.ZERO;
        for (CycleState state : cycleService.cycles()) {
            if (state.cycle().getRecipient().getId().equals(member.getId())) {
                out = out.add(state.collected());
            }
        }
        return out.add(totalToppedUp(member.getName()));
    }

    private BigDecimal totalToppedUp(String name) {
        return distributionRepository.findAllForLedger().stream()
                .filter(topUp -> topUp.getRecipient().getName().equals(name))
                .map(BuyinDistribution::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<Member> everyone() {
        return memberRepository.findAllByOrderByCreatedAtAscIdAsc();
    }

    private List<Member> everyoneExcept(String name) {
        return memberRepository.findAllByOrderByCreatedAtAscIdAsc().stream()
                .filter(member -> !member.getName().equals(name))
                .toList();
    }

    private Member memberNamed(String name) {
        return memberRepository.findAllByOrderByCreatedAtAscIdAsc().stream()
                .filter(member -> member.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Runs every rotation out. A single advance is enough however many cycles it
     * passes — checkDue re-queries after each payout, so the rotation a payout
     * generates is fired by the same call.
     */
    private void runToTheEnd() {
        clockService.advanceClock(LocalDate.of(2021, 12, 31));
    }

    /** Moves the clock without firing anything — some of these tests are about figures. */
    private void setClockTo(LocalDate date) {
        StokvelConfig config = configRepository.require();
        config.setCurrentDate(date);
        configRepository.save(config);
    }
}
