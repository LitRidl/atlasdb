/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
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

package com.palantir.atlasdb.timelock.benchmarks.benchmarks;

import java.util.Map;

import com.google.common.collect.ImmutableMap;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.timelock.benchmarks.RandomBytes;
import com.palantir.atlasdb.timelock.benchmarks.schema.BenchmarksSchema;
import com.palantir.atlasdb.transaction.impl.SerializableTransactionManager;

public final class KvsPutUnlessExistsBenchmark extends AbstractBenchmark {

    private final KeyValueService keyValueService;

    public static Map<String, Object> execute(SerializableTransactionManager txnManager, int numClients,
            int requestsPerClient) {
        return new KvsPutUnlessExistsBenchmark(txnManager.getKeyValueService(), numClients, requestsPerClient)
                .execute();
    }

    private KvsPutUnlessExistsBenchmark(KeyValueService keyValueService, int numClients, int numRequestsPerClient) {
        super(numClients, numRequestsPerClient);
        this.keyValueService = keyValueService;
    }

    @Override
    protected void performOneCall() {
        byte[] data = RandomBytes.ofLength(16);
        keyValueService.putUnlessExists(
                BenchmarksSchema.BLOBS_TABLE_REF,
                ImmutableMap.of(Cell.create(data, data), data));
    }
}
