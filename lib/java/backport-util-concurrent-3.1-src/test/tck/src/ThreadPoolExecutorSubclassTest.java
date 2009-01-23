/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/licenses/publicdomain
 * Other contributors include Andrew Wright, Jeffrey Hayes,
 * Pat Fisher, Mike Judd.
 */

import edu.emory.mathcs.backport.java.util.concurrent.*;
import edu.emory.mathcs.backport.java.util.concurrent.locks.*;

import junit.framework.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import edu.emory.mathcs.backport.java.util.concurrent.helpers.*;

public class ThreadPoolExecutorSubclassTest extends JSR166TestCase {
    public static void main(String[] args) {
	junit.textui.TestRunner.run (suite());
    }
    public static Test suite() {
        return new TestSuite(ThreadPoolExecutorTest.class);
    }

    static class CustomTask implements RunnableFuture {
        final Callable callable;
        final ReentrantLock lock = new ReentrantLock();
        final Condition cond = lock.newCondition();
        boolean done;
        boolean cancelled;
        Object result;
        Thread thread;
        Exception exception;
        CustomTask(Callable c) { callable = c; }
        CustomTask(final Runnable r, final Object res) { callable = new Callable() {
            public Object call() throws Exception { r.run(); return res; }};
        }
        public boolean isDone() {
            lock.lock(); try { return done; } finally { lock.unlock() ; }
        }
        public boolean isCancelled() {
            lock.lock(); try { return cancelled; } finally { lock.unlock() ; }
        }
        public boolean cancel(boolean mayInterrupt) {
            lock.lock();
            try {
                if (!done) {
                    cancelled = true;
                    done = true;
                    if (mayInterrupt && thread != null)
                        thread.interrupt();
                    return true;
                }
                return false;
            }
            finally { lock.unlock() ; }
        }
        public void run() {
            boolean runme;
            lock.lock();
            try {
                runme = !done;
                if (!runme)
                    thread = Thread.currentThread();
            }
            finally { lock.unlock() ; }
            if (!runme) return;
            Object v = null;
            Exception e = null;
            try {
                v = callable.call();
            }
            catch(Exception ex) {
                e = ex;
            }
            lock.lock();
            try {
                result = v;
                exception = e;
                done = true;
                thread = null;
                cond.signalAll();
            }
            finally { lock.unlock(); }
        }
        public Object get() throws InterruptedException, ExecutionException {
            lock.lock();
            try {
                while (!done)
                    cond.await();
                if (exception != null)
                    throw new ExecutionException(exception);
                return result;
            }
            finally { lock.unlock(); }
        }
        public Object get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException{
            long nanos = unit.toNanos(timeout);
            lock.lock();
            try {
                long deadline = Utils.nanoTime() + nanos;
                for (;;) {
                    if (done) break;
                    if (nanos < 0)
                        throw new TimeoutException();
                    cond.await(nanos, TimeUnit.NANOSECONDS);
                    nanos = deadline - Utils.nanoTime();
                }
                if (exception != null)
                    throw new ExecutionException(exception);
                return result;
            }
            finally { lock.unlock(); }
        }
    }


    static class CustomTPE extends ThreadPoolExecutor {
        protected RunnableFuture newTaskFor(Callable c) {
            return new CustomTask(c);
        }
        protected RunnableFuture newTaskFor(Runnable r, Object v) {
            return new CustomTask(r, v);
        }

