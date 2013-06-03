/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package co.paralleluniverse.actors.behaviors;

import co.paralleluniverse.actors.Actor;
import co.paralleluniverse.actors.ActorSpec;
import co.paralleluniverse.actors.BasicActor;
import co.paralleluniverse.actors.LifecycleMessage;
import co.paralleluniverse.actors.LocalActor;
import co.paralleluniverse.actors.ShutdownMessage;
import co.paralleluniverse.actors.behaviors.Supervisor.ChildSpec;
import co.paralleluniverse.actors.behaviors.Supervisor.ChildMode;
import co.paralleluniverse.actors.behaviors.Supervisor.RestartStrategy;
import co.paralleluniverse.common.util.Exceptions;
import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.Strand;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jsr166e.ForkJoinPool;
import org.junit.Test;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;
import org.junit.Ignore;

/**
 * These tests are also good tests for sendSync, as they test sendSync (and receive) from both fibers and threads.
 *
 * @author pron
 */
public class SupervisorTest {
    static final int mailboxSize = 10;
    private ForkJoinPool fjPool;

    public SupervisorTest() throws Exception {
        fjPool = new ForkJoinPool(4, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true);
        java.util.logging.LogManager.getLogManager().readConfiguration(); // gradle messes with the configurations
    }

    private <T extends LocalActor<Message, V>, Message, V> T spawnActor(T actor) {
        Fiber fiber = new Fiber(fjPool, actor);
        fiber.start();
        return actor;
    }

    private static class Actor1 extends BasicActor<Object, Integer> {
        public Actor1(String name) {
            super(name);
        }

        @Override
        protected Integer doRun() throws SuspendExecution, InterruptedException {
            register();
            int i = 0;
            try {
                for (;;) {
                    Object m = receive();
                    i++;
                }
            } catch (InterruptedException e) {
                return i;
            }
        }

        @Override
        protected void handleLifecycleMessage(LifecycleMessage m) {
            if (m instanceof ShutdownMessage)
                Strand.currentStrand().interrupt();
            else
                super.handleLifecycleMessage(m);
        }
    }

    private static class BadActor1 extends BasicActor<Object, Integer> {
        public BadActor1(String name) {
            super(name);
        }

        @Override
        protected Integer doRun() throws SuspendExecution, InterruptedException {
            register();
            int i = 0;
            try {
                for (;;) {
                    Object m = receive();
                    i++;
                    throw new RuntimeException("Ha!");
                }
            } catch (InterruptedException e) {
                return i;
            }
        }
    }

    private <Message, V> LocalActor<Message, V> getRegisteredActor(String name, long timeout) throws InterruptedException {
        LocalActor<Message, V> a;
        final long start = System.nanoTime();
        while ((a = LocalActor.getActor(name)) == null || a.isDone()) {
            if (System.nanoTime() > start + TimeUnit.MILLISECONDS.toNanos(timeout))
                return null;
            Thread.sleep(10);
        }
        return a;
    }

    private <Message, V> LocalActor<Message, V> getChild(Supervisor sup, String name, long timeout) throws InterruptedException {
        LocalActor<Message, V> a;
        final long start = System.nanoTime();
        while ((a = sup.getChild(name)) == null || a.isDone()) {
            if (System.nanoTime() > start + TimeUnit.MILLISECONDS.toNanos(timeout))
                return null;
            Thread.sleep(10);
        }
        return a;
    }

    @Test
    public void startChild() throws Exception {
        final Supervisor sup = spawnActor(new Supervisor(RestartStrategy.ONE_FOR_ONE,
                new ChildSpec("actor1", ChildMode.PERMANENT, 5, 1, TimeUnit.SECONDS, 3, ActorSpec.of(Actor1.class, "actor1"))));

        LocalActor<Object, Integer> a;

        a = getChild(sup, "actor1", 1000);
        for (int i = 0; i < 3; i++)
            a.send(1);
        a.send(new ShutdownMessage(null));
        assertThat(a.get(), is(3));

        sup.shutdown();
        sup.join();
    }

//    @Test
//    public void startChild2() throws Exception {
//        final Supervisor sup = spawnActor(new Supervisor(RestartStrategy.ONE_FOR_ONE));
//
//        sup.addChild(new ChildSpec("actor1", ActorSpec.of(Actor1.class, "actor1"), ActorMode.PERMANENT, 5, 1, TimeUnit.SECONDS, 3), null);
//        LocalActor<Object, Integer> a;
//
//        a = getChild(sup, "actor1", 1000);
//        for (int i = 0; i < 3; i++)
//            a.send(1);
//        a.send(new ShutdownMessage(null));
//        assertThat(a.get(), is(3));
//        
//        sup.shutdown();
//        sup.join();
//    }
    @Test
    public void whenChildDiesThenRestart() throws Exception {
        final Supervisor sup = spawnActor(new Supervisor(RestartStrategy.ONE_FOR_ONE,
                new ChildSpec("actor1", ChildMode.PERMANENT, 5, 1, TimeUnit.SECONDS, 3, ActorSpec.of(Actor1.class, "actor1"))));

        LocalActor<Object, Integer> a;

        a = getChild(sup, "actor1", 1000);
        for (int i = 0; i < 3; i++)
            a.send(1);
        a.send(new ShutdownMessage(null));
        assertThat(a.get(), is(3));


        a = getChild(sup, "actor1", 1000);
        for (int i = 0; i < 5; i++)
            a.send(1);
        a.send(new ShutdownMessage(null));
        assertThat(a.get(), is(5));


        sup.shutdown();
        sup.join();
    }

