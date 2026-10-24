package org.offline.binddiagnostic;

final class ExtendedBindingSessionTest {
    private static int passed;
    private static final class Fake implements BindingSession.Port {
        int binds;
        int unbinds;
        boolean accepted = true;
        RuntimeException bindFailure;
        RuntimeException unbindFailure;
        Runnable duringBind;
        final BindingSession session = new BindingSession(this);
        @Override public boolean bind() {
            binds++;
            if (duringBind != null) { duringBind.run(); }
            if (bindFailure != null) { throw bindFailure; }
            return accepted;
        }
        @Override public void unbind() {
            unbinds++;
            if (unbindFailure != null) { throw unbindFailure; }
        }
        @Override public void log(String value) { if (value.isEmpty()) { throw new AssertionError(); } }
        void start() { session.visible(); session.bindClicked(); }
    }
    private static void check(boolean value) { if (!value) { throw new AssertionError(); } }
    private static void test(String name, Runnable body) {
        body.run(); passed++; System.out.println("PASS " + name);
    }
    public static void main(String[] args) {
        test("launch/resume do not bind", () -> {
            Fake f = new Fake(); f.session.visible(); f.session.visible(); check(f.binds == 0);
        });
        test("hidden click cannot bind", () -> {
            Fake f = new Fake(); f.session.bindClicked(); check(f.binds == 0);
        });
        test("accepted callback and explicit unbind", () -> {
            Fake f = new Fake(); f.start(); check(f.session.state() == BindingSession.State.WAITING);
            f.session.connected(true); check(f.session.state() == BindingSession.State.CONNECTED);
            f.session.unbindClicked(); check(f.unbinds == 1 && f.session.state() == BindingSession.State.IDLE);
        });
        test("duplicate clicks blocked while pending and connected", () -> {
            Fake f = new Fake(); f.start(); f.session.bindClicked(); f.session.connected(true);
            f.session.bindClicked(); check(f.binds == 1);
        });
        test("false bind explicitly unregisters", () -> {
            Fake f = new Fake(); f.accepted = false; f.start();
            check(f.unbinds == 1 && f.session.state() == BindingSession.State.IDLE);
        });
        test("denied bind explicitly unregisters", () -> {
            Fake f = new Fake(); f.bindFailure = new SecurityException(); f.start(); check(f.unbinds == 1);
        });
        test("runtime bind failure unregisters", () -> {
            Fake f = new Fake(); f.bindFailure = new IllegalStateException(); f.start(); check(f.unbinds == 1);
        });
        test("disconnect unregisters without reconnect", () -> {
            Fake f = new Fake(); f.start(); f.session.connected(true); f.session.disconnected();
            check(f.unbinds == 1 && f.binds == 1); f.session.connected(true);
            check(f.session.state() == BindingSession.State.IDLE);
        });
        test("binding death unregisters", () -> {
            Fake f = new Fake(); f.start(); f.session.bindingDied(); check(f.unbinds == 1 && f.binds == 1);
        });
        test("null binding unregisters", () -> {
            Fake f = new Fake(); f.start(); f.session.nullBinding(); check(f.unbinds == 1);
        });
        test("absent Binder unregisters", () -> {
            Fake f = new Fake(); f.start(); f.session.connected(false); check(f.unbinds == 1);
        });
        test("stop pending and repeated destruction are idempotent", () -> {
            Fake f = new Fake(); f.start(); f.session.stopped(); f.session.stopped();
            f.session.visible(); check(f.unbinds == 1 && f.binds == 1);
        });
        test("rotation replacement never auto binds", () -> {
            Fake f = new Fake(); f.start(); f.session.connected(true); f.session.stopped();
            Fake replacement = new Fake(); replacement.session.visible();
            check(f.unbinds == 1 && replacement.binds == 0);
        });
        test("not registered cleanup exception settles idle", () -> {
            Fake f = new Fake(); f.unbindFailure = new IllegalArgumentException(); f.start();
            f.session.unbindClicked(); check(f.session.state() == BindingSession.State.IDLE);
        });
        test("unexpected cleanup failure blocks new binds until explicit cleanup", () -> {
            Fake f = new Fake(); f.start(); f.unbindFailure = new SecurityException();
            f.session.disconnected(); f.session.bindClicked(); f.session.connected(true);
            check(f.binds == 1 && f.session.state() == BindingSession.State.CLEANUP_FAILED);
            f.unbindFailure = null; f.session.unbindClicked(); f.session.bindClicked(); check(f.binds == 2);
        });
        test("synchronous null callback cannot restore waiting", () -> {
            Fake f = new Fake(); f.duringBind = f.session::nullBinding; f.start();
            check(f.unbinds == 1 && f.session.state() == BindingSession.State.IDLE);
        });
        test("synchronous connection is not overwritten", () -> {
            Fake f = new Fake(); f.duringBind = () -> f.session.connected(true); f.start();
            check(f.session.state() == BindingSession.State.CONNECTED);
        });
        test("stop during bind cannot leave registration", () -> {
            Fake f = new Fake(); f.duringBind = f.session::stopped; f.start();
            check(f.unbinds == 1 && f.session.state() == BindingSession.State.IDLE);
        });
        System.out.println("Tests passed: " + passed);
    }
}
