/*
 * Copyright (c) 2004-2005 by OpenSymphony
 * All rights reserved.
 * 
 * Previously Copyright (c) 2001-2004 James House
 */
package org.quartz.core;

import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.Job;
import org.quartz.JobPersistenceException;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.spi.TriggerFiredBundle;

/**
 * <p>
 * The thread responsible for performing the work of firing <code>{@link Trigger}</code>
 * s that are registered with the <code>{@link QuartzScheduler}</code>.
 * </p>
 * 
 * @see QuartzScheduler
 * @see Job
 * @see Trigger
 * 
 * @author James House
 */
public class QuartzSchedulerThread extends Thread {
    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Data members.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */
    private QuartzScheduler qs;

    private QuartzSchedulerResources qsRsrcs;

    private Object pauseLock = new Object();

    private Object idleLock = new Object();

    private boolean signaled;

    private boolean paused;

    private boolean halted;

    private SchedulingContext ctxt = null;

    private Random random = new Random(System.currentTimeMillis());

    // When the scheduler finds there is no current trigger to fire, how long
    // it should wait until checking again...
    private static long DEFAULT_IDLE_WAIT_TIME = 30L * 1000L;

    private long idleWaitTime = DEFAULT_IDLE_WAIT_TIME;

    private int idleWaitVariablness = 7 * 1000;

    private long dbFailureRetryInterval = 15L * 1000L;

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Constructors.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * Construct a new <code>QuartzSchedulerThread</code> for the given
     * <code>QuartzScheduler</code> as a non-daemon <code>Thread</code>
     * with normal priority.
     * </p>
     */
    QuartzSchedulerThread(QuartzScheduler qs, QuartzSchedulerResources qsRsrcs,
            SchedulingContext ctxt) {
        this(qs, qsRsrcs, ctxt, false, Thread.NORM_PRIORITY);
    }

    /**
     * <p>
     * Construct a new <code>QuartzSchedulerThread</code> for the given
     * <code>QuartzScheduler</code> as a <code>Thread</code> with the given
     * attributes.
     * </p>
     */
    QuartzSchedulerThread(QuartzScheduler qs, QuartzSchedulerResources qsRsrcs,
            SchedulingContext ctxt, boolean setDaemon, int threadPrio) {
        super(qs.getSchedulerThreadGroup(), qsRsrcs.getThreadName());
        this.qs = qs;
        this.qsRsrcs = qsRsrcs;
        this.ctxt = ctxt;
        this.setDaemon(setDaemon);
        this.setPriority(threadPrio);

        // start the underlying thread, but put this object into the 'paused'
        // state
        // so processing doesn't start yet...
        paused = true;
        halted = false;
        this.start();
    }

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Interface.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    void setIdleWaitTime(long waitTime) {
        idleWaitTime = waitTime;
        idleWaitVariablness = (int) (waitTime * 0.2);
    }

    private long getDbFailureRetryInterval() {
        return dbFailureRetryInterval;
    }

    public void setDbFailureRetryInterval(long dbFailureRetryInterval) {
        this.dbFailureRetryInterval = dbFailureRetryInterval;
    }

    private long getRandomizedIdleWaitTime() {
        return idleWaitTime - random.nextInt(idleWaitVariablness);
    }

    /**
     * <p>
     * Signals the main processing loop to pause at the next possible point.
     * </p>
     */
    void togglePause(boolean pause) {
        synchronized (pauseLock) {
            paused = pause;

            if (paused) {
                signalSchedulingChange();
            } else {
                pauseLock.notify();
            }
        }
    }

    /**
     * <p>
     * Signals the main processing loop to pause at the next possible point.
     * </p>
     */
    void halt() {
        synchronized (pauseLock) {
            halted = true;

            if (paused) {
                pauseLock.notify();
            } else {
                signalSchedulingChange();
            }
        }
    }

    boolean isPaused() {
        return paused;
    }

    /**
     * <p>
     * Signals the main processing loop that a change in scheduling has been
     * made - in order to interrupt any sleeping that may be occuring while
     * waiting for the fire time to arrive.
     * </p>
     */
    void signalSchedulingChange() {
        signaled = true;
    }

