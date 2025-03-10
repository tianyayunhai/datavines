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
package io.datavines.engine.config;

import io.datavines.common.config.*;
import io.datavines.common.config.enums.SourceType;
import io.datavines.common.config.enums.TransformType;
import io.datavines.common.entity.*;
import io.datavines.common.entity.job.BaseJobParameter;
import io.datavines.common.exception.DataVinesException;
import io.datavines.common.utils.*;
import io.datavines.connector.api.ConnectorFactory;
import io.datavines.metric.api.ExpectedValue;
import io.datavines.metric.api.SqlMetric;
import io.datavines.spi.PluginLoader;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.datavines.common.CommonConstants.*;
import static io.datavines.common.ConfigConstants.*;
import static io.datavines.common.ConfigConstants.TABLE;
import static io.datavines.engine.config.MetricParserUtils.generateUniqueCode;

public abstract class BaseJobConfigurationBuilder implements JobConfigurationBuilder {

    protected final DataVinesJobConfig configuration = new DataVinesJobConfig();

    protected Map<String, String> inputParameter;

    protected JobExecutionParameter jobExecutionParameter;

    protected JobExecutionInfo jobExecutionInfo;

    protected Map<String, Map<String, String>> metric2InputParameter = new HashMap<>();

    @Override
    public void init(Map<String, String> inputParameter, JobExecutionInfo jobExecutionInfo) {
        this.inputParameter = inputParameter;
        LocalDate nowDate = LocalDate.now();
        this.inputParameter.put(WEEK_START_DAY, DateUtils.format(DateUtils.getWeekStart(nowDate), DateUtils.YYYY_MM_DD));
        this.inputParameter.put(WEEK_END_DAY, DateUtils.format(DateUtils.getWeekEnd(nowDate), DateUtils.YYYY_MM_DD));
        this.inputParameter.put(MONTH_START_DAY, DateUtils.format(DateUtils.getMonthStart(nowDate), DateUtils.YYYY_MM_DD));
        this.inputParameter.put(MONTH_END_DAY, DateUtils.format(DateUtils.getMonthEnd(nowDate), DateUtils.YYYY_MM_DD));
        this.inputParameter.put(DAY_START_TIME, DateUtils.format(DateUtils.getStartOfDay(nowDate), DateUtils.YYYY_MM_DD_HH_MM_SS));
        this.inputParameter.put(DAY_END_TIME, DateUtils.format(DateUtils.getEndOfDay(nowDate), DateUtils.YYYY_MM_DD_HH_MM_SS));
        this.inputParameter.put(DAY_AFTER_7_END_TIME, DateUtils.format(DateUtils.getEndOfDayAfterNDays(nowDate,7), DateUtils.YYYY_MM_DD_HH_MM_SS));
        this.inputParameter.put(DAY_AFTER_30_END_TIME, DateUtils.format(DateUtils.getEndOfDayAfterNDays(nowDate,30), DateUtils.YYYY_MM_DD_HH_MM_SS));
        this.inputParameter.put(COLUMN, "");
        this.jobExecutionInfo = jobExecutionInfo;
        this.jobExecutionParameter = jobExecutionInfo.getJobExecutionParameter();

        this.inputParameter.put(ERROR_DATA_FILE_NAME, jobExecutionInfo.getErrorDataFileName());

        if (FILE.equalsIgnoreCase(jobExecutionInfo.getErrorDataStorageType())) {
            Map<String,String> errorDataParameterMap = JSONUtils.toMap(jobExecutionInfo.getErrorDataStorageParameter(),String.class, String.class);
            this.inputParameter.put(ERROR_DATA_DIR, errorDataParameterMap.get(DATA_DIR));
            this.inputParameter.put(COLUMN_SEPARATOR,
                    errorDataParameterMap.get(CommonPropertyUtils.COLUMN_SEPARATOR) == null ?
                            CommonPropertyUtils.COLUMN_SEPARATOR_DEFAULT : errorDataParameterMap.get(CommonPropertyUtils.COLUMN_SEPARATOR));
            this.inputParameter.put(LINE_SEPARATOR,
                    errorDataParameterMap.get(CommonPropertyUtils.LINE_SEPARATOR) == null ?
                            CommonPropertyUtils.LINE_SEPARATOR_DEFAULT : errorDataParameterMap.get(CommonPropertyUtils.LINE_SEPARATOR));
        } else {
            this.inputParameter.put(ERROR_DATA_DIR, CommonPropertyUtils.getString(CommonPropertyUtils.ERROR_DATA_DIR, CommonPropertyUtils.ERROR_DATA_DIR_DEFAULT));
            this.inputParameter.put(COLUMN_SEPARATOR, CommonPropertyUtils.getString(CommonPropertyUtils.COLUMN_SEPARATOR, CommonPropertyUtils.COLUMN_SEPARATOR_DEFAULT));
            this.inputParameter.put(LINE_SEPARATOR, CommonPropertyUtils.getString(CommonPropertyUtils.LINE_SEPARATOR, CommonPropertyUtils.LINE_SEPARATOR_DEFAULT));
        }

        if (FILE.equalsIgnoreCase(jobExecutionInfo.getValidateResultDataStorageType())) {
            Map<String,String> validateResultDataParameterMap = JSONUtils.toMap(jobExecutionInfo.getValidateResultDataStorageParameter(),String.class, String.class);
            this.inputParameter.put(VALIDATE_RESULT_DATA_DIR, validateResultDataParameterMap.get(DATA_DIR));
        } else {
            this.inputParameter.put(VALIDATE_RESULT_DATA_DIR, CommonPropertyUtils.getString(CommonPropertyUtils.VALIDATE_RESULT_DATA_DIR, CommonPropertyUtils.VALIDATE_RESULT_DATA_DIR_DEFAULT));
        }

        List<BaseJobParameter> metricJobParameterList = jobExecutionParameter.getMetricParameterList();
        if (CollectionUtils.isNotEmpty(metricJobParameterList)) {
            for (BaseJobParameter parameter : metricJobParameterList) {
                String metricUniqueKey = getMetricUniqueKey(parameter);
                Map<String, String> metricInputParameter = new HashMap<>(this.inputParameter);
                metricInputParameter.put(METRIC_UNIQUE_KEY, metricUniqueKey);
                metricInputParameter.put(String.format("%s_%s", EXPECTED_VALUE, metricUniqueKey), null);
                if (parameter.getMetricParameter() != null) {
                    parameter.getMetricParameter().forEach((k, v) -> {
                        metricInputParameter.put(k, String.valueOf(v));
                    });
                }

                if (parameter.getExpectedParameter() != null) {
                    parameter.getExpectedParameter().forEach((k, v) -> {
                        if (EXPECTED_VALUE.equals(k) &&  v != null) {
                            metricInputParameter.put(String.format("%s_%s", EXPECTED_VALUE, metricUniqueKey), String.valueOf(v));
                            metricInputParameter.remove(EXPECTED_VALUE);
                        }
                        metricInputParameter.put(k, String.valueOf(v));
                    });
                }

                metricInputParameter.put(RESULT_FORMULA, String.valueOf(parameter.getResultFormula()));
                metricInputParameter.put(OPERATOR, String.valueOf(parameter.getOperator()));
                metricInputParameter.put(THRESHOLD, String.valueOf(parameter.getThreshold()));
                metricInputParameter.put(EXPECTED_TYPE, StringUtils.wrapperSingleQuotes(parameter.getExpectedType()));
                metricInputParameter.put(INVALIDATE_ITEM_CAN_OUTPUT, String.valueOf(true));
                metricInputParameter.put(ENGINE_TYPE, jobExecutionInfo.getEngineType());
                metric2InputParameter.put(metricUniqueKey, metricInputParameter);
            }
        }
    }

