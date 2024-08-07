package com.hpj.admin;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

@RunWith(JUnit4.class)
public class ThreadTests {

    /**
     * 状态，用于判断当前由哪个线程来处理，0：A线程，1：B线程，2：C线程
     */
    private volatile int state;

    /**
     * 执行次数
     */
    private final int times = 10;

    /**
     * 锁
     */
    private final Lock lock = new ReentrantLock();

    /**
     * 锁对象
     */
    private final Object object = new Object();

    @Test
    public void testReentrantLock() throws InterruptedException {
        ThreadTests threadTests = new ThreadTests();
        Thread threadA = new Thread(() -> threadTests.printReentrantLock("A", 0));
        Thread threadB = new Thread(() -> threadTests.printReentrantLock("B", 1));
        Thread threadC = new Thread(() -> threadTests.printReentrantLock("C", 2));
        threadA.start();
        threadB.start();
        threadC.start();
        threadA.join();
        threadB.join();
        threadC.join();
    }

    /**
     * 使用ReentrantLock + 状态量控制
     * @param name         名称
     * @param targetState  目标状态值
     */
    private void printReentrantLock(String name, int targetState) {
        for (int i = 0; i < times; ) {
            lock.lock();
            try {
                while (state % 3 == targetState) {
                    state++;
                    i++;
                    System.out.println(Thread.currentThread().getName() + name);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    @Test
    public void testCyclicBarrier() throws InterruptedException, BrokenBarrierException {
        ThreadTests threadTests = new ThreadTests();
        CyclicBarrier aCyclicBarrier = new CyclicBarrier(2);
        CyclicBarrier bCyclicBarrier = new CyclicBarrier(2);
        CyclicBarrier cCyclicBarrier = new CyclicBarrier(2);
        Thread threadA = new Thread(() -> threadTests.printCyclicBarrier("A", aCyclicBarrier, bCyclicBarrier, false));
        Thread threadB = new Thread(() -> threadTests.printCyclicBarrier("B", bCyclicBarrier, cCyclicBarrier, false));
        Thread threadC = new Thread(() -> threadTests.printCyclicBarrier("C", cCyclicBarrier, aCyclicBarrier, true));
        threadA.start();
        threadB.start();
        threadC.start();
        aCyclicBarrier.await();
        threadA.join();
        threadB.join();
        threadC.join();
    }

    /**
     * 使用CyclicBarrier（循环屏障）实现
     * @param name         名称
     * @param barrier      当前屏障
     * @param nextBarrier  下个屏障
     * @param last         是否最后一个线程
     */
    private void printCyclicBarrier(String name, CyclicBarrier barrier, CyclicBarrier nextBarrier, boolean last) {
        for (int i = 0; i < times; i++) {
            try {
                barrier.await();
                System.out.println(Thread.currentThread().getName() + name);
                barrier.reset();
                if (last && (i == times - 1)) {
                    break;
                }
                nextBarrier.await();
            } catch (BrokenBarrierException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    public void testAwaitNotify() throws InterruptedException, BrokenBarrierException {
        ThreadTests threadTests = new ThreadTests();
        Thread threadA = new Thread(() -> threadTests.printAwaitNotify("A", 0));
        Thread threadB = new Thread(() -> threadTests.printAwaitNotify("B", 1));
        Thread threadC = new Thread(() -> threadTests.printAwaitNotify("C", 2));
        threadA.start();
        threadB.start();
        threadC.start();
        threadA.join();
        threadB.join();
        threadC.join();
    }

    /**
     * 使用await和notifyAll实现
     * @param name        名称
     * @param targetState 目标状态值
     */
    private void printAwaitNotify(String name, int targetState) {
        for (int i = 0; i < times; i++) {
            synchronized (object) {
                while (state % 3 != targetState) {
                    try {
                        object.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                state++;
                System.out.println(Thread.currentThread().getName() + name);
                object.notifyAll();
            }
        }
    }

    @Test
    public void testCondition() throws InterruptedException {
        ThreadTests threadTests = new ThreadTests();
        Condition aCondition = threadTests.lock.newCondition();
        Condition bCondition = threadTests.lock.newCondition();
        Condition cCondition = threadTests.lock.newCondition();
        Thread threadA = new Thread(() -> threadTests.printCondition("A", 0, aCondition, bCondition));
        Thread threadB = new Thread(() -> threadTests.printCondition("B", 1, bCondition, cCondition));
        Thread threadC = new Thread(() -> threadTests.printCondition("C", 2, cCondition, aCondition));
        threadA.start();
        threadB.start();
        threadC.start();
        threadA.join();
        threadB.join();
        threadC.join();
    }

    /**
     * 使用lock+Condition实现
     * @param name        名称
     * @param targetState 目标状态
     * @param now         当前条件
     * @param next        下个线程唤醒条件
     */
    private void printCondition(String name, int targetState, Condition now, Condition next) {
        for (int i = 0; i < times; ) {
            lock.lock();
            try {
                while (state % 3 != targetState) {
                    now.await();
                }
                state++;
                i++;
                System.out.println(Thread.currentThread().getName() + name);
                next.signal();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        }
    }

    private Thread threadA, threadB, threadC;

    @Test
    public void testLockSupport() throws InterruptedException {
        ThreadTests threadTests = new ThreadTests();
        threadTests.threadA = new Thread(() -> threadTests.printLockSupport("A", 0, threadTests.threadB));
        threadTests.threadB = new Thread(() -> threadTests.printLockSupport("B", 1, threadTests.threadC));
        threadTests.threadC = new Thread(() -> threadTests.printLockSupport("C", 2, threadTests.threadA));
        threadTests.threadA.start();
        threadTests.threadB.start();
        threadTests.threadC.start();
        threadTests.threadA.join();
        threadTests.threadB.join();
        threadTests.threadC.join();
    }

    /**
     * 使用LockSupport实现，park用于线程休眠，unpartk用于线程唤醒
     * @param name         名称
     * @param targetState  目标状态
     * @param next         下一个需要唤醒的线程
     */
    private void printLockSupport(String name, int targetState, Thread next) {
        for (int i = 0; i < 10; i++) {
            while (state % 3 != targetState) {
                LockSupport.park();
            }
            state++;
            System.out.println(Thread.currentThread().getName() + name);
            LockSupport.unpark(next);
        }
    }

    private static Map<String, CountDownLatch> latchMap = new HashMap<>();
    private static Map<String, Semaphore> semaphoreMap = new HashMap<>();
    static {
        latchMap.put("A", new CountDownLatch(1));
        latchMap.put("B", new CountDownLatch(1));
        latchMap.put("C", new CountDownLatch(1));
        semaphoreMap.put("A", new Semaphore(0));
        semaphoreMap.put("B", new Semaphore(0));
        semaphoreMap.put("C", new Semaphore(0));
    }

    @Test
    public void testCountDownLatch() throws InterruptedException {
        ThreadTests threadTests = new ThreadTests();
        Thread threadA = new Thread(() -> threadTests.printCountDownLatch("A", "B"));
        Thread threadB = new Thread(() -> threadTests.printCountDownLatch("B", "C"));
        Thread threadC = new Thread(() -> threadTests.printCountDownLatch("C", "A"));
        threadA.start();
        threadB.start();
        threadC.start();
        latchMap.get("A").countDown();
        threadA.join();
        threadB.join();
        threadC.join();
    }

    /**
     * 使用CountDownLatch实现
     * @param name        名称
     * @param nextName    下一个需要唤醒的线程名称
     */
    private void printCountDownLatch(String name, String nextName) {
        for (int i = 0; i < 10; i++) {
            try {
                latchMap.get(name).await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            latchMap.put(name, new CountDownLatch(1));
            System.out.println(Thread.currentThread().getName() + name);
            latchMap.get(nextName).countDown();
        }
    }

    @Test
    public void testSemaphore() throws InterruptedException {
        ThreadTests threadTests = new ThreadTests();
        Thread threadA = new Thread(() -> threadTests.printSemaphore("A", "B"));
        Thread threadB = new Thread(() -> threadTests.printSemaphore("B", "C"));
        Thread threadC = new Thread(() -> threadTests.printSemaphore("C", "A"));
        threadA.start();
        threadB.start();
        threadC.start();
        semaphoreMap.get("A").release();
        threadA.join();
        threadB.join();
        threadC.join();
    }

    /**
     * 使用Semaphore实现
     * @param name        名称
     * @param nextName    下一个需要唤醒的线程名称
     */
    private void printSemaphore(String name, String nextName) {
        for (int i = 0; i < 10; i++) {
            try {
                semaphoreMap.get(name).acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println(Thread.currentThread().getName() + name);
            semaphoreMap.get(nextName).release();
        }
    }

    @Test
    public void testVolatile() throws InterruptedException {
        ThreadTests threadTests = new ThreadTests();
        Thread threadA = new Thread(() -> threadTests.printVolatile("A", 0));
        Thread threadB = new Thread(() -> threadTests.printVolatile("B", 1));
        Thread threadC = new Thread(() -> threadTests.printVolatile("C", 2));
        threadA.start();
        threadB.start();
        threadC.start();
        threadA.join();
        threadB.join();
        threadC.join();
    }

    /**
     * 使用Volatile实现
     * @param name        名称
     * @param targetState 目标状态值
     */
    private void printVolatile(String name, int targetState) {
        for (int i = 0; i < 10; ) {
            while (state % 3 != targetState) {
            }
            System.out.println(Thread.currentThread().getName() + name);
            i++;
            synchronized (this) {
                state = state + 1;
            }
        }
    }

    private final AtomicInteger atomicState = new AtomicInteger(0);

    @Test
    public void testAtomicInteger() throws InterruptedException {
        ThreadTests threadTests = new ThreadTests();
        Thread threadA = new Thread(() -> threadTests.printAtomicInteger("A", 0));
        Thread threadB = new Thread(() -> threadTests.printAtomicInteger("B", 1));
        Thread threadC = new Thread(() -> threadTests.printAtomicInteger("C", 2));
        threadA.start();
        threadB.start();
        threadC.start();
        threadA.join();
        threadB.join();
        threadC.join();
    }

    /**
     * 使用AtomicInteger实现
     * @param name        名称
     * @param targetState 目标状态值
     */
    private void printAtomicInteger(String name, int targetState) {
        for (int i = 0; i < 10; ) {
            while (atomicState.get() % 3 != targetState) {
            }
            System.out.println(Thread.currentThread().getName() + name);
            i++;
            atomicState.getAndIncrement();
        }
    }
}
