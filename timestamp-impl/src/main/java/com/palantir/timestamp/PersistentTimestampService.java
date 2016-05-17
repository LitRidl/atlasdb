/**
 * Copyright 2015 Palantir Technologies
 * <p>
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://opensource.org/licenses/BSD-3-Clause
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.timestamp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.concurrent.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.palantir.common.concurrent.PTExecutors;
import com.palantir.common.remoting.ServiceNotAvailableException;
import com.palantir.common.time.Clock;
import com.palantir.common.time.SystemClock;
import com.palantir.exception.PalantirInterruptedException;

@ThreadSafe
public class PersistentTimestampService implements TimestampService {
    private static final Logger log = LoggerFactory.getLogger(PersistentTimestampService.class);

    private static final int MAX_REQUEST_RANGE_SIZE = 10 * 1000;
    static final long ALLOCATION_BUFFER_SIZE = 1000 * 1000;
    private static final int ONE_MINUTE_IN_MILLIS = 60000;

    private final TimestampBoundStore store;

    private final AtomicLong lastReturnedTimestamp;
    private final AtomicLong upperLimitToHandOutInclusive;

    private final ExecutorService executor;

    private Clock clock;
    private long lastAllocatedTime;
    volatile Throwable allocationFailure = null;

    public static PersistentTimestampService create(TimestampBoundStore tbs) {
        return create(tbs, new SystemClock());
    }

    @VisibleForTesting
    protected static PersistentTimestampService create(TimestampBoundStore tbs, Clock clock) {
        return new PersistentTimestampService(tbs, tbs.getUpperLimit(), clock);
    }

    private PersistentTimestampService(TimestampBoundStore tbs, long lastUpperBound, Clock clock) {
        store = tbs;
        lastReturnedTimestamp = new AtomicLong(lastUpperBound);
        upperLimitToHandOutInclusive = new AtomicLong(lastUpperBound);
        executor = PTExecutors.newSingleThreadExecutor(PTExecutors.newThreadFactory("Timestamp allocator", Thread.NORM_PRIORITY, true));
        this.clock = clock;
        lastAllocatedTime = clock.getTimeMillis();
    }

    @Override
    public long getFreshTimestamp() {
        long ret = getFreshTimestamps(1).getLowerBound();
        return ret;
    }

    public long getUpperLimitTimestampToHandOutInclusive() {
        return upperLimitToHandOutInclusive.get();
    }

    @Override
    public TimestampRange getFreshTimestamps(int numTimestampsRequested) {
        Preconditions.checkArgument(numTimestampsRequested > 0,
                "Number of timestamps requested must be greater than zero, was %s",
                numTimestampsRequested);

        if (numTimestampsRequested > MAX_REQUEST_RANGE_SIZE) {
            numTimestampsRequested = MAX_REQUEST_RANGE_SIZE;
        }
        boolean hasLogged = false;
        while (true) {
            long upperLimit = upperLimitToHandOutInclusive.get();
            long lastVal = lastReturnedTimestamp.get();
            if (lastVal >= upperLimit) {
                submitAllocationTask();
                Throwable possibleFailure = allocationFailure;
                if (possibleFailure instanceof MultipleRunningTimestampServiceError) {
                    throw new ServiceNotAvailableException("This server is no longer valid because another is running.", possibleFailure);
                } else if (possibleFailure != null) {
                    throw new RuntimeException("failed to allocate more timestamps", possibleFailure);
                }
                if (!hasLogged) {
                    log.error("We haven't gotten enough timestamps from the DB", new RuntimeException());
                    hasLogged = true;
                }
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                    throw new PalantirInterruptedException("Interrupted while waiting for timestamp allocation.");
                }
                continue;
            }
            long newVal = Math.min(upperLimit, lastVal + numTimestampsRequested);
            if (lastReturnedTimestamp.compareAndSet(lastVal, newVal)) {
                if (isAllocationRequired(newVal, upperLimit)) {
                    submitAllocationTask();
                }
                return TimestampRange.createInclusiveRange(lastVal + 1, newVal);
            }
        }
    }

    /**
     * Fast forwards the timestamp to the specified one so that no one can be served fresh timestamps prior
     * to it from now on.
     *
     * Sets the upper limit in the TimestampBoundStore as well as increases the minimum timestamp that can
     * be allocated from this instantiation of the TimestampService moving forward.
     *
     * The caller of this is responsible for not using any of the fresh timestamps previously served to it,
     * and must call getFreshTimestamps() to ensure it is using timestamps after the fastforward point.
     *
     * @param timestamp
     */
    public synchronized void fastForwardTimestamp(long timestamp) {
        long upperLimit = timestamp + ALLOCATION_BUFFER_SIZE;
        store.storeUpperLimit(upperLimit);
        // Prevent upper limit from falling behind stored upper limit.
        setToAtLeast(upperLimitToHandOutInclusive, upperLimit);

        // Prevent ourselves from serving any of the bad (read: pre-fastForward) timestamps
        setToAtLeast(lastReturnedTimestamp, timestamp);
    }

    private synchronized void allocateMoreTimestamps() {
        if (shouldNotAllocateMoreTimestamps()) {
            return;
        }

        long newLimit = lastReturnedTimestamp.get() + ALLOCATION_BUFFER_SIZE;
        store.storeUpperLimit(newLimit);
        // Prevent upper limit from falling behind stored upper limit.
        setToAtLeast(upperLimitToHandOutInclusive, newLimit);
        lastAllocatedTime = clock.getTimeMillis();
        allocationFailure = null;
    }

    private boolean shouldNotAllocateMoreTimestamps() {
        return !isAllocationRequired(lastReturnedTimestamp.get(), upperLimitToHandOutInclusive.get())
            || allocationFailure instanceof MultipleRunningTimestampServiceError;
    }

    private static void setToAtLeast(AtomicLong toAdvance, long val) {
        while (true) {
            long oldUpper = toAdvance.get();
            if (val <= oldUpper || toAdvance.compareAndSet(oldUpper, val)) {
                return;
            }
        }
    }

    private void submitAllocationTask() {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    allocateMoreTimestamps();
                } catch (Throwable e) { // (authorized)
                    handleAllocationException(e);
                }
            }
        });
    }

    private void handleAllocationException(Throwable e) {
        if (allocationFailure != null
                && e.getClass().equals(allocationFailure.getClass())) {
            // QA-75825: don't keep logging error if we keep failing to allocate.
            log.info("Throwable while allocating timestamps.", e);
        } else {
            log.error("Throwable while allocating timestamps.", e);
        }
        allocationFailure = e;
    }

    private boolean isAllocationRequired(long lastVal, long upperLimit) {
        return exceededUpperLimit(lastVal, upperLimit)
                || exceededHalfOfBuffer(lastVal, upperLimit)
                || haveNotAllocatedForOneMinute();
    }

    private boolean exceededHalfOfBuffer(long lastVal, long upperLimit) {
        return (upperLimit - lastVal) <= ALLOCATION_BUFFER_SIZE / 2;
    }

    private boolean exceededUpperLimit(long lastVal, long upperLimit) {
        return lastVal >= upperLimit;
    }

    private boolean haveNotAllocatedForOneMinute() {
        return lastAllocatedTime + ONE_MINUTE_IN_MILLIS < clock.getTimeMillis();
    }
}