    @Override
    public void buildName() {
        configuration.setName(jobExecutionInfo.getName());
    }

    @Override
    public void buildEnvConfig() {
        configuration.setEnvConfig(getEnvConfig());
    }

    @Override
    public void buildSourceConfigs() throws DataVinesException {
        configuration.setSourceParameters(getSourceConfigs());
    }

    @Override
    public void buildTransformConfigs() {
        List<TransformConfig> transformConfigs = new ArrayList<>();
        List<BaseJobParameter> metricJobParameterList = jobExecutionParameter.getMetricParameterList();
        if (CollectionUtils.isNotEmpty(metricJobParameterList)) {
            for (BaseJobParameter parameter : metricJobParameterList) {
                String metricUniqueKey = getMetricUniqueKey(parameter);
                Map<String, String> metricInputParameter = metric2InputParameter.get(metricUniqueKey);

                String metricType = parameter.getMetricType();
                SqlMetric sqlMetric = PluginLoader
                        .getPluginLoader(SqlMetric.class)
                        .getNewPlugin(metricType);
                boolean invalidateItemCanOutput = Boolean.parseBoolean(metricInputParameter.get(INVALIDATE_ITEM_CAN_OUTPUT));
                MetricParserUtils.operateInputParameter(metricInputParameter, sqlMetric, jobExecutionInfo);
                invalidateItemCanOutput &= sqlMetric.isInvalidateItemsCanOutput();
                metricInputParameter.put(INVALIDATE_ITEM_CAN_OUTPUT, String.valueOf(invalidateItemCanOutput));

                // generate invalidate item execute sql
                if (sqlMetric.getInvalidateItems(metricInputParameter) != null) {
                    ExecuteSql invalidateItemExecuteSql = sqlMetric.getInvalidateItems(metricInputParameter);
                    metricInputParameter.put(INVALIDATE_ITEMS_TABLE, invalidateItemExecuteSql.getResultTable());
                    invalidateItemExecuteSql.setResultTable(invalidateItemExecuteSql.getResultTable());
                    MetricParserUtils.setTransformerConfig(
                            metricInputParameter,
                            transformConfigs,
                            invalidateItemExecuteSql,
                            TransformType.INVALIDATE_ITEMS.getDescription());
                }

                // generate actual value execute sql
                ExecuteSql actualValueExecuteSql = sqlMetric.getActualValue(metricInputParameter);
                if (actualValueExecuteSql != null) {
                    actualValueExecuteSql.setResultTable(sqlMetric.getActualValue(metricInputParameter).getResultTable());
                    MetricParserUtils.setTransformerConfig(
                            metricInputParameter,
                            transformConfigs,
                            actualValueExecuteSql,
                            TransformType.ACTUAL_VALUE.getDescription());
                    metricInputParameter.put(ACTUAL_TABLE, sqlMetric.getActualValue(metricInputParameter).getResultTable());
                }

                // generate expected value transform sql
                String expectedType = jobExecutionInfo.getEngineType() + "_" + parameter.getExpectedType();
                ExpectedValue expectedValue = PluginLoader
                        .getPluginLoader(ExpectedValue.class)
                        .getNewPlugin(expectedType);

                ExecuteSql expectedValueExecuteSql =
                        new ExecuteSql(expectedValue.getExecuteSql(metricInputParameter), expectedValue.getOutputTable(metricInputParameter));

                if (StringUtils.isNotEmpty(expectedValueExecuteSql.getResultTable())) {
                    metricInputParameter.put(EXPECTED_TABLE, expectedValueExecuteSql.getResultTable());
                }

                metricInputParameter.put(UNIQUE_CODE, StringUtils.wrapperSingleQuotes(generateUniqueCode(metricInputParameter)));

                if (expectedValue.isNeedDefaultDatasource()) {
                    MetricParserUtils.setTransformerConfig(metricInputParameter, transformConfigs,
                            expectedValueExecuteSql, TransformType.EXPECTED_VALUE_FROM_METADATA_SOURCE.getDescription());
                } else {
                    MetricParserUtils.setTransformerConfig(metricInputParameter, transformConfigs,
                            expectedValueExecuteSql, TransformType.EXPECTED_VALUE_FROM_SOURCE.getDescription());
                }
                metric2InputParameter.put(metricUniqueKey, metricInputParameter);
            }

            configuration.setTransformParameters(transformConfigs);
        }
    }