    /**
     * <p>
     * The main processing loop of the <code>QuartzSchedulerThread</code>.
     * </p>
     */
    public void run() {
        boolean lastAcquireFailed = false;
        
        while (!halted) {
            // check if we're supposed to pause...
            synchronized (pauseLock) {
                while (paused && !halted) {
                    try {
                        // wait until togglePause(false) is called...
                        pauseLock.wait(100L);
                    } catch (InterruptedException ignore) {
                    }
                }

                if (halted) {
                    break;
                }
            }

            Trigger trigger = null;
            boolean idleWait = true;

            try {
                trigger = qsRsrcs.getJobStore().acquireNextTrigger(
                        ctxt);
                lastAcquireFailed = false;
            } catch (JobPersistenceException jpe) {
                if(!lastAcquireFailed)
                    qs.notifySchedulerListenersError(
                        "An error occured while scanning for the next trigger to fire.",
                        jpe);
                lastAcquireFailed = true;
            }
            catch (RuntimeException e) {
                if(!lastAcquireFailed)
                    getLog().error("quartzSchedulerThreadLoop: RuntimeException "
                            +e.getMessage(), e);
                lastAcquireFailed = true;
            }

            if (trigger != null) {
                long now = System.currentTimeMillis();

                long triggerTime = trigger.getNextFireTime().getTime();
                long timeUntilTrigger = triggerTime - now;
                long spinInterval = 10;

                if (timeUntilTrigger <= idleWaitTime) {
                    // this looping may seem a bit silly, but it's the
                    // current work-around
                    // for a dead-lock that can occur if the Thread.sleep()
                    // is replaced with
                    // a obj.wait() that gets notified when the signal is
                    // set...
                    // so to be able to detect the signal change without
                    // sleeping the entire
                    // timeUntilTrigger, we spin here... don't worry
                    // though, this spinning
                    // doesn't even register 0.2% cpu usage on a pentium 4.
                    int numPauses = (int) (timeUntilTrigger / spinInterval);
                    while (numPauses >= 0 && !signaled) {

                        try {
                            Thread.sleep(spinInterval);
                        } catch (InterruptedException ignore) {
                        }

                        now = System.currentTimeMillis();
                        timeUntilTrigger = triggerTime - now;
                        numPauses = (int) (timeUntilTrigger / spinInterval);
                    }
                    if (signaled) {
                        try {
                            qsRsrcs.getJobStore().releaseAcquiredTrigger(
                                    ctxt, trigger);
                        } catch (JobPersistenceException jpe) {
                            qs.notifySchedulerListenersError(
                                    "An error occured while releasing trigger '"
                                            + trigger.getFullName() + "'",
                                    jpe);
                            // db connection must have failed... keep
                            // retrying until it's up...
                            releaseTriggerRetryLoop(trigger);
                        } catch (RuntimeException e) {
                            getLog().error(
                                "releaseTriggerRetryLoop: RuntimeException "
                                +e.getMessage(), e);
                            // db connection must have failed... keep
                            // retrying until it's up...
                            releaseTriggerRetryLoop(trigger);
                        }
                        signaled = false;
                        continue;
                    }

                    // set trigger to 'executing'
                    TriggerFiredBundle bndle = null;

                    try {
                        bndle = qsRsrcs.getJobStore().triggerFired(ctxt,
                                trigger);
                    } catch (SchedulerException se) {
                        qs.notifySchedulerListenersError(
                                "An error occured while firing trigger '"
                                        + trigger.getFullName() + "'", se);
                    }

                    if (bndle == null) {
                        try {
                            qsRsrcs.getJobStore().releaseAcquiredTrigger(ctxt,
                                    trigger);
                        } catch (SchedulerException se) {
                            qs.notifySchedulerListenersError(
                                    "An error occured while releasing trigger '"
                                            + trigger.getFullName() + "'", se);
                            // db connection must have failed... keep retrying
                            // until it's up...
                            releaseTriggerRetryLoop(trigger);
                        }
                        continue;
                    }

                    JobRunShell shell = null;
                    try {
                        shell = qsRsrcs.getJobRunShellFactory()
                                .borrowJobRunShell();

                        shell.initialize(qs, bndle);
                    } catch (SchedulerException se) {
                        try {
                            qsRsrcs.getJobStore().releaseAcquiredTrigger(ctxt,
                                    trigger);
                        } catch (SchedulerException se2) {
                            qs.notifySchedulerListenersError(
                                    "An error occured while releasing trigger '"
                                            + trigger.getFullName() + "'", se2);
                            // db connection must have failed... keep retrying
                            // until it's up...
                            releaseTriggerRetryLoop(trigger);
                        }
                        continue;
                    }

                    qsRsrcs.getThreadPool().runInThread(shell);

                    idleWait = false;
                } else {
                    //put the trigger back into the queue so it may be
                    // executed again in future
                    try {
                        qsRsrcs.getJobStore().releaseAcquiredTrigger(ctxt,
                                trigger);
                    } catch (JobPersistenceException jpe) {
                        qs.notifySchedulerListenersError(
                                "An error occured while releasing trigger '"
                                        + trigger.getFullName() + "'", jpe);
                        // db connection must have failed... keep retrying
                        // until it's up...
                        releaseTriggerRetryLoop(trigger);
                    } catch (RuntimeException e) {
                        getLog().error(
                            "releaseTriggerRetryLoop: RuntimeException "
                            +e.getMessage(), e);
                        // db connection must have failed... keep retrying
                        // until it's up...
                        releaseTriggerRetryLoop(trigger);
                    }
                    idleWait = true;
                }
            }

            // this looping may seem a bit silly, but it's the current
            // work-around
            // for a dead-lock that can occur if the Thread.sleep() is replaced
            // with
            // a obj.wait() that gets notified when the signal is set...
            // so to be able to detect the signal change without sleeping the
            // entier
            // getRandomizedIdleWaitTime(), we spin here... don't worry though,
            // the
            // CPU usage of this spinning can't even be measured on a pentium
            // 4.
            long now = System.currentTimeMillis();
            long waitTime = now + getRandomizedIdleWaitTime();
            long timeUntilContinue = waitTime - now;
            long spinInterval = 10;
            int numPauses = (int) (timeUntilContinue / spinInterval);

            while (idleWait && numPauses > 0 && !signaled) {

                try {
                    Thread.sleep(10L);
                } catch (InterruptedException ignore) {
                }

                now = System.currentTimeMillis();
                timeUntilContinue = waitTime - now;
                numPauses = (int) (timeUntilContinue / spinInterval);
            }
            signaled = false;
        } // loop...

        // drop references to scheduler stuff to aid garbage collection...
        qs = null;
        qsRsrcs = null;
    }

    public void releaseTriggerRetryLoop(Trigger trigger) {
        int retryCount = 0;
        try {
            while (!halted) {
                try {
                    Thread.sleep(getDbFailureRetryInterval()); // retry every N
                    // seconds (the db
                    // connection must
                    // be failed)
                    retryCount++;
                    qsRsrcs.getJobStore().releaseAcquiredTrigger(ctxt, trigger);
                    retryCount = 0;
                    break;
                } catch (JobPersistenceException jpe) {
                    if(retryCount % 4 == 0)
                        qs.notifySchedulerListenersError(
                            "An error occured while releasing trigger '"
                                    + trigger.getFullName() + "'", jpe);
                } catch (RuntimeException e) {
                    getLog().error("releaseTriggerRetryLoop: RuntimeException "+e.getMessage(), e);
                } catch (InterruptedException e) {
                    getLog().error("releaseTriggerRetryLoop: InterruptedException "+e.getMessage(), e);
                }
            }
        } finally {
            if(retryCount == 0)
                getLog().info("releaseTriggerRetryLoop: connection restored.");
        }
    }

    public static Log getLog() {
        return LogFactory.getLog(QuartzSchedulerThread.class);
    }

} // end of QuartzSchedulerThread