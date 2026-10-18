// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android.testfixtures;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import dev.androidagent.harness.web.android.AndroidWeb4AgentSession;
import dev.androidagent.harness.web.android.Web4AgentActionResult;
import dev.androidagent.harness.web.android.Web4AgentConfiguration;
import dev.androidagent.harness.web.android.Web4AgentExactEffectTestHooks;
import dev.androidagent.harness.web.android.Web4AgentPresenter;
import dev.androidagent.harness.web.android.Web4AgentSession;
import dev.androidagent.harness.web.android.Web4AgentSessionProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import kotlin.Unit;

/**
 * Headless, debuggable-only host for deterministic exact-effect verification.
 *
 * <p>The returned {@link Web4AgentSession} is the real, version-matched
 * AndroidWeb4AgentSession and therefore retains the internal exact-effect
 * capability used by Web4AgentToolSet. Consumers see only this opaque test
 * facade. The host intentionally does not create a BrowserActivity;
 * presentation acknowledgement/quiescence remains a separate HG-005 test.
 * Consume this Gradle test-fixtures variant only from test configurations.</p>
 */
public final class Web4AgentExactEffectTestHost
        implements Web4AgentSessionProvider, Web4AgentPresenter, AutoCloseable {
    private final Context applicationContext;
    private final String sessionId;
    private final Web4AgentConfiguration configuration;
    private final Object lifecycleLock = new Object();
    private final RaceController raceController;
    private final Web4AgentExactEffectTestHooks hooks;

    private long sessionGeneration = 1L;
    private AndroidWeb4AgentSession currentSession;
    private boolean transitionInProgress;
    private boolean hostClosed;

    private Web4AgentExactEffectTestHost(
            Context context,
            String sessionId,
            Web4AgentConfiguration configuration
    ) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Fixture session id must not be blank.");
        }
        this.applicationContext = context.getApplicationContext();
        if ((applicationContext.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            throw new IllegalArgumentException(
                    "Web4Agent exact-effect fixtures require a debuggable test host."
            );
        }
        this.sessionId = sessionId;
        this.configuration = configuration;
        this.raceController = new RaceController(sessionId, this::sessionGeneration);
        this.hooks = new Web4AgentExactEffectTestHooks() {
            @Override
            public void afterGuardBeforeDispatch(String actualSessionId, String leaseId) {
                raceController.afterGuardBeforeDispatch(actualSessionId, leaseId);
            }

            @Override
            public void afterSessionFenced(String actualSessionId) {
                raceController.afterSessionFenced(actualSessionId);
            }
        };
        this.currentSession = newSession();
    }

    public static Web4AgentExactEffectTestHost create(Context context, String sessionId) {
        return create(context, sessionId, Web4AgentConfiguration.Companion.secureDefault());
    }

    public static Web4AgentExactEffectTestHost create(
            Context context,
            String sessionId,
            Web4AgentConfiguration configuration
    ) {
        if (context == null || configuration == null) {
            throw new IllegalArgumentException("Fixture context and configuration are required.");
        }
        return new Web4AgentExactEffectTestHost(context, sessionId, configuration);
    }

    public String getSessionId() {
        return sessionId;
    }

    public RaceController getRaceController() {
        return raceController;
    }

    @Override
    public Web4AgentSession session(String requestedSessionId) {
        if (!sessionId.equals(requestedSessionId)) {
            throw new IllegalArgumentException(
                    "Fixture is bound to a different Web4Agent session id."
            );
        }
        synchronized (lifecycleLock) {
            check(!hostClosed, "Web4Agent exact-effect test host is closed.");
            check(!transitionInProgress, "Web4Agent fixture session is being replaced or closed.");
            check(currentSession != null, "Web4Agent fixture session is closed.");
            return currentSession;
        }
    }

    /** Headless presenter used only so Web4AgentToolSet can execute test opens. */
    @Override
    public void show(String requestedSessionId) {
        if (!sessionId.equals(requestedSessionId)) {
            throw new IllegalArgumentException(
                    "Fixture presenter is bound to a different Web4Agent session id."
            );
        }
    }

    /** Fences and closes the current real session on a worker thread. */
    public CompletionStage<Web4AgentActionResult> closeSessionAsync() {
        AndroidWeb4AgentSession target = beginTransition();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return target.finish(false);
            } finally {
                synchronized (lifecycleLock) {
                    if (currentSession == target) currentSession = null;
                    transitionInProgress = false;
                }
            }
        });
    }

    /**
     * Fences the old session and creates a fresh same-id generation after its
     * cleanup quiesces.
     */
    public CompletionStage<Web4AgentSession> replaceSessionAsync() {
        AndroidWeb4AgentSession target = beginTransition();
        return CompletableFuture.supplyAsync(() -> {
            try {
                Web4AgentActionResult closeResult = target.finish(false);
                check(closeResult.getOk(), closeResult.getSummary());
                synchronized (lifecycleLock) {
                    check(!hostClosed, "Web4Agent exact-effect test host is closed.");
                    check(currentSession == target, "Fixture session changed during replacement.");
                    sessionGeneration += 1L;
                    AndroidWeb4AgentSession replacement = newSession();
                    currentSession = replacement;
                    return replacement;
                }
            } finally {
                synchronized (lifecycleLock) {
                    transitionInProgress = false;
                }
            }
        });
    }

    @Override
    public void close() {
        AndroidWeb4AgentSession target;
        synchronized (lifecycleLock) {
            if (hostClosed) return;
            hostClosed = true;
            target = currentSession;
            currentSession = null;
        }
        raceController.close();
        if (target != null) target.finish(false);
    }

    private AndroidWeb4AgentSession beginTransition() {
        synchronized (lifecycleLock) {
            check(!hostClosed, "Web4Agent exact-effect test host is closed.");
            check(!transitionInProgress, "A fixture session transition is already in progress.");
            check(currentSession != null, "Web4Agent fixture session is closed.");
            transitionInProgress = true;
            return currentSession;
        }
    }

    private long sessionGeneration() {
        synchronized (lifecycleLock) {
            return sessionGeneration;
        }
    }

    private AndroidWeb4AgentSession newSession() {
        return new AndroidWeb4AgentSession(
                applicationContext,
                sessionId,
                configuration,
                System::currentTimeMillis,
                ignored -> Unit.INSTANCE,
                hooks
        );
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    /** The only deterministic window exposed by this fixture. */
    public enum RaceStage {
        AFTER_GUARD_BEFORE_DISPATCH
    }

    /**
     * Opaque proof bound to the old session generation and its internal effect
     * lease. The raw lease is never exposed.
     */
    public static final class RaceWindow {
        private final String windowId;
        private final String sessionId;
        private final long sessionGeneration;
        private final String effectToken;
        private final RaceStage stage;

        private RaceWindow(
                String windowId,
                String sessionId,
                long sessionGeneration,
                String effectToken
        ) {
            this.windowId = windowId;
            this.sessionId = sessionId;
            this.sessionGeneration = sessionGeneration;
            this.effectToken = effectToken;
            this.stage = RaceStage.AFTER_GUARD_BEFORE_DISPATCH;
        }

        public String getWindowId() {
            return windowId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public long getSessionGeneration() {
            return sessionGeneration;
        }

        public String getEffectToken() {
            return effectToken;
        }

        public RaceStage getStage() {
            return stage;
        }
    }

    /** One-shot gate armed for the next governed exact effect. */
    public static final class RaceGate implements AutoCloseable {
        private static final long DEFAULT_WAIT_MILLIS = 10_000L;
        private static final long MIN_WAIT_MILLIS = 100L;
        private static final long MAX_WAIT_MILLIS = 30_000L;

        private final String windowId;
        private final String sessionId;
        private final long sessionGeneration;
        private final long maxHoldMillis;
        private final Function<String, String> effectToken;
        private final CompletableFuture<RaceWindow> entered = new CompletableFuture<>();
        private final CompletableFuture<Void> fenced = new CompletableFuture<>();
        private final CountDownLatch releaseLatch = new CountDownLatch(1);
        private volatile boolean holdTimedOut;

        private RaceGate(
                String windowId,
                String sessionId,
                long sessionGeneration,
                long maxHoldMillis,
                Function<String, String> effectToken
        ) {
            this.windowId = windowId;
            this.sessionId = sessionId;
            this.sessionGeneration = sessionGeneration;
            this.maxHoldMillis = maxHoldMillis;
            this.effectToken = effectToken;
        }

        public RaceWindow awaitAfterGuardBeforeDispatch() {
            return awaitAfterGuardBeforeDispatch(DEFAULT_WAIT_MILLIS);
        }

        public RaceWindow awaitAfterGuardBeforeDispatch(long timeoutMillis) {
            try {
                return entered.get(requireWaitMillis(timeoutMillis), TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                throw new IllegalStateException(
                        "Exact Web4Agent effect did not reach AFTER_GUARD_BEFORE_DISPATCH in time.",
                        timeout
                );
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Fixture wait was interrupted.", interrupted);
            } catch (ExecutionException failure) {
                throw new IllegalStateException("Fixture race window failed.", failure.getCause());
            }
        }

        public void awaitSessionFenced() {
            awaitSessionFenced(DEFAULT_WAIT_MILLIS);
        }

        public void awaitSessionFenced(long timeoutMillis) {
            try {
                fenced.get(requireWaitMillis(timeoutMillis), TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                throw new IllegalStateException(
                        "Exact Web4Agent session was not fenced in time.",
                        timeout
                );
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Fixture wait was interrupted.", interrupted);
            } catch (ExecutionException failure) {
                throw new IllegalStateException("Fixture session fence failed.", failure.getCause());
            }
        }

        public void release() {
            releaseLatch.countDown();
        }

        public boolean getHoldTimedOut() {
            return holdTimedOut;
        }

        @Override
        public void close() {
            release();
        }

        private void enter(String actualSessionId, String leaseId) {
            check(sessionId.equals(actualSessionId),
                    "Exact-effect gate received a different session.");
            RaceWindow window = new RaceWindow(
                    windowId,
                    sessionId,
                    sessionGeneration,
                    effectToken.apply(leaseId)
            );
            check(entered.complete(window),
                    "Exact-effect gate is one-shot and already captured an effect.");
            try {
                if (!releaseLatch.await(maxHoldMillis, TimeUnit.MILLISECONDS)) {
                    holdTimedOut = true;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                holdTimedOut = true;
            }
        }

        private void markSessionFenced(String actualSessionId) {
            if (sessionId.equals(actualSessionId)) fenced.complete(null);
        }

        private static long requireWaitMillis(long value) {
            if (value < MIN_WAIT_MILLIS || value > MAX_WAIT_MILLIS) {
                throw new IllegalArgumentException(
                        "Fixture wait must be from " + MIN_WAIT_MILLIS + " to " +
                                MAX_WAIT_MILLIS + " milliseconds."
                );
            }
            return value;
        }
    }

    /** Opaque controller that can only observe/release the exact race window. */
    public static final class RaceController implements AutoCloseable {
        private final String sessionId;
        private final LongSupplier generation;
        private final String secret = UUID.randomUUID().toString();
        private final AtomicReference<RaceGate> activeGate = new AtomicReference<>();

        private RaceController(String sessionId, LongSupplier generation) {
            this.sessionId = sessionId;
            this.generation = generation;
        }

        public RaceGate armNextEffect() {
            return armNextEffect(RaceGate.MAX_WAIT_MILLIS);
        }

        public RaceGate armNextEffect(long maxHoldMillis) {
            RaceGate.requireWaitMillis(maxHoldMillis);
            RaceGate gate = new RaceGate(
                    "web-exact-window-" + UUID.randomUUID(),
                    sessionId,
                    generation.getAsLong(),
                    maxHoldMillis,
                    this::effectToken
            );
            check(activeGate.compareAndSet(null, gate),
                    "An exact-effect race gate is already armed or blocked.");
            return gate;
        }

        @Override
        public void close() {
            RaceGate gate = activeGate.getAndSet(null);
            if (gate != null) gate.release();
        }

        private void afterGuardBeforeDispatch(String actualSessionId, String leaseId) {
            RaceGate gate = activeGate.get();
            if (gate == null) return;
            try {
                gate.enter(actualSessionId, leaseId);
            } finally {
                activeGate.compareAndSet(gate, null);
            }
        }

        private void afterSessionFenced(String actualSessionId) {
            RaceGate gate = activeGate.get();
            if (gate != null) gate.markSessionFenced(actualSessionId);
        }

        private String effectToken(String leaseId) {
            final byte[] digest;
            try {
                digest = MessageDigest.getInstance("SHA-256").digest(
                        (secret + ':' + leaseId).getBytes(StandardCharsets.UTF_8)
                );
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable.", impossible);
            }
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                encoded.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return encoded.toString();
        }
    }
}