    @Override
    public DataVinesJobConfig build() {
        return configuration;
    }

    protected abstract EnvConfig getEnvConfig();

    protected abstract List<SourceConfig> getSourceConfigs() throws DataVinesException;

    protected SourceConfig getValidateResultDataSourceConfig() throws DataVinesException {

        SourceConfig actualValueSourceConfig = new SourceConfig();
        ConnectorFactory storageFactory =
                PluginLoader.getPluginLoader(ConnectorFactory.class)
                        .getOrCreatePlugin(jobExecutionInfo.getValidateResultDataStorageType());

        actualValueSourceConfig.setPlugin(storageFactory.getCategory());
        actualValueSourceConfig.setType(SourceType.METADATA.getDescription());
        actualValueSourceConfig.setConfig(getValidateResultSourceConfigMap(null,"dv_actual_values"));
        return actualValueSourceConfig;
    }

    protected SourceConfig getValidateResultDataSourceConfig(String outputTable) throws DataVinesException {

        SourceConfig actualValueSourceConfig = new SourceConfig();
        ConnectorFactory storageFactory =
                PluginLoader.getPluginLoader(ConnectorFactory.class)
                        .getOrCreatePlugin(jobExecutionInfo.getValidateResultDataStorageType());

        actualValueSourceConfig.setPlugin(storageFactory.getCategory());
        actualValueSourceConfig.setType(SourceType.METADATA.getDescription());
        actualValueSourceConfig.setConfig(getValidateResultSourceConfigMap(null,"dv_actual_values", outputTable));
        return actualValueSourceConfig;
    }

