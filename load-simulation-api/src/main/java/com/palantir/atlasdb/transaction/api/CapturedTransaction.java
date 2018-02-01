/*
 * Copyright 2018 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.transaction.api;

import java.util.List;

import org.immutables.value.Value;

import com.palantir.atlasdb.transaction.impl.AlwaysFailingReplayCondition;

@Value.Immutable
public interface CapturedTransaction extends ConditionAwareTransactionTask<Void, AlwaysFailingReplayCondition,
        DeliberatelyFailedNonRetriableException> {
    List<Invocation> invocations();
    long timestamp();

    static ImmutableCapturedTransaction.Builder builder() {
        return ImmutableCapturedTransaction.builder();
    }

    @Override
    default Void execute(Transaction transaction, AlwaysFailingReplayCondition condition) {
        invocations().forEach(invocation -> invocation.accept(transaction));
        return null;
    }
}
