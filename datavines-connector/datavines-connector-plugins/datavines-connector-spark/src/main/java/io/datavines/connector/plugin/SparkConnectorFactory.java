/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.datavines.connector.plugin;

import io.datavines.connector.api.*;

public class SparkConnectorFactory extends AbstractJdbcConnectorFactory {

    @Override
    public Connector getConnector() {
        return null;
    }

    @Override
    public Dialect getDialect() {
        return new SparkDialect();
    }

    @Override
    public ParameterConverter getConnectorParameterConverter() {
        return null;
    }

    @Override
    public Executor getExecutor() {
        return null;
    }

    @Override
    public MetricScript getMetricScript() {
        return new SparkMetricScript();
    }

    @Override
    public Boolean showInFrontend() {
        return false;
    }
}