    protected SinkConfig getValidateResultDataSinkConfig(ExpectedValue expectedValue, String sql, String dbTable, Map<String, String> inputParameter) throws DataVinesException {

        SinkConfig validateResultDataStorageConfig = new SinkConfig();
        validateResultDataStorageConfig.setPlugin(jobExecutionInfo.getValidateResultDataStorageType());
        Map<String, Object> configMap = getValidateResultSourceConfigMap(
                ParameterUtils.convertParameterPlaceholders(sql, inputParameter),dbTable);
        configMap.put(JOB_EXECUTION_ID, jobExecutionInfo.getId());
        configMap.put(INVALIDATE_ITEMS_TABLE, inputParameter.get(INVALIDATE_ITEMS_TABLE));
        configMap.put(METRIC_UNIQUE_KEY, inputParameter.get(METRIC_UNIQUE_KEY));
        if (expectedValue != null && StringUtils.isNotEmpty(expectedValue.getKey(inputParameter))) {
            inputParameter.put(EXPECTED_VALUE, expectedValue.getKey(inputParameter));
            configMap.put(EXPECTED_VALUE, expectedValue.getKey(inputParameter));
        }

        validateResultDataStorageConfig.setConfig(configMap);

        return validateResultDataStorageConfig;
    }

    private Map<String,Object> getValidateResultSourceConfigMap(String sql, String dbTable) {
        Map<String, Object> configMap = new HashMap<>();
        ConnectorFactory storageFactory =
                PluginLoader.getPluginLoader(ConnectorFactory.class)
                        .getOrCreatePlugin(jobExecutionInfo.getValidateResultDataStorageType());
        if (storageFactory != null) {
            if (StringUtils.isNotEmpty(jobExecutionInfo.getValidateResultDataStorageParameter())) {
                configMap = storageFactory.getConnectorParameterConverter().converter(JSONUtils.toMap(jobExecutionInfo.getValidateResultDataStorageParameter(), String.class, Object.class));
                configMap.put(DRIVER, storageFactory.getDialect().getDriver());
            }
        }

        configMap.put(TABLE, dbTable);
        configMap.put(OUTPUT_TABLE, dbTable);
        if (StringUtils.isNotEmpty(sql)) {
            configMap.put(SQL, sql);
        }

        return configMap;
    }

    private Map<String,Object> getValidateResultSourceConfigMap(String sql, String dbTable,String outputTable) {
        Map<String, Object> configMap = new HashMap<>();
        ConnectorFactory storageFactory =
                PluginLoader.getPluginLoader(ConnectorFactory.class)
                        .getOrCreatePlugin(jobExecutionInfo.getValidateResultDataStorageType());
        if (storageFactory != null) {
            if (StringUtils.isNotEmpty(jobExecutionInfo.getValidateResultDataStorageParameter())) {
                configMap = storageFactory.getConnectorParameterConverter().converter(JSONUtils.toMap(jobExecutionInfo.getValidateResultDataStorageParameter(), String.class, Object.class));
                configMap.put(DRIVER, storageFactory.getDialect().getDriver());
            }
        }

        configMap.put(TABLE, dbTable);
        configMap.put(OUTPUT_TABLE, outputTable);
        if (StringUtils.isNotEmpty(sql)) {
            configMap.put(SQL, sql);
        }

        return configMap;
    }

    protected String getMetricUniqueKey(BaseJobParameter parameter) {
        return DigestUtils.md5Hex(String.format("%s_%s_%s_%s_%s",
                parameter.getMetricType(),
                parameter.getMetricParameter().get(DATABASE),
                parameter.getMetricParameter().get(TABLE),
                parameter.getMetricParameter().get(COLUMN),
                jobExecutionInfo.getId())).substring(0,8);
    }
}