    @Test
    public void whenChildDiesTooManyTimesThenGiveUpAndDie() throws Exception {
        final Supervisor sup = spawnActor(new Supervisor(RestartStrategy.ONE_FOR_ONE,
                new ChildSpec("actor1", ChildMode.PERMANENT, 3, 1, TimeUnit.SECONDS, 3, ActorSpec.of(BadActor1.class, "actor1"))));

        LocalActor<Object, Integer> a, prevA = null;

        for (int k = 0; k < 4; k++) {
            a = getChild(sup, "actor1", 1000);
            assertThat(a, not(prevA));

            a.send(1);

            try {
                a.join();
                fail();
            } catch (ExecutionException e) {
            }

            prevA = a;
        }

        sup.join(20, TimeUnit.MILLISECONDS);
    }

    @Test
    public void dontRestartTemporaryChildDeadOfNaturalCause() throws Exception {
        final Supervisor sup = spawnActor(new Supervisor(RestartStrategy.ONE_FOR_ONE,
                new ChildSpec("actor1", ChildMode.TEMPORARY, 5, 1, TimeUnit.SECONDS, 3, ActorSpec.of(Actor1.class, "actor1"))));

        LocalActor<Object, Integer> a;

        a = getChild(sup, "actor1", 1000);
        for (int i = 0; i < 3; i++)
            a.send(1);
        a.send(new ShutdownMessage(null));
        assertThat(a.get(), is(3));


        a = getChild(sup, "actor1", 200);
        assertThat(a, nullValue());


        sup.shutdown();
        sup.join();
    }

    @Test
    public void dontRestartTemporaryChildDeadOfUnnaturalCause() throws Exception {
        final Supervisor sup = spawnActor(new Supervisor(RestartStrategy.ONE_FOR_ONE,
                new ChildSpec("actor1", ChildMode.TEMPORARY, 5, 1, TimeUnit.SECONDS, 3, ActorSpec.of(BadActor1.class, "actor1"))));

        LocalActor<Object, Integer> a;

        a = getChild(sup, "actor1", 1000);
        for (int i = 0; i < 3; i++)
            a.send(1);
        a.send(new ShutdownMessage(null));
        try {
            a.join();
            fail();
        } catch (ExecutionException e) {
        }

        a = getChild(sup, "actor1", 200);
        assertThat(a, nullValue());

        sup.shutdown();
        sup.join();
    }

    @Test
    public void dontRestartTransientChildDeadOfNaturalCause() throws Exception {
        final Supervisor sup = spawnActor(new Supervisor(RestartStrategy.ONE_FOR_ONE,
                new ChildSpec("actor1", ChildMode.TRANSIENT, 5, 1, TimeUnit.SECONDS, 3, ActorSpec.of(Actor1.class, "actor1"))));

        LocalActor<Object, Integer> a;

        a = getChild(sup, "actor1", 1000);
        for (int i = 0; i < 3; i++)
            a.send(1);
        a.send(new ShutdownMessage(null));
        assertThat(a.get(), is(3));


        a = getChild(sup, "actor1", 200);
        assertThat(a, nullValue());


        sup.shutdown();
        sup.join();
    }

    @Test
    public void restartTransientChildDeadOfUnnaturalCause() throws Exception {
        final Supervisor sup = spawnActor(new Supervisor(RestartStrategy.ONE_FOR_ONE,
                new ChildSpec("actor1", ChildMode.TRANSIENT, 5, 1, TimeUnit.SECONDS, 3, ActorSpec.of(BadActor1.class, "actor1"))));

        LocalActor<Object, Integer> a;

        a = getChild(sup, "actor1", 1000);
        for (int i = 0; i < 3; i++)
            a.send(1);
        a.send(new ShutdownMessage(null));
        try {
            a.join();
            fail();
        } catch (ExecutionException e) {
        }


        LocalActor<Object, Integer> b = getChild(sup, "actor1", 200);
        assertThat(b, not(nullValue()));
        assertThat(b, not(a));

        sup.shutdown();
        sup.join();
    }
}