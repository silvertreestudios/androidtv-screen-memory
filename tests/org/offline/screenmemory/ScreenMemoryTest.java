package org.offline.screenmemory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Deterministic fake clock/transport; executes production controller and metadata policies. */
public final class ScreenMemoryTest {
    private static int assertions;
    private ScreenMemoryTest() { }
    private static void check(boolean value, String label) { if (!value) { throw new AssertionError(label); } assertions++; }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException ex) { assertions++; return; }
        throw new AssertionError("Expected rejection");
    }
    private static Memory memory(int z, int x, int y) { return new Memory("test", "Cinema", 2.39, 1, z, x, y); }
    private static final class Fake implements Operation.Port {
        final List<String> events = new ArrayList<>();
        final List<Integer> sent = new ArrayList<>();
        final Operation operation = new Operation(this);
        long bindToken, tickToken;
        boolean journalFailure, sendFailure, cleanupFailure, bindFailure, scheduleFailure;
        String reason;
        boolean success;
        int cleanups, binds, finishes;
        Runnable inSend, inEvent, inCleanup;
        @Override public void event(String event) {
            events.add(event);
            if (inEvent != null) { Runnable run = inEvent; inEvent = null; run.run(); }
            if (journalFailure) { throw new IllegalStateException("disk"); }
        }
        @Override public void bind(long token) { binds++; bindToken = token; if (bindFailure) { throw new IllegalStateException("bind"); } }
        @Override public void send(int what) {
            check(events.get(events.size() - 1).contains("dirty=true"), "durable critical event before send");
            sent.add(what);
            if (inSend != null) { inSend.run(); }
            if (sendFailure) { throw new IllegalStateException("transport"); }
        }
        @Override public void schedule(long token, long delay) {
            check(delay == 1000, "one second pacing/final grace"); tickToken = token;
            if (scheduleFailure) { throw new IllegalStateException("clock"); }
        }
        @Override public void cleanup() { cleanups++; if (inCleanup != null) { inCleanup.run(); } if (cleanupFailure) { throw new IllegalStateException("cleanup"); } }
        @Override public void finished(boolean submitted, String reason) { finishes++; success = submitted; this.reason = reason; check(!reason.isEmpty(), "reason visible"); }
        void start(Memory target) { check(operation.confirmNative(), "explicit reference"); check(operation.start(target), "operation accepted"); }
        void connect() { operation.connected(bindToken); }
        void tick() { operation.tick(tickToken); }
        void drain() { int budget = 130; while (operation.busy() && --budget > 0) { tick(); } check(budget > 0, "bounded completion"); }
    }
    private static void metadata() throws java.io.IOException {
        List<List<Memory>> writes = new ArrayList<>();
        Catalog catalog = new Catalog(Collections.emptyList(), list -> writes.add(new ArrayList<>(list)));
        check(catalog.all().size() == 1 && catalog.get("native") == Memory.NATIVE, "Native enforced");
        rejects(() -> catalog.delete("native")); rejects(() -> catalog.save(Memory.NATIVE));
        rejects(() -> new Memory("native", "Other", 16, 9, 0, 0, 0));
        Memory draft = memory(3, -2, 1); check(catalog.get("test") == null, "unsaved draft not catalog");
        catalog.save(draft); check(catalog.get("test").x == -2, "save offsets");
        Memory discarded = draft.offsets(5, 2, 1); check(catalog.get("test").zoom == 3 && discarded.zoom == 5, "discard independent");
        catalog.save(new Memory("test", "Renamed", 4, 3, 1, 2, 3));
        check(catalog.all().size() == 2 && catalog.get("test").name.equals("Renamed"), "update replaces ID");
        byte[] bytes = MemoryCodec.encode(catalog.all());
        Catalog decoded = new Catalog(MemoryCodec.decode(bytes), list -> { });
        check(decoded.get("test").width == 4 && decoded.get("native").zoom == 0, "actual storage codec roundtrip");
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
        try { MemoryCodec.decode(trailing); throw new AssertionError("corruption accepted"); } catch (java.io.IOException ex) { assertions++; }
        try { MemoryCodec.decode(new byte[] {0, 0}); throw new AssertionError("truncation accepted"); } catch (java.io.IOException ex) { assertions++; }
        Catalog failing = new Catalog(Collections.singletonList(draft), list -> { throw new IllegalStateException("disk"); });
        rejects(() -> failing.delete("test")); check(failing.get("test") != null, "failed delete preserves catalog");
        rejects(() -> failing.save(discarded)); check(failing.get("test").zoom == 3, "failed save preserves catalog");
        catalog.delete("test"); check(catalog.all().size() == 1 && writes.size() == 3, "delete persists without controller");
        rejects(() -> Memory.validate(Arrays.asList(draft, draft)));
        rejects(() -> draft.offsets(101, 0, 0));
    }
    private static void geometry() {
        double[] wide = Memory.fit(1600, 900, 21, 9);
        check(wide[0] == 0 && wide[2] == 1600 && wide[1] > 0, "21:9 fills width");
        double[] narrow = Memory.fit(1600, 900, 4, 3);
        check(narrow[1] == 0 && narrow[3] == 900 && narrow[2] == 1200, "4:3 fills height");
        double[] full = Memory.fit(1600, 900, 16, 9);
        check(full[0] == 0 && full[1] == 0 && full[2] == 1600 && full[3] == 900, "16:9 full white");
        rejects(() -> memory(0, Integer.MIN_VALUE, 0));
        rejects(() -> new Memory("m", "x", Double.NaN, 1, 0, 0, 0));
        rejects(() -> new Memory("m", "x", 1, Double.POSITIVE_INFINITY, 0, 0, 0));
        rejects(() -> new Memory("m", "x", 0, 1, 0, 0, 0));
        rejects(() -> Memory.fit(0, 900, 16, 9)); rejects(() -> Memory.fit(1600, 900, -1, 9));
    }
    private static void transitions() {
        check(Operation.plan(0, 0, 0, memory(2, -1, 1)).equals(Arrays.asList(428, 428, 421, 422)), "zoom horizontal vertical order");
        check(Operation.plan(1, 1, 1, memory(0, 0, 0)).equals(Arrays.asList(429, 421, 423)), "negative deltas");
        check(Operation.plan(0, 0, 0, memory(0, 1, -1)).equals(Arrays.asList(420, 423)), "all six exact mappings");
        rejects(() -> Operation.plan(-100, 0, 0, memory(100, 0, 0)));
        Fake f = new Fake(); check(!f.operation.known(), "cold process unknown");
        check(!f.operation.start(Memory.NATIVE) && f.binds == 0 && f.sent.isEmpty(), "unknown Native cannot move");
        check(f.operation.confirmNative() && f.sent.isEmpty() && f.binds == 0, "confirmation sends nothing");
        f.operation.start(Memory.NATIVE); check(f.success && f.binds == 0, "known noop no binding");
        f.operation.start(memory(1, 2, -1)); long first = f.bindToken;
        check(!f.operation.start(memory(1, 1, 1)), "double click rejected");
        check(!f.operation.confirmNative(), "busy reference rejected");
        f.inSend = () -> { f.operation.connected(first); check(!f.operation.start(Memory.NATIVE), "reentrant start blocked"); };
        f.connect(); check(f.sent.size() == 1 && f.operation.zoom() == 1, "first submission tracking");
        f.inSend = null; f.operation.connected(first); check(f.sent.size() == 1, "late duplicate connection ignored");
        long oldTick = f.tickToken; f.tick(); f.operation.tick(oldTick);
        check(f.sent.size() == 2, "duplicate timer cannot advance next step");
        f.drain(); check(f.success && f.operation.known() && f.operation.x() == 2 && f.operation.y() == -1, "mixed target tracked after grace");
        int before = f.sent.size(); f.operation.start(memory(0, 1, 1)); f.connect(); f.drain();
        check(f.sent.subList(before, f.sent.size()).equals(Arrays.asList(429, 421, 422, 422)), "memory to memory delta avoids Native detour");
        f.operation.start(memory(1, 1, 1)); f.connect(); f.drain();
        check(f.operation.zoom() == 1, "discarded metadata leaves physical tracking intact");
        Fake cold = new Fake(); check(!cold.operation.known(), "restart ignores persisted offsets");
    }
    private static void uncertainty() {
        for (String reason : Arrays.asList("Stop", "reply boundary", "reply error", "reply unknown", "disconnect", "binding death", "timeout", "cancel")) {
            Fake f = new Fake(); f.start(memory(2, 1, 0)); f.connect(); long stale = f.tickToken;
            f.operation.stop(reason); f.operation.tick(stale); f.operation.connected(f.bindToken);
            check(!f.operation.known() && !f.operation.busy() && !f.success && f.sent.size() == 1 && f.cleanups == 1, "abort " + reason);
        }
        Fake cancelled = new Fake(); cancelled.start(memory(1, 0, 0)); cancelled.operation.stop("cancel before first");
        check(cancelled.sent.isEmpty() && !cancelled.operation.known(), "pre-send cancellation conservative unknown");
        Fake journal = new Fake(); journal.operation.confirmNative(); journal.journalFailure = true; journal.operation.start(memory(1, 0, 0));
        check(journal.binds == 0 && journal.sent.isEmpty() && !journal.operation.known(), "start journal fails closed");
        Fake stepLog = new Fake(); stepLog.start(memory(1, 0, 0)); stepLog.journalFailure = true; stepLog.connect();
        check(stepLog.sent.isEmpty() && !stepLog.operation.known(), "critical event failure prevents send");
        Fake afterLog = new Fake(); afterLog.start(memory(1, 0, 0)); afterLog.inSend = () -> afterLog.journalFailure = true; afterLog.connect();
        check(afterLog.sent.size() == 1 && !afterLog.operation.known(), "post-submission journal failure unknown");
        Fake send = new Fake(); send.start(memory(2, 0, 0)); send.sendFailure = true; send.connect(); send.tick();
        check(send.sent.size() == 1 && !send.operation.known() && !send.success, "throwing send never retried");
        Fake cleanup = new Fake(); cleanup.start(memory(1, 0, 0)); cleanup.connect(); cleanup.cleanupFailure = true; cleanup.tick();
        check(!cleanup.success && !cleanup.operation.known() && !cleanup.operation.confirmNative(), "cleanup failure permanently gates policy");
        Fake finalLog = new Fake(); finalLog.start(memory(1, 0, 0)); finalLog.connect(); finalLog.journalFailure = true; finalLog.tick();
        check(!finalLog.success && !finalLog.operation.known(), "completion journal failure is unknown");
        Fake clock = new Fake(); clock.start(memory(1, 0, 0)); clock.scheduleFailure = true; clock.connect();
        check(!clock.operation.known() && clock.sent.size() == 1, "schedule failure unknown");
        Fake bind = new Fake(); bind.bindFailure = true; bind.start(memory(1, 0, 0)); check(!bind.operation.known() && bind.sent.isEmpty(), "bind exception abort");
        Fake ref = new Fake(); ref.journalFailure = true; check(!ref.operation.confirmNative() && !ref.operation.known(), "reference persistence failure");
        Fake reentrant = new Fake(); reentrant.start(memory(1, 0, 0)); reentrant.inEvent = () -> reentrant.operation.stop("reentrant Stop"); reentrant.connect();
        check(reentrant.sent.isEmpty() && !reentrant.operation.known(), "Stop during critical event prevents send");
        Fake settle = new Fake(); settle.start(memory(1, 0, 0)); settle.connect();
        settle.inCleanup = () -> settle.operation.stop("Stop during cleanup"); settle.tick();
        check(!settle.success && !settle.operation.known() && settle.finishes == 1, "reentrant cleanup cannot report success");
        Fake late = new Fake(); late.start(memory(1, 0, 0)); long obsolete = late.bindToken;
        late.connect(); late.drain(); late.operation.start(memory(2, 0, 0));
        late.operation.connected(obsolete); late.operation.failure(obsolete, "obsolete callback");
        check(late.sent.size() == 1 && late.operation.busy(), "old operation callbacks do not affect replacement");
        late.connect(); late.drain(); check(late.sent.size() == 2 && late.success, "new operation retains per-operation gate");
        Fake reference = new Fake(); reference.inEvent = () -> {
            check(!reference.operation.confirmNative() && !reference.operation.start(Memory.NATIVE), "reentrant reference/start gated");
        };
        check(reference.operation.confirmNative() && reference.sent.isEmpty(), "reference finishes without send");
        Fake reply = new Fake(); reply.start(memory(1, 0, 0)); reply.inSend = () -> reply.operation.stop("synchronous reply"); reply.sendFailure = true; reply.connect();
        check(reply.sent.size() == 1 && !reply.operation.known() && !reply.success && reply.finishes == 1, "reply during send remains uncertain");
    }
    private static void reviewRegressions() {
        Fake f = new Fake(); f.start(memory(2, 1, -1)); f.connect(); f.drain();
        RuntimeState runtime = new RuntimeState(); runtime.operation = f.operation;
        int sent = f.sent.size();
        check(f.operation.known() && f.operation.zoom() == 2, "known nonzero before manual alignment");
        runtime.acceptReference();
        check(runtime.reference && !f.operation.known() && f.sent.size() == sent,
                "accepted manual reference immediately invalidates old offsets without commands");
        runtime.pending = Memory.NATIVE; runtime.launching = true; runtime.launchFailed();
        check(!runtime.reference && !runtime.launching && runtime.pending == null && !f.operation.known(),
                "foreground launch failure keeps tracking unknown and clears handoff");
        check(!f.operation.start(memory(3, 1, -1)) && f.sent.size() == sent,
                "launch failure cannot move from stale offsets");
        check(runtime.displayStatus().contains("UNKNOWN"), "overview/notification expose launch failure unknown");
        int[] updates = {0}; runtime.listener = () -> updates[0]++;
        runtime.publish(() -> { throw new IllegalStateException("notification unavailable"); });
        check(updates[0] == 1 && !f.operation.known(), "notification failure preserves status UI and controller");
        check(f.operation.confirmNative(), "reference can be explicitly reestablished");
        runtime.status = "Commands submitted; position estimated; hardware completion unmeasured";
        check(runtime.displayStatus().contains("Position estimated"), "known presentation remains an estimate");
        f.operation.start(memory(1, 0, 0));
        check(runtime.displayStatus().contains("BUSY"), "in-flight presentation busy");
        f.operation.stop("reply failure"); runtime.status = "reply failure; tracking UNKNOWN";
        check(runtime.displayStatus().contains("reply failure") && runtime.displayStatus().contains("UNKNOWN"),
                "terminal failure presentation retains reason and unknown");
        for (String loss : Arrays.asList("focus", "window", "detach")) {
            SelectGesture g = new SelectGesture();
            check(g.press(true, 0), "gesture begins before " + loss);
            g.cancel();
            check(!g.hold(true) && !g.release(true, false), "canceled " + loss + " cannot long or short commit after refocus");
        }
        SelectGesture g = new SelectGesture();
        check(!g.press(false, 0), "unfocused key down ignored");
        check(g.press(true, 0) && !g.press(true, 1), "repeat does not queue gesture");
        check(g.hold(true) && !g.hold(true) && !g.release(true, false), "one long action without short action");
        check(g.press(true, 0) && g.release(true, false), "short focused gesture commits");
        g.press(true, 0); check(!g.release(false, false), "unfocused short commit blocked");
        g.press(true, 0); check(!g.hold(false) && !g.release(true, false), "unfocused long commit cancels gesture");
        g.press(true, 0); check(!g.release(true, true), "canceled key up ignored");
    }
    private static void usability() {
        UiPolicy.Dispatch dispatch = new UiPolicy.Dispatch();
        long ticket = dispatch.ticket();
        check(dispatch.valid(ticket, true), "eligible deferred action");
        check(!dispatch.valid(ticket, false), "deferred action rechecks eligibility");
        dispatch.cancel();
        check(!dispatch.valid(ticket, true), "focus/window loss then regain cannot revive deferred action");
        check(dispatch.valid(dispatch.ticket(), true), "new gesture after cancellation allowed");
        check(UiPolicy.resolveName("", 2.4, 1).equals("2.4:1"), "blank decimal ratio name");
        check(UiPolicy.resolveName("   ", 16, 9).equals("16:9"), "whitespace ratio name");
        check(UiPolicy.resolveName("", 21, 9).equals("21:9"), "display ratio not reduced");
        check(UiPolicy.resolveName("  Cinema  ", 21, 9).equals("Cinema"), "manual name trimmed");
        check(UiPolicy.resolveName("", 2.39123, 1).equals("2.39123:1"), "meaningful decimals retained");
        rejects(() -> UiPolicy.resolveName("", Double.NaN, 1));
        rejects(() -> UiPolicy.resolveName("Named", 1, Double.POSITIVE_INFINITY));
        rejects(() -> UiPolicy.resolveName("", -1, 9));
        rejects(() -> UiPolicy.resolveName("", 0, 9));
        rejects(() -> Double.parseDouble(""));
        rejects(() -> UiPolicy.ratioName(Double.MIN_VALUE, 1));
        for (String target : Arrays.asList("Zoom +", "Zoom −", "Left", "Right", "Up", "Down")) {
            UiPolicy.Focus focus = new UiPolicy.Focus(); focus.selected(target);
            for (int callback = 0; callback < 4; callback++) {
                check(focus.restore(true) == null && target.equals(focus.target()), "busy rebuild retains logical target " + target);
            }
            check(target.equals(focus.restore(false)), "completion restores adjustment without dispatch");
            focus.selected("Back"); check("Back".equals(focus.restore(false)), "intentional navigation wins");
            focus.clear(); check(focus.restore(false) == null, "new screen drops old target");
        }
        for (int[] screen : new int[][] {{1920,1080}, {1280,720}, {800,480}, {1080,1920}}) {
            for (float density : new float[] {1, 2, 3}) {
                int[] p = UiPolicy.panel(screen[0], screen[1], density);
                check(p[0] <= screen[0] && p[3] <= screen[1] && p[3] == p[1] * 6, "all six fixed rows fit");
                check(p[2] > 0 && p[2] * 3 <= p[1], "readable text fits row");
                check(p[0] * (double) p[3] < screen[0] * (double) screen[1] * .3, "panel covers modest canvas area");
            }
        }
        rejects(() -> UiPolicy.panel(0, 1080, 1));
    }
    private static void inputEligibility() {
        check(UiPolicy.canActivate(true, true, true, false, false), "touch works without keyboard focus");
        check(!UiPolicy.canActivate(true, true, true, false, true), "keyboard requires focus");
        check(UiPolicy.canActivate(true, true, true, true, true), "focused keyboard works");
        for (boolean keyboard : new boolean[] {false, true}) {
            check(!UiPolicy.canActivate(false, true, true, true, keyboard), "disabled input blocked");
            check(!UiPolicy.canActivate(true, false, true, true, keyboard), "detached input blocked");
            check(!UiPolicy.canActivate(true, true, false, true, keyboard), "inactive window input blocked");
            UiPolicy.Dispatch dispatch = new UiPolicy.Dispatch();
            long ticket = dispatch.ticket(); dispatch.cancel();
            check(!dispatch.valid(ticket, UiPolicy.canActivate(true, true, true, true, keyboard)),
                    "canceled deferred input cannot fire after eligibility returns");
        }
    }
    private static Fake preview(int direction) {
        Fake f = new Fake(); check(f.operation.confirmNative(), "preview reference");
        check(f.operation.preview(memory(direction, 0, 0)), "explicit preview accepted");
        f.connect(); return f;
    }
    private static void boundaries() {
        for (int direction : new int[] {1, -1}) {
            int reply = direction == 1 ? 2002 : 2001, command = direction == 1 ? 428 : 429;
            Fake f = preview(direction); long obsolete = f.tickToken;
            f.operation.reply(reply, command);
            check(f.operation.known() && !f.operation.busy() && f.operation.zoom() == 0,
                    "supported preview restores estimated pre-step reference");
            check(f.sent.size() == 1 && f.cleanups == 1 && f.success && f.reason.contains("(estimated)"),
                    "limit visible with cleanup and no compensation");
            f.operation.reply(reply, command); f.operation.tick(obsolete);
            check(f.finishes == 1 && f.sent.size() == 1, "late duplicate and obsolete timer inert");
            check(!f.operation.preview(memory(direction, 0, 0)) && f.binds == 1,
                    "latched repeated preview rejected before bind/send");
            check(f.operation.preview(memory(-direction, 0, 0)), "opposite preview accepted");
            f.connect(); check(!f.operation.previewAllowed(direction), "latch retained during opposite work");
            f.tick(); check(f.operation.previewAllowed(direction), "normal opposite finish releases latch");
            Fake reset = preview(direction); reset.operation.reply(reply, command);
            check(reset.operation.confirmNative() && reset.operation.previewAllowed(direction), "explicit reference clears latch");
        }
        int[][] wrong = {{2001,428},{2002,429},{2003,428},{2002,420},{999,428}};
        for (int[] pair : wrong) {
            Fake f = preview(1); f.operation.reply(pair[0], pair[1]);
            check(!f.operation.known() && !f.operation.busy() && f.sent.size() == 1, "wrong reply fails unknown");
        }
        for (int count : new int[] {1, 3}) {
            Fake f = new Fake(); f.start(memory(count, 1, 0)); f.connect();
            f.operation.reply(2002, 428);
            check(!f.operation.known() && f.operation.zoom() == 1 && f.sent.size() == 1,
                    "recall stops without rolling back submitted counts or continuing axes");
            check(f.reason.contains("re-establish reference"), "recall recovery explanation");
        }
        Fake one = new Fake(); one.start(memory(1, 0, 0)); one.connect(); one.operation.reply(2002,428);
        check(!one.operation.known(), "one-step recall is not preview");
        Fake early = new Fake(); early.operation.confirmNative(); early.operation.preview(memory(1,0,0));
        early.operation.reply(2002,428); check(!early.operation.known() && early.sent.isEmpty(), "reply before send unknown");
        Fake late = preview(1); late.tick(); late.operation.reply(2002,428);
        check(late.operation.zoom() == 1 && late.operation.known(), "after grace no rollback");
        Fake repeated = preview(1); repeated.tick(); repeated.operation.preview(memory(2,0,0)); repeated.connect();
        repeated.operation.reply(2002,428);
        check(repeated.operation.zoom() == 1 && repeated.operation.known(),
                "same command ordinal ambiguity explicitly bounded only by new connection/grace assumption");
        for (boolean cleanup : new boolean[] {false,true}) {
            Fake f = preview(1); f.cleanupFailure = cleanup; f.journalFailure = !cleanup;
            f.operation.reply(2002,428); check(!f.operation.known(), "limit cleanup/log failure unknown");
        }
        Fake finalLog = preview(1); finalLog.inCleanup = () -> { finalLog.journalFailure = true; };
        finalLog.operation.reply(2002,428);
        check(!finalLog.operation.known(), "post-cleanup limit journal failure unknown");
        Fake stale = preview(1); long oldConnection = stale.bindToken; stale.tick();
        stale.operation.preview(memory(2,0,0)); stale.operation.connected(oldConnection);
        check(stale.sent.size() == 1, "obsolete connection token cannot submit new preview");
        stale.connect(); stale.tick();
        Fake failedOpposite = preview(1); failedOpposite.operation.reply(2002,428);
        failedOpposite.operation.preview(memory(-1,0,0)); failedOpposite.connect(); failedOpposite.operation.stop("disconnect");
        check(!failedOpposite.operation.known(), "failed opposite invalidates reference");
        Catalog saved = new Catalog(Collections.singletonList(memory(7,2,1)), list -> { throw new AssertionError("controller wrote catalog"); });
        Fake f = preview(1); f.operation.reply(2002,428);
        check(saved.get("test").zoom == 7 && saved.get("native") == Memory.NATIVE, "boundary preserves saved definitions");
    }
    private static final class SeedStore implements Catalog.InitializationStore {
        List<Memory> disk = Collections.emptyList(); boolean marked, failSave, failMarker; int writes;
        public boolean initialized() { return marked; }
        public void save(List<Memory> list) {
            if (failSave) { throw new IllegalStateException("save"); }
            disk = new ArrayList<>(list); writes++;
        }
        public void markInitialized() {
            if (failMarker) { throw new IllegalStateException("marker"); }
            marked = true;
        }
    }
    private static void initialization() throws java.io.IOException {
        SeedStore fresh = new SeedStore();
        List<Memory> seeded = Catalog.initialize(fresh.disk, fresh);
        Memory cinema = seeded.get(1);
        check(seeded.size() == 2 && cinema.id.equals("cinemascope") && cinema.name.equals("CinemaScope")
                && cinema.width == 2.4 && cinema.height == 1 && cinema.zoom == 0 && cinema.x == 0 && cinema.y == 0,
                "fresh ordinary CinemaScope zero metadata");
        Catalog catalog = new Catalog(seeded, fresh); catalog.delete("cinemascope");
        check(Catalog.initialize(fresh.disk, fresh).size() == 1 && fresh.writes == 2, "deleted seed not resurrected");
        SeedStore existing = new SeedStore(); Memory user = memory(8,2,3);
        List<Memory> result = Catalog.initialize(Collections.singletonList(user), existing);
        check(result.size() == 3 && result.get(1) == user, "existing users retained");
        check(Catalog.initialize(result, existing).size() == 3 && existing.writes == 1, "seed once");
        SeedStore retry = new SeedStore(); retry.failMarker = true;
        rejects(() -> Catalog.initialize(Collections.singletonList(user), retry));
        check(!retry.marked && retry.disk.size() == 3, "catalog committed before failed marker");
        retry.failMarker = false;
        check(Catalog.initialize(retry.disk, retry).size() == 3, "retry deduplicates stable ID");
        SeedStore fail = new SeedStore(); fail.disk = Collections.singletonList(user); fail.failSave = true;
        rejects(() -> Catalog.initialize(fail.disk, fail));
        check(!fail.marked && fail.disk.get(0) == user, "save failure preserves data and marker");
        SeedStore corrupt = new SeedStore(); byte[] bytes = {0,0}; byte[] original = bytes.clone();
        try { Catalog.initialize(MemoryCodec.decode(bytes), corrupt); throw new AssertionError("corrupt accepted"); }
        catch (java.io.IOException expected) { check(corrupt.writes == 0 && !corrupt.marked && Arrays.equals(bytes,original), "corruption never reaches seed write"); }
        Memory custom = new Memory("cinemascope", "User custom", 4,3,2,1,0);
        SeedStore duplicate = new SeedStore();
        check(Catalog.initialize(Collections.singletonList(custom), duplicate).get(1) == custom, "existing stable ID never overwritten");
    }
    public static void main(String[] args) throws java.io.IOException {
        boundaries(); initialization(); metadata(); geometry(); transitions(); uncertainty(); reviewRegressions(); usability(); inputEligibility();
        System.out.println("Screen Memory tests passed: " + assertions + " assertions; production policies; no Android runtime");
    }
}