        CustomTPE(int corePoolSize,
                  int maximumPoolSize,
                  long keepAliveTime,
                  TimeUnit unit,
                  BlockingQueue workQueue) {
            super(corePoolSize, maximumPoolSize, keepAliveTime, unit,
                  workQueue);
        }
        CustomTPE(int corePoolSize,
                  int maximumPoolSize,
                  long keepAliveTime,
                  TimeUnit unit,
                  BlockingQueue workQueue,
                  ThreadFactory threadFactory) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             threadFactory);
        }

        CustomTPE(int corePoolSize,
                  int maximumPoolSize,
                  long keepAliveTime,
                  TimeUnit unit,
                  BlockingQueue workQueue,
                  RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
              handler);
        }
        CustomTPE(int corePoolSize,
                  int maximumPoolSize,
                  long keepAliveTime,
                  TimeUnit unit,
                  BlockingQueue workQueue,
                  ThreadFactory threadFactory,
                  RejectedExecutionHandler handler) {
            super(corePoolSize, maximumPoolSize, keepAliveTime, unit,
              workQueue, threadFactory, handler);
        }

        volatile boolean beforeCalled = false;
        volatile boolean afterCalled = false;
        volatile boolean terminatedCalled = false;
        public CustomTPE() {
            super(1, 1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new SynchronousQueue());
        }
        protected void beforeExecute(Thread t, Runnable r) {
            beforeCalled = true;
        }
        protected void afterExecute(Runnable r, Throwable t) {
            afterCalled = true;
        }
        protected void terminated() {
            terminatedCalled = true;
        }

    }

    static class FailingThreadFactory implements ThreadFactory{
        int calls = 0;
        public Thread newThread(Runnable r){
            if (++calls > 1) return null;
            return new Thread(r);
        }
    }


    /**
     *  execute successfully executes a runnable
     */
    public void testExecute() {
        ThreadPoolExecutor p1 = new CustomTPE(1, 1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            p1.execute(new Runnable() {
                    public void run() {
                        try {
                            Thread.sleep(SHORT_DELAY_MS);
                        } catch(InterruptedException e){
                            threadUnexpectedException();
                        }
                    }
                });
	    Thread.sleep(SMALL_DELAY_MS);
        } catch(InterruptedException e){
            unexpectedException();
        }
        joinPool(p1);
    }

    /**
     *  getActiveCount increases but doesn't overestimate, when a
     *  thread becomes active
     */
    public void testGetActiveCount() {
        ThreadPoolExecutor p2 = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        assertEquals(0, p2.getActiveCount());
        p2.execute(new MediumRunnable());
        try {
            Thread.sleep(SHORT_DELAY_MS);
        } catch(Exception e){
            unexpectedException();
        }
        assertEquals(1, p2.getActiveCount());
        joinPool(p2);
    }

    /**
     *  prestartCoreThread starts a thread if under corePoolSize, else doesn't
     */
    public void testPrestartCoreThread() {
        ThreadPoolExecutor p2 = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        assertEquals(0, p2.getPoolSize());
        assertTrue(p2.prestartCoreThread());
        assertEquals(1, p2.getPoolSize());
        assertTrue(p2.prestartCoreThread());
        assertEquals(2, p2.getPoolSize());
        assertFalse(p2.prestartCoreThread());
        assertEquals(2, p2.getPoolSize());
        joinPool(p2);
    }

    /**
     *  prestartAllCoreThreads starts all corePoolSize threads
     */
    public void testPrestartAllCoreThreads() {
        ThreadPoolExecutor p2 = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        assertEquals(0, p2.getPoolSize());
        p2.prestartAllCoreThreads();
        assertEquals(2, p2.getPoolSize());
        p2.prestartAllCoreThreads();
        assertEquals(2, p2.getPoolSize());
        joinPool(p2);
    }

    /**
     *   getCompletedTaskCount increases, but doesn't overestimate,
     *   when tasks complete
     */
    public void testGetCompletedTaskCount() {
        ThreadPoolExecutor p2 = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        assertEquals(0, p2.getCompletedTaskCount());
        p2.execute(new ShortRunnable());
        try {
            Thread.sleep(SMALL_DELAY_MS);
        } catch(Exception e){
            unexpectedException();
        }
        assertEquals(1, p2.getCompletedTaskCount());
        try { p2.shutdown(); } catch(SecurityException ok) { return; }
        joinPool(p2);
    }

    /**
     *   getCorePoolSize returns size given in constructor if not otherwise set
     */
    public void testGetCorePoolSize() {
        ThreadPoolExecutor p1 = new CustomTPE(1, 1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        assertEquals(1, p1.getCorePoolSize());
        joinPool(p1);
    }

    /**
     *   getKeepAliveTime returns value given in constructor if not otherwise set
     */
    public void testGetKeepAliveTime() {
        ThreadPoolExecutor p2 = new CustomTPE(2, 2, 1000, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        assertEquals(1, p2.getKeepAliveTime(TimeUnit.SECONDS));
        joinPool(p2);
    }


    /**
     * getThreadFactory returns factory in constructor if not set
     */
    public void testGetThreadFactory() {
        ThreadFactory tf = new SimpleThreadFactory();
        ThreadPoolExecutor p = new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10), tf, new NoOpREHandler());
        assertSame(tf, p.getThreadFactory());
        joinPool(p);
    }

    /**
     * setThreadFactory sets the thread factory returned by getThreadFactory
     */
    public void testSetThreadFactory() {
        ThreadPoolExecutor p = new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        ThreadFactory tf = new SimpleThreadFactory();
        p.setThreadFactory(tf);
        assertSame(tf, p.getThreadFactory());
        joinPool(p);
    }


    /**
     * setThreadFactory(null) throws NPE
     */
    public void testSetThreadFactoryNull() {
        ThreadPoolExecutor p = new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            p.setThreadFactory(null);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            joinPool(p);
        }
    }

    /**
     * getRejectedExecutionHandler returns handler in constructor if not set
     */
    public void testGetRejectedExecutionHandler() {
        RejectedExecutionHandler h = new NoOpREHandler();
        ThreadPoolExecutor p = new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10), h);
        assertSame(h, p.getRejectedExecutionHandler());
        joinPool(p);
    }

    /**
     * setRejectedExecutionHandler sets the handler returned by
     * getRejectedExecutionHandler
     */
    public void testSetRejectedExecutionHandler() {
        ThreadPoolExecutor p = new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        RejectedExecutionHandler h = new NoOpREHandler();
        p.setRejectedExecutionHandler(h);
        assertSame(h, p.getRejectedExecutionHandler());
        joinPool(p);
    }


    /**
     * setRejectedExecutionHandler(null) throws NPE
     */
    public void testSetRejectedExecutionHandlerNull() {
        ThreadPoolExecutor p = new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            p.setRejectedExecutionHandler(null);
            shouldThrow();
        } catch (NullPointerException success) {
        } finally {
            joinPool(p);
        }
    }


    /**
     *   getLargestPoolSize increases, but doesn't overestimate, when
     *   multiple threads active
     */
    public void testGetLargestPoolSize() {
        ThreadPoolExecutor p2 = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            assertEquals(0, p2.getLargestPoolSize());
            p2.execute(new MediumRunnable());
            p2.execute(new MediumRunnable());
            Thread.sleep(SHORT_DELAY_MS);
            assertEquals(2, p2.getLargestPoolSize());
        } catch(Exception e){
            unexpectedException();
        }
        joinPool(p2);
    }

    /**
     *   getMaximumPoolSize returns value given in constructor if not
     *   otherwise set
     */
    public void testGetMaximumPoolSize() {
        ThreadPoolExecutor p2 = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        assertEquals(2, p2.getMaximumPoolSize());
        joinPool(p2);
    }

    /**
     *   getPoolSize increases, but doesn't overestimate, when threads
     *   become active
     */
    public void testGetPoolSize() {
        ThreadPoolExecutor p1 = new CustomTPE(1, 1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        assertEquals(0, p1.getPoolSize());
        p1.execute(new MediumRunnable());
        assertEquals(1, p1.getPoolSize());
        joinPool(p1);
    }

    /**
     *  getTaskCount increases, but doesn't overestimate, when tasks submitted
     */
    public void testGetTaskCount() {
        ThreadPoolExecutor p1 = new CustomTPE(1, 1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            assertEquals(0, p1.getTaskCount());
            p1.execute(new MediumRunnable());
            Thread.sleep(SHORT_DELAY_MS);
            assertEquals(1, p1.getTaskCount());
        } catch(Exception e){
            unexpectedException();
        }
        joinPool(p1);
    }

    /**
     *   isShutDown is false before shutdown, true after
     */
    public void testIsShutdown() {

	ThreadPoolExecutor p1 = new CustomTPE(1, 1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        assertFalse(p1.isShutdown());
        try { p1.shutdown(); } catch(SecurityException ok) { return; }
	assertTrue(p1.isShutdown());
        joinPool(p1);
    }


    /**
     *  isTerminated is false before termination, true after
     */
    public void testIsTerminated() {
	ThreadPoolExecutor p1 = new CustomTPE(1, 1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        assertFalse(p1.isTerminated());
        try {
            p1.execute(new MediumRunnable());
        } finally {
            try { p1.shutdown(); } catch(SecurityException ok) { return; }
        }
	try {
	    assertTrue(p1.awaitTermination(LONG_DELAY_MS, TimeUnit.MILLISECONDS));
            assertTrue(p1.isTerminated());
	} catch(Exception e){
            unexpectedException();
        }
    }

    /**
     *  isTerminating is not true when running or when terminated
     */
    public void testIsTerminating() {
	ThreadPoolExecutor p1 = new CustomTPE(1, 1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        assertFalse(p1.isTerminating());
        try {
            p1.execute(new SmallRunnable());
            assertFalse(p1.isTerminating());
        } finally {
            try { p1.shutdown(); } catch(SecurityException ok) { return; }
        }
        try {
	    assertTrue(p1.awaitTermination(LONG_DELAY_MS, TimeUnit.MILLISECONDS));
            assertTrue(p1.isTerminated());
            assertFalse(p1.isTerminating());
	} catch(Exception e){
            unexpectedException();
        }
    }

    /**
     * getQueue returns the work queue, which contains queued tasks
     */
    public void testGetQueue() {
        BlockingQueue q = new ArrayBlockingQueue(10);
        ThreadPoolExecutor p1 = new CustomTPE(1, 1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, q);
        FutureTask[] tasks = new FutureTask[5];
        for(int i = 0; i < 5; i++){
            tasks[i] = new FutureTask(new MediumPossiblyInterruptedRunnable(), Boolean.TRUE);
            p1.execute(tasks[i]);
        }
        try {
            Thread.sleep(SHORT_DELAY_MS);
            BlockingQueue wq = p1.getQueue();
            assertSame(q, wq);
            assertFalse(wq.contains(tasks[0]));
            assertTrue(wq.contains(tasks[4]));
            for (int i = 1; i < 5; ++i)
                tasks[i].cancel(true);
            p1.shutdownNow();
        } catch(Exception e) {
            unexpectedException();
        } finally {
            joinPool(p1);
        }
    }

    /**
     * remove(task) removes queued task, and fails to remove active task
     */
    public void testRemove() {
        BlockingQueue q = new ArrayBlockingQueue(10);
        ThreadPoolExecutor p1 = new CustomTPE(1, 1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, q);
        FutureTask[] tasks = new FutureTask[5];
        for(int i = 0; i < 5; i++){
            tasks[i] = new FutureTask(new MediumPossiblyInterruptedRunnable(), Boolean.TRUE);
            p1.execute(tasks[i]);
        }
        try {
            Thread.sleep(SHORT_DELAY_MS);
            assertFalse(p1.remove(tasks[0]));
            assertTrue(q.contains(tasks[4]));
            assertTrue(q.contains(tasks[3]));
            assertTrue(p1.remove(tasks[4]));
            assertFalse(p1.remove(tasks[4]));
            assertFalse(q.contains(tasks[4]));
            assertTrue(q.contains(tasks[3]));
            assertTrue(p1.remove(tasks[3]));
            assertFalse(q.contains(tasks[3]));
        } catch(Exception e) {
            unexpectedException();
        } finally {
            joinPool(p1);
        }
    }

    /**
     *   purge removes cancelled tasks from the queue
     */
    public void testPurge() {
        ThreadPoolExecutor p1 = new CustomTPE(1, 1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        FutureTask[] tasks = new FutureTask[5];
        for(int i = 0; i < 5; i++){
            tasks[i] = new FutureTask(new MediumPossiblyInterruptedRunnable(), Boolean.TRUE);
            p1.execute(tasks[i]);
        }
        tasks[4].cancel(true);
        tasks[3].cancel(true);
        p1.purge();
        long count = p1.getTaskCount();
        assertTrue(count >= 2 && count < 5);
        joinPool(p1);
    }

    /**
     *  shutDownNow returns a list containing tasks that were not run
     */
    public void testShutDownNow() {
	ThreadPoolExecutor p1 = new CustomTPE(1, 1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        List l;
        try {
            for(int i = 0; i < 5; i++)
                p1.execute(new MediumPossiblyInterruptedRunnable());
        }
        finally {
            try {
                l = p1.shutdownNow();
            } catch (SecurityException ok) { return; }

        }
	assertTrue(p1.isShutdown());
	assertTrue(l.size() <= 4);
    }

    // Exception Tests


    /**
     * Constructor throws if corePoolSize argument is less than zero
     */
    public void testConstructor1() {
        try {
            new CustomTPE(-1,1,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if maximumPoolSize is less than zero
     */
    public void testConstructor2() {
        try {
            new CustomTPE(1,-1,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if maximumPoolSize is equal to zero
     */
    public void testConstructor3() {
        try {
            new CustomTPE(1,0,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if keepAliveTime is less than zero
     */
    public void testConstructor4() {
        try {
            new CustomTPE(1,2,-1L,TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if corePoolSize is greater than the maximumPoolSize
     */
    public void testConstructor5() {
        try {
            new CustomTPE(2,1,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if workQueue is set to null
     */
    public void testConstructorNullPointerException() {
        try {
            new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS,null);
            shouldThrow();
        }
        catch (NullPointerException success){}
    }



    /**
     * Constructor throws if corePoolSize argument is less than zero
     */
    public void testConstructor6() {
        try {
            new CustomTPE(-1,1,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new SimpleThreadFactory());
            shouldThrow();
        } catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if maximumPoolSize is less than zero
     */
    public void testConstructor7() {
        try {
            new CustomTPE(1,-1,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new SimpleThreadFactory());
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if maximumPoolSize is equal to zero
     */
    public void testConstructor8() {
        try {
            new CustomTPE(1,0,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new SimpleThreadFactory());
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if keepAliveTime is less than zero
     */
    public void testConstructor9() {
        try {
            new CustomTPE(1,2,-1L,TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new SimpleThreadFactory());
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if corePoolSize is greater than the maximumPoolSize
     */
    public void testConstructor10() {
        try {
            new CustomTPE(2,1,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new SimpleThreadFactory());
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if workQueue is set to null
     */
    public void testConstructorNullPointerException2() {
        try {
            new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS,null,new SimpleThreadFactory());
            shouldThrow();
        }
        catch (NullPointerException success){}
    }

    /**
     * Constructor throws if threadFactory is set to null
     */
    public void testConstructorNullPointerException3() {
        try {
            ThreadFactory f = null;
            new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS,new ArrayBlockingQueue(10),f);
            shouldThrow();
        }
        catch (NullPointerException success){}
    }


    /**
     * Constructor throws if corePoolSize argument is less than zero
     */
    public void testConstructor11() {
        try {
            new CustomTPE(-1,1,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new NoOpREHandler());
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if maximumPoolSize is less than zero
     */
    public void testConstructor12() {
        try {
            new CustomTPE(1,-1,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new NoOpREHandler());
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if maximumPoolSize is equal to zero
     */
    public void testConstructor13() {
        try {
            new CustomTPE(1,0,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new NoOpREHandler());
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if keepAliveTime is less than zero
     */
    public void testConstructor14() {
        try {
            new CustomTPE(1,2,-1L,TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new NoOpREHandler());
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if corePoolSize is greater than the maximumPoolSize
     */
    public void testConstructor15() {
        try {
            new CustomTPE(2,1,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new NoOpREHandler());
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if workQueue is set to null
     */
    public void testConstructorNullPointerException4() {
        try {
            new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS,null,new NoOpREHandler());
            shouldThrow();
        }
        catch (NullPointerException success){}
    }

    /**
     * Constructor throws if handler is set to null
     */
    public void testConstructorNullPointerException5() {
        try {
            RejectedExecutionHandler r = null;
            new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS,new ArrayBlockingQueue(10),r);
            shouldThrow();
        }
        catch (NullPointerException success){}
    }


    /**
     * Constructor throws if corePoolSize argument is less than zero
     */
    public void testConstructor16() {
        try {
            new CustomTPE(-1,1,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new SimpleThreadFactory(),new NoOpREHandler());
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if maximumPoolSize is less than zero
     */
    public void testConstructor17() {
        try {
            new CustomTPE(1,-1,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new SimpleThreadFactory(),new NoOpREHandler());
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if maximumPoolSize is equal to zero
     */
    public void testConstructor18() {
        try {
            new CustomTPE(1,0,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new SimpleThreadFactory(),new NoOpREHandler());
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if keepAliveTime is less than zero
     */
    public void testConstructor19() {
        try {
            new CustomTPE(1,2,-1L,TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new SimpleThreadFactory(),new NoOpREHandler());
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if corePoolSize is greater than the maximumPoolSize
     */
    public void testConstructor20() {
        try {
            new CustomTPE(2,1,LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10),new SimpleThreadFactory(),new NoOpREHandler());
            shouldThrow();
        }
        catch (IllegalArgumentException success){}
    }

    /**
     * Constructor throws if workQueue is set to null
     */
    public void testConstructorNullPointerException6() {
        try {
            new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS,null,new SimpleThreadFactory(),new NoOpREHandler());
            shouldThrow();
        }
        catch (NullPointerException success){}
    }

    /**
     * Constructor throws if handler is set to null
     */
    public void testConstructorNullPointerException7() {
        try {
            RejectedExecutionHandler r = null;
            new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS,new ArrayBlockingQueue(10),new SimpleThreadFactory(),r);
            shouldThrow();
        }
        catch (NullPointerException success){}
    }

    /**
     * Constructor throws if ThreadFactory is set top null
     */
    public void testConstructorNullPointerException8() {
        try {
            ThreadFactory f = null;
            new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS,new ArrayBlockingQueue(10),f,new NoOpREHandler());
            shouldThrow();
        }
        catch (NullPointerException successdn8){}
    }


    /**
     *  execute throws RejectedExecutionException
     *  if saturated.
     */
    public void testSaturatedExecute() {
        ThreadPoolExecutor p = new CustomTPE(1,1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(1));
        try {

            for(int i = 0; i < 5; ++i){
                p.execute(new MediumRunnable());
            }
            shouldThrow();
        } catch(RejectedExecutionException success){}
        joinPool(p);
    }

    /**
     *  executor using CallerRunsPolicy runs task if saturated.
     */
    public void testSaturatedExecute2() {
        RejectedExecutionHandler h = new CustomTPE.CallerRunsPolicy();
        ThreadPoolExecutor p = new CustomTPE(1,1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(1), h);
        try {

            TrackedNoOpRunnable[] tasks = new TrackedNoOpRunnable[5];
            for(int i = 0; i < 5; ++i){
                tasks[i] = new TrackedNoOpRunnable();
            }
            TrackedLongRunnable mr = new TrackedLongRunnable();
            p.execute(mr);
            for(int i = 0; i < 5; ++i){
                p.execute(tasks[i]);
            }
            for(int i = 1; i < 5; ++i) {
                assertTrue(tasks[i].done);
            }
            try { p.shutdownNow(); } catch(SecurityException ok) { return; }
        } catch(RejectedExecutionException ex){
            unexpectedException();
        } finally {
            joinPool(p);
        }
    }

    /**
     *  executor using DiscardPolicy drops task if saturated.
     */
    public void testSaturatedExecute3() {
        RejectedExecutionHandler h = new CustomTPE.DiscardPolicy();
        ThreadPoolExecutor p = new CustomTPE(1,1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(1), h);
        try {

            TrackedNoOpRunnable[] tasks = new TrackedNoOpRunnable[5];
            for(int i = 0; i < 5; ++i){
                tasks[i] = new TrackedNoOpRunnable();
            }
            p.execute(new TrackedLongRunnable());
            for(int i = 0; i < 5; ++i){
                p.execute(tasks[i]);
            }
            for(int i = 0; i < 5; ++i){
                assertFalse(tasks[i].done);
            }
            try { p.shutdownNow(); } catch(SecurityException ok) { return; }
        } catch(RejectedExecutionException ex){
            unexpectedException();
        } finally {
            joinPool(p);
        }
    }

    /**
     *  executor using DiscardOldestPolicy drops oldest task if saturated.
     */
    public void testSaturatedExecute4() {
        RejectedExecutionHandler h = new CustomTPE.DiscardOldestPolicy();
        ThreadPoolExecutor p = new CustomTPE(1,1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(1), h);
        try {
            p.execute(new TrackedLongRunnable());
            TrackedLongRunnable r2 = new TrackedLongRunnable();
            p.execute(r2);
            assertTrue(p.getQueue().contains(r2));
            TrackedNoOpRunnable r3 = new TrackedNoOpRunnable();
            p.execute(r3);
            assertFalse(p.getQueue().contains(r2));
            assertTrue(p.getQueue().contains(r3));
            try { p.shutdownNow(); } catch(SecurityException ok) { return; }
        } catch(RejectedExecutionException ex){
            unexpectedException();
        } finally {
            joinPool(p);
        }
    }

    /**
     *  execute throws RejectedExecutionException if shutdown
     */
    public void testRejectedExecutionExceptionOnShutdown() {
        ThreadPoolExecutor tpe =
            new CustomTPE(1,1,LONG_DELAY_MS, TimeUnit.MILLISECONDS,new ArrayBlockingQueue(1));
        try { tpe.shutdown(); } catch(SecurityException ok) { return; }
	try {
	    tpe.execute(new NoOpRunnable());
	    shouldThrow();
	} catch(RejectedExecutionException success){}

	joinPool(tpe);
    }

    /**
     *  execute using CallerRunsPolicy drops task on shutdown
     */
    public void testCallerRunsOnShutdown() {
        RejectedExecutionHandler h = new CustomTPE.CallerRunsPolicy();
        ThreadPoolExecutor p = new CustomTPE(1,1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(1), h);

        try { p.shutdown(); } catch(SecurityException ok) { return; }
	try {
            TrackedNoOpRunnable r = new TrackedNoOpRunnable();
	    p.execute(r);
            assertFalse(r.done);
	} catch(RejectedExecutionException success){
            unexpectedException();
        } finally {
            joinPool(p);
        }
    }

    /**
     *  execute using DiscardPolicy drops task on shutdown
     */
    public void testDiscardOnShutdown() {
        RejectedExecutionHandler h = new CustomTPE.DiscardPolicy();
        ThreadPoolExecutor p = new CustomTPE(1,1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(1), h);

        try { p.shutdown(); } catch(SecurityException ok) { return; }
	try {
            TrackedNoOpRunnable r = new TrackedNoOpRunnable();
	    p.execute(r);
            assertFalse(r.done);
	} catch(RejectedExecutionException success){
            unexpectedException();
        } finally {
            joinPool(p);
        }
    }


    /**
     *  execute using DiscardOldestPolicy drops task on shutdown
     */
    public void testDiscardOldestOnShutdown() {
        RejectedExecutionHandler h = new CustomTPE.DiscardOldestPolicy();
        ThreadPoolExecutor p = new CustomTPE(1,1, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(1), h);

        try { p.shutdown(); } catch(SecurityException ok) { return; }
	try {
            TrackedNoOpRunnable r = new TrackedNoOpRunnable();
	    p.execute(r);
            assertFalse(r.done);
	} catch(RejectedExecutionException success){
            unexpectedException();
        } finally {
            joinPool(p);
        }
    }


    /**
     *  execute (null) throws NPE
     */
    public void testExecuteNull() {
        ThreadPoolExecutor tpe = null;
        try {
	    tpe = new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS,new ArrayBlockingQueue(10));
	    tpe.execute(null);
            shouldThrow();
	} catch(NullPointerException success){}

	joinPool(tpe);
    }

    /**
     *  setCorePoolSize of negative value throws IllegalArgumentException
     */
    public void testCorePoolSizeIllegalArgumentException() {
	ThreadPoolExecutor tpe = null;
	try {
	    tpe = new CustomTPE(1,2,LONG_DELAY_MS, TimeUnit.MILLISECONDS,new ArrayBlockingQueue(10));
	} catch(Exception e){}
	try {
	    tpe.setCorePoolSize(-1);
	    shouldThrow();
	} catch(IllegalArgumentException success){
        } finally {
            try { tpe.shutdown(); } catch(SecurityException ok) { return; }
        }
        joinPool(tpe);
    }

    /**
     *  setMaximumPoolSize(int) throws IllegalArgumentException if
     *  given a value less the core pool size
     */
    public void testMaximumPoolSizeIllegalArgumentException() {
        ThreadPoolExecutor tpe = null;
        try {
            tpe = new CustomTPE(2,3,LONG_DELAY_MS, TimeUnit.MILLISECONDS,new ArrayBlockingQueue(10));
        } catch(Exception e){}
        try {
            tpe.setMaximumPoolSize(1);
            shouldThrow();
        } catch(IllegalArgumentException success){
        } finally {
            try { tpe.shutdown(); } catch(SecurityException ok) { return; }
        }
        joinPool(tpe);
    }

    /**
     *  setMaximumPoolSize throws IllegalArgumentException
     *  if given a negative value
     */
    public void testMaximumPoolSizeIllegalArgumentException2() {
        ThreadPoolExecutor tpe = null;
        try {
            tpe = new CustomTPE(2,3,LONG_DELAY_MS, TimeUnit.MILLISECONDS,new ArrayBlockingQueue(10));
        } catch(Exception e){}
        try {
            tpe.setMaximumPoolSize(-1);
            shouldThrow();
        } catch(IllegalArgumentException success){
        } finally {
            try { tpe.shutdown(); } catch(SecurityException ok) { return; }
        }
        joinPool(tpe);
    }


    /**
     *  setKeepAliveTime  throws IllegalArgumentException
     *  when given a negative value
     */
    public void testKeepAliveTimeIllegalArgumentException() {
	ThreadPoolExecutor tpe = null;
        try {
            tpe = new CustomTPE(2,3,LONG_DELAY_MS, TimeUnit.MILLISECONDS,new ArrayBlockingQueue(10));
        } catch(Exception e){}

	try {
            tpe.setKeepAliveTime(-1,TimeUnit.MILLISECONDS);
            shouldThrow();
        } catch(IllegalArgumentException success){
        } finally {
            try { tpe.shutdown(); } catch(SecurityException ok) { return; }
        }
        joinPool(tpe);
    }

    /**
     * terminated() is called on termination
     */
    public void testTerminated() {
        CustomTPE tpe = new CustomTPE();
        try { tpe.shutdown(); } catch(SecurityException ok) { return; }
        assertTrue(tpe.terminatedCalled);
        joinPool(tpe);
    }

    /**
     * beforeExecute and afterExecute are called when executing task
     */
    public void testBeforeAfter() {
        CustomTPE tpe = new CustomTPE();
        try {
            TrackedNoOpRunnable r = new TrackedNoOpRunnable();
            tpe.execute(r);
            Thread.sleep(SHORT_DELAY_MS);
            assertTrue(r.done);
            assertTrue(tpe.beforeCalled);
            assertTrue(tpe.afterCalled);
            try { tpe.shutdown(); } catch(SecurityException ok) { return; }
        }
        catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(tpe);
        }
    }

    /**
     * completed submit of callable returns result
     */
    public void testSubmitCallable() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            Future future = e.submit(new StringTask());
            String result = (String)future.get();
            assertSame(TEST_STRING, result);
        }
        catch (ExecutionException ex) {
            unexpectedException();
        }
        catch (InterruptedException ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * completed submit of runnable returns successfully
     */
    public void testSubmitRunnable() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            Future future = e.submit(new NoOpRunnable());
            future.get();
            assertTrue(future.isDone());
        }
        catch (ExecutionException ex) {
            unexpectedException();
        }
        catch (InterruptedException ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * completed submit of (runnable, result) returns result
     */
    public void testSubmitRunnable2() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            Future future = e.submit(new NoOpRunnable(), TEST_STRING);
            String result = (String)future.get();
            assertSame(TEST_STRING, result);
        }
        catch (ExecutionException ex) {
            unexpectedException();
        }
        catch (InterruptedException ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }





    /**
     * invokeAny(null) throws NPE
     */
    public void testInvokeAny1() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            e.invokeAny(null);
        } catch (NullPointerException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAny(empty collection) throws IAE
     */
    public void testInvokeAny2() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            e.invokeAny(new ArrayList());
        } catch (IllegalArgumentException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAny(c) throws NPE if c has null elements
     */
    public void testInvokeAny3() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new StringTask());
            l.add(null);
            e.invokeAny(l);
        } catch (NullPointerException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAny(c) throws ExecutionException if no task completes
     */
    public void testInvokeAny4() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new NPETask());
            e.invokeAny(l);
        } catch (ExecutionException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAny(c) returns result of some task
     */
    public void testInvokeAny5() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new StringTask());
            l.add(new StringTask());
            String result = (String)e.invokeAny(l);
            assertSame(TEST_STRING, result);
        } catch (ExecutionException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAll(null) throws NPE
     */
    public void testInvokeAll1() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            e.invokeAll(null);
        } catch (NullPointerException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAll(empty collection) returns empty collection
     */
    public void testInvokeAll2() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            List r = e.invokeAll(new ArrayList());
            assertTrue(r.isEmpty());
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAll(c) throws NPE if c has null elements
     */
    public void testInvokeAll3() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new StringTask());
            l.add(null);
            e.invokeAll(l);
        } catch (NullPointerException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * get of element of invokeAll(c) throws exception on failed task
     */
    public void testInvokeAll4() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new NPETask());
            List result = e.invokeAll(l);
            assertEquals(1, result.size());
            for (Iterator it = result.iterator(); it.hasNext();)
                ((Future)it.next()).get();
        } catch(ExecutionException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * invokeAll(c) returns results of all completed tasks
     */
    public void testInvokeAll5() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new StringTask());
            l.add(new StringTask());
            List result = e.invokeAll(l);
            assertEquals(2, result.size());
            for (Iterator it = result.iterator(); it.hasNext();)
                assertSame(TEST_STRING, ((Future)it.next()).get());
        } catch (ExecutionException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }



    /**
     * timed invokeAny(null) throws NPE
     */
    public void testTimedInvokeAny1() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            e.invokeAny(null, MEDIUM_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (NullPointerException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAny(,,null) throws NPE
     */
    public void testTimedInvokeAnyNullTimeUnit() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new StringTask());
            e.invokeAny(l, MEDIUM_DELAY_MS, null);
        } catch (NullPointerException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAny(empty collection) throws IAE
     */
    public void testTimedInvokeAny2() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            e.invokeAny(new ArrayList(), MEDIUM_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (IllegalArgumentException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAny(c) throws NPE if c has null elements
     */
    public void testTimedInvokeAny3() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new StringTask());
            l.add(null);
            e.invokeAny(l, MEDIUM_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (NullPointerException success) {
        } catch(Exception ex) {
            ex.printStackTrace();
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAny(c) throws ExecutionException if no task completes
     */
    public void testTimedInvokeAny4() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new NPETask());
            e.invokeAny(l, MEDIUM_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch(ExecutionException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAny(c) returns result of some task
     */
    public void testTimedInvokeAny5() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new StringTask());
            l.add(new StringTask());
            String result = (String)e.invokeAny(l, MEDIUM_DELAY_MS, TimeUnit.MILLISECONDS);
            assertSame(TEST_STRING, result);
        } catch (ExecutionException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAll(null) throws NPE
     */
    public void testTimedInvokeAll1() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            e.invokeAll(null, MEDIUM_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (NullPointerException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAll(,,null) throws NPE
     */
    public void testTimedInvokeAllNullTimeUnit() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new StringTask());
            e.invokeAll(l, MEDIUM_DELAY_MS, null);
        } catch (NullPointerException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAll(empty collection) returns empty collection
     */
    public void testTimedInvokeAll2() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            List r = e.invokeAll(new ArrayList(), MEDIUM_DELAY_MS, TimeUnit.MILLISECONDS);
            assertTrue(r.isEmpty());
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAll(c) throws NPE if c has null elements
     */
    public void testTimedInvokeAll3() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new StringTask());
            l.add(null);
            e.invokeAll(l, MEDIUM_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (NullPointerException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * get of element of invokeAll(c) throws exception on failed task
     */
    public void testTimedInvokeAll4() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new NPETask());
            List result = e.invokeAll(l, MEDIUM_DELAY_MS, TimeUnit.MILLISECONDS);
            assertEquals(1, result.size());
            for (Iterator it = result.iterator(); it.hasNext();)
                ((Future)it.next()).get();
        } catch(ExecutionException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAll(c) returns results of all completed tasks
     */
    public void testTimedInvokeAll5() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new StringTask());
            l.add(new StringTask());
            List result = e.invokeAll(l, MEDIUM_DELAY_MS, TimeUnit.MILLISECONDS);
            assertEquals(2, result.size());
            for (Iterator it = result.iterator(); it.hasNext();)
                assertSame(TEST_STRING, ((Future)it.next()).get());
        } catch (ExecutionException success) {
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * timed invokeAll(c) cancels tasks not completed by timeout
     */
    public void testTimedInvokeAll6() {
        ExecutorService e = new CustomTPE(2, 2, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        try {
            ArrayList l = new ArrayList();
            l.add(new StringTask());
            l.add(Executors.callable(new MediumPossiblyInterruptedRunnable(), TEST_STRING));
            l.add(new StringTask());
            List result = e.invokeAll(l, SHORT_DELAY_MS, TimeUnit.MILLISECONDS);
            assertEquals(3, result.size());
            Iterator it = result.iterator();
            Future f1 = (Future)it.next();
            Future f2 = (Future)it.next();
            Future f3 = (Future)it.next();
            assertTrue(f1.isDone());
            assertTrue(f2.isDone());
            assertTrue(f3.isDone());
            assertFalse(f1.isCancelled());
            assertTrue(f2.isCancelled());
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * Execution continues if there is at least one thread even if
     * thread factory fails to create more
     */
    public void testFailingThreadFactory() {
        ExecutorService e = new CustomTPE(100, 100, LONG_DELAY_MS, TimeUnit.MILLISECONDS, new LinkedBlockingQueue(), new FailingThreadFactory());
        try {
            ArrayList l = new ArrayList();
            for (int k = 0; k < 100; ++k) {
                e.execute(new NoOpRunnable());
            }
            Thread.sleep(LONG_DELAY_MS);
        } catch(Exception ex) {
            unexpectedException();
        } finally {
            joinPool(e);
        }
    }

    /**
     * allowsCoreThreadTimeOut is by default false.
     */
    public void testAllowsCoreThreadTimeOut() {
        ThreadPoolExecutor tpe = new CustomTPE(2, 2, 1000, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        assertFalse(tpe.allowsCoreThreadTimeOut());
        joinPool(tpe);
    }

    /**
     * allowCoreThreadTimeOut(true) causes idle threads to time out
     */
    public void testAllowCoreThreadTimeOut_true() {
        ThreadPoolExecutor tpe = new CustomTPE(2, 10, 10, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        tpe.allowCoreThreadTimeOut(true);
        tpe.execute(new NoOpRunnable());
        try {
            Thread.sleep(MEDIUM_DELAY_MS);
            assertEquals(0, tpe.getPoolSize());
        } catch(InterruptedException e){
            unexpectedException();
        } finally {
            joinPool(tpe);
        }
    }

    /**
     * allowCoreThreadTimeOut(false) causes idle threads not to time out
     */
    public void testAllowCoreThreadTimeOut_false() {
        ThreadPoolExecutor tpe = new CustomTPE(2, 10, 10, TimeUnit.MILLISECONDS, new ArrayBlockingQueue(10));
        tpe.allowCoreThreadTimeOut(false);
        tpe.execute(new NoOpRunnable());
        try {
            Thread.sleep(MEDIUM_DELAY_MS);
            assertTrue(tpe.getPoolSize() >= 1);
        } catch(InterruptedException e){
            unexpectedException();
        } finally {
            joinPool(tpe);
        }
    }

}