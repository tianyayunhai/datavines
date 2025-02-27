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

package io.datavines.server.repository.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.datavines.common.config.DataVinesJobConfig;
import io.datavines.common.datasource.jdbc.entity.ColumnInfo;
import io.datavines.common.entity.ConnectionInfo;
import io.datavines.common.entity.JobExecutionInfo;
import io.datavines.common.entity.JobExecutionParameter;
import io.datavines.common.entity.job.BaseJobParameter;
import io.datavines.common.entity.job.NotificationParameter;
import io.datavines.common.entity.job.SubmitJob;
import io.datavines.common.enums.DataVinesDataType;
import io.datavines.common.enums.ExecutionStatus;
import io.datavines.common.enums.JobType;
import io.datavines.common.exception.DataVinesException;
import io.datavines.common.utils.JSONUtils;
import io.datavines.common.utils.PasswordFilterUtils;
import io.datavines.common.utils.StringUtils;
import io.datavines.connector.api.ConnectorFactory;
import io.datavines.connector.api.utils.SqlUtils;
import io.datavines.core.enums.Status;
import io.datavines.core.exception.DataVinesServerException;
import io.datavines.core.utils.LanguageUtils;
import io.datavines.engine.config.DataVinesConfigurationManager;
import io.datavines.metric.api.MetricType;
import io.datavines.metric.api.ResultFormula;
import io.datavines.metric.api.SqlMetric;
import io.datavines.server.api.dto.bo.job.DataProfileJobCreateOrUpdate;
import io.datavines.server.api.dto.bo.job.JobCreate;
import io.datavines.server.api.dto.bo.job.JobUpdate;
import io.datavines.server.api.dto.vo.JobExecutionStat;
import io.datavines.server.api.dto.vo.JobVO;
import io.datavines.server.api.dto.vo.SlaConfigVO;
import io.datavines.server.api.dto.vo.SlaVO;
import io.datavines.server.dqc.coordinator.builder.JobExecutionParameterBuilderFactory;
import io.datavines.server.enums.CommandType;
import io.datavines.server.enums.Priority;
import io.datavines.server.repository.entity.*;
import io.datavines.server.repository.entity.catalog.CatalogEntityInstance;
import io.datavines.server.repository.entity.catalog.CatalogEntityMetricJobRel;
import io.datavines.server.repository.mapper.JobMapper;
import io.datavines.server.repository.service.*;
import io.datavines.server.utils.ContextHolder;
import io.datavines.server.utils.DefaultDataSourceInfoUtils;
import io.datavines.server.utils.JobParameterUtils;
import io.datavines.spi.PluginLoader;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.datavines.common.CommonConstants.LOCAL;
import static io.datavines.common.CommonConstants.TABLE;
import static io.datavines.common.ConfigConstants.*;
import static io.datavines.common.log.SensitiveDataConverter.PWD_PATTERN_1;
import static io.datavines.server.utils.DefaultDataSourceInfoUtils.getDefaultConnectionInfo;

@Slf4j
@Service("jobService")
public class JobServiceImpl extends ServiceImpl<JobMapper, Job> implements JobService {

    @Autowired
    private JobExecutionService jobExecutionService;

    @Autowired
    private CommandService commandService;

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private EnvService envService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ErrorDataStorageService errorDataStorageService;

    @Autowired
    private SlaService slaService;

    @Autowired
    private SlaJobService slaJobService;

    @Autowired
    private CatalogEntityMetricJobRelService catalogEntityMetricJobRelService;

    @Autowired
    private IssueService issueService;

    @Autowired
    private CatalogEntityInstanceService catalogEntityInstanceService;

    @Autowired
    private JobScheduleService jobScheduleService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long create(JobCreate jobCreate) throws DataVinesServerException {

        String parameter = jobCreate.getParameter();
        if (StringUtils.isEmpty(parameter)) {
            throw new DataVinesServerException(Status.JOB_PARAMETER_IS_NULL_ERROR);
        }

        if (jobCreate.getIsErrorDataOutputToDataSource()!= null && jobCreate.getIsErrorDataOutputToDataSource()) {
            DataSource dataSource = dataSourceService.getDataSourceById(jobCreate.getDataSourceId());
            if (dataSource != null) {
                String errorDataStorageType = dataSource.getType();
                ConnectorFactory connectorFactory = PluginLoader.getPluginLoader(ConnectorFactory.class).getOrCreatePlugin(errorDataStorageType);
                if (connectorFactory == null || !connectorFactory.getDialect().invalidateItemCanOutputToSelf()) {
                    throw new DataVinesServerException(Status.DATASOURCE_NOT_SUPPORT_ERROR_DATA_OUTPUT_TO_SELF_ERROR, errorDataStorageType);
                }
            }
        }

        Job job = new Job();
        BeanUtils.copyProperties(jobCreate, job);
        List<BaseJobParameter> jobParameters = JSONUtils.toList(parameter, BaseJobParameter.class);
        jobParameters = JobParameterUtils.regenerateJobParameterList(jobParameters);
        checkDuplicateMetricInJob(jobParameters);
        isMetricSuitable(jobCreate.getDataSourceId(), jobCreate.getDataSourceId2(), jobCreate.getEngineType(), jobParameters);
        List<String> fqnList = setJobAttribute(job, jobParameters);

        if (StringUtils.isEmpty(jobCreate.getJobName())) {
            job.setName(getJobName(jobCreate.getType(), jobCreate.getParameter()));
        } else {
            job.setName(jobCreate.getJobName());
        }

        if (getByKeyAttribute(job)) {
            throw new DataVinesServerException(Status.JOB_EXIST_ERROR, job.getName());
        }

        job.setType(JobType.of(jobCreate.getType()));
        job.setCreateBy(ContextHolder.getUserId());
        job.setCreateTime(LocalDateTime.now());
        job.setUpdateBy(ContextHolder.getUserId());
        job.setUpdateTime(LocalDateTime.now());

        if (!save(job)) {
            log.info("create metric jov error : {}", jobCreate);
            throw new DataVinesServerException(Status.CREATE_JOB_ERROR, job.getName());
        } else {
            saveOrUpdateMetricJobEntityRel(job, fqnList);
        }

        long jobId = job.getId();

        // whether running now
        if (jobCreate.getRunningNow() == 1) {
            executeJob(job, null);
        }

        return jobId;
    }

    private boolean getByKeyAttribute(Job job) {
        List<Job> list = baseMapper.selectList(new QueryWrapper<Job>().lambda()
                .eq(Job::getName,job.getName())
                .eq(Job::getSchemaName,job.getSchemaName())
                .eq(Job::getTableName,job.getTableName())
                .eq(Job::getDataSourceId,job.getDataSourceId())
                .eq(job.getDataSourceId2() != 0, Job::getDataSourceId2, job.getDataSourceId2())
                .eq(StringUtils.isNotEmpty(job.getColumnName()), Job::getColumnName,job.getColumnName())
        );
        return CollectionUtils.isNotEmpty(list);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long update(JobUpdate jobUpdate) {
        Job job = getById(jobUpdate.getId());
        if (job == null) {
            throw new DataVinesServerException(Status.JOB_NOT_EXIST_ERROR, jobUpdate.getId());
        }
        String originJobName = job.getName();
        if (jobUpdate.getIsErrorDataOutputToDataSource()!= null && jobUpdate.getIsErrorDataOutputToDataSource()) {
            DataSource dataSource = dataSourceService.getDataSourceById(job.getDataSourceId());
            if (dataSource != null) {
                String errorDataStorageType = dataSource.getType();
                ConnectorFactory connectorFactory = PluginLoader.getPluginLoader(ConnectorFactory.class).getOrCreatePlugin(errorDataStorageType);
                if (connectorFactory == null || !connectorFactory.getDialect().invalidateItemCanOutputToSelf()) {
                    throw new DataVinesServerException(Status.DATASOURCE_NOT_SUPPORT_ERROR_DATA_OUTPUT_TO_SELF_ERROR, errorDataStorageType);
                }
            }
        }

        BeanUtils.copyProperties(jobUpdate, job);
        List<BaseJobParameter> jobParameters = JSONUtils.toList(jobUpdate.getParameter(), BaseJobParameter.class);
        checkDuplicateMetricInJob(jobParameters);
        isMetricSuitable(jobUpdate.getDataSourceId(), jobUpdate.getDataSourceId2(), jobUpdate.getEngineType(), jobParameters);
        List<String> fqnList = setJobAttribute(job, jobParameters);
        if (StringUtils.isEmpty(jobUpdate.getJobName())) {
            job.setName(getJobName(jobUpdate.getType(), jobUpdate.getParameter()));
        } else {
            job.setName(jobUpdate.getJobName());
        }
        // add check if the name has changed
        if (getByKeyAttribute(job) && !org.apache.commons.lang3.StringUtils.equals(originJobName, job.getName())) {
            throw new DataVinesServerException(Status.JOB_EXIST_ERROR, job.getName());
        }
        job.setUpdateBy(ContextHolder.getUserId());
        job.setUpdateTime(LocalDateTime.now());

        if (!updateById(job)) {
            log.info("update metric job  error : {}", jobUpdate);
            throw new DataVinesServerException(Status.UPDATE_JOB_ERROR, job.getName());
        } else {
            saveOrUpdateMetricJobEntityRel(job, fqnList);
        }

        if (jobUpdate.getRunningNow() == 1) {
            executeJob(job, null);
        }

        return job.getId();
    }

    private void saveOrUpdateMetricJobEntityRel(Job job, List<String> fqnList) {
        List<CatalogEntityMetricJobRel>  listRel = catalogEntityMetricJobRelService.list(new QueryWrapper<CatalogEntityMetricJobRel>().lambda()
                .eq(CatalogEntityMetricJobRel::getMetricJobId, job.getId())
                .eq(CatalogEntityMetricJobRel::getMetricJobType, JobType.DATA_QUALITY.getDescription()));
        if (!listRel.isEmpty()) {
            catalogEntityMetricJobRelService.remove(new QueryWrapper<CatalogEntityMetricJobRel>().lambda()
                    .eq(CatalogEntityMetricJobRel::getMetricJobId, job.getId())
                    .eq(CatalogEntityMetricJobRel::getMetricJobType, JobType.DATA_QUALITY.getDescription()));
        }

        if (CollectionUtils.isNotEmpty(fqnList)) {
            for (String fqn : fqnList) {
                CatalogEntityInstance instance =
                        catalogEntityInstanceService.getByDataSourceAndFQN(job.getDataSourceId(), fqn);
                if (instance == null) {
                    continue;
                }

                CatalogEntityMetricJobRel entityMetricJobRel = new CatalogEntityMetricJobRel();
                entityMetricJobRel.setEntityUuid(instance.getUuid());
                entityMetricJobRel.setMetricJobId(job.getId());
                entityMetricJobRel.setMetricJobType(JobType.DATA_QUALITY.getDescription());
                entityMetricJobRel.setCreateBy(ContextHolder.getUserId());
                entityMetricJobRel.setCreateTime(LocalDateTime.now());
                entityMetricJobRel.setUpdateBy(ContextHolder.getUserId());
                entityMetricJobRel.setUpdateTime(LocalDateTime.now());
                catalogEntityMetricJobRelService.save(entityMetricJobRel);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long createOrUpdateDataProfileJob(DataProfileJobCreateOrUpdate dataProfileJobCreateOrUpdate) throws DataVinesServerException {
        // 需要对参数进行校验，判断插件类型是否存在
        String parameter = dataProfileJobCreateOrUpdate.getParameter();
        if (StringUtils.isEmpty(parameter)) {
            throw new DataVinesServerException(Status.JOB_PARAMETER_IS_NULL_ERROR);
        }

        Job job = new Job();
        BeanUtils.copyProperties(dataProfileJobCreateOrUpdate, job);
        job.setName(String.format("%s(%s.%s)", JobType.DATA_PROFILE.getDescription(), job.getSchemaName(), job.getTableName()));

        List<Job> list = baseMapper.selectList(new QueryWrapper<Job>().lambda()
                .eq(Job::getName,job.getName())
                .eq(Job::getDataSourceId,job.getDataSourceId())
        );

        if (CollectionUtils.isNotEmpty(list)) {
            job.setId(list.get(0).getId());
            job.setUpdateBy(ContextHolder.getUserId());
            job.setUpdateTime(LocalDateTime.now());
            job.setType(JobType.DATA_PROFILE);

            if (baseMapper.updateById(job) <= 0) {
                log.info("update data profile job fail : {}", dataProfileJobCreateOrUpdate);
                throw new DataVinesServerException(Status.UPDATE_JOB_ERROR, job.getName());
            }
        } else {
            job.setType(JobType.of(dataProfileJobCreateOrUpdate.getType()));
            job.setCreateBy(ContextHolder.getUserId());
            job.setCreateTime(LocalDateTime.now());
            job.setUpdateBy(ContextHolder.getUserId());
            job.setUpdateTime(LocalDateTime.now());
            if (baseMapper.insert(job) <= 0) {
                log.info("create data profile job fail : {}", dataProfileJobCreateOrUpdate);
                throw new DataVinesServerException(Status.CREATE_JOB_ERROR, job.getName());
            }
        }
        // whether running now
        if (dataProfileJobCreateOrUpdate.getRunningNow() == 1) {
            executeJob(job, null);
        }

        return job.getId();
    }

    private void isMetricSuitable(long datasourceId, long datasourceId2, String engine, List<BaseJobParameter> jobParameters) {
        if (CollectionUtils.isEmpty(jobParameters)) {
            return;
        }

        for (BaseJobParameter jobParameter : jobParameters) {

            if (datasourceId2 !=0 && LOCAL.equalsIgnoreCase(engine) ) {
                if (MetricType.MULTI_TABLE_ACCURACY.getDescription().equalsIgnoreCase(jobParameter.getMetricType()) && datasourceId != datasourceId2)  {
                    throw new DataVinesServerException(Status.MULTI_TABLE_ACCURACY_NOT_SUPPORT_LOCAL_ENGINE);
                }
            }

            if (StringUtils.isEmpty(getFQN(jobParameter))) {
                throw new DataVinesServerException(Status.METRIC_JOB_RELATED_ENTITY_NOT_EXIST);
            }

            if (!isColumn(jobParameter)) {
                return;
            }

            CatalogEntityInstance columnEntity =
                    catalogEntityInstanceService.getByDataSourceAndFQN(datasourceId, getFQN(jobParameter));
            if (columnEntity == null) {
                return;
            }

            if (StringUtils.isEmpty(columnEntity.getProperties())) {
                throw new DataVinesServerException(Status.ENTITY_TYPE_NOT_EXIST);
            }

            ColumnInfo columnInfo = JSONUtils.parseObject(columnEntity.getProperties(), ColumnInfo.class);
            if (columnInfo != null) {
                String columnType = columnInfo.getType();
                DataVinesDataType dataVinesDataType = DataVinesDataType.getType(columnType);
                if (dataVinesDataType == null) {
                    throw new DataVinesServerException(Status.ENTITY_TYPE_NOT_EXIST);
                }

                SqlMetric metric = PluginLoader.getPluginLoader(SqlMetric.class).getOrCreatePlugin(jobParameter.getMetricType());
                if (metric == null) {
                    throw new DataVinesServerException(Status.METRIC_JOB_RELATED_ENTITY_NOT_EXIST, jobParameter.getMetricType().toUpperCase());
                }

                List<DataVinesDataType> suitableTypeList = metric.suitableType();
                if (!suitableTypeList.contains(dataVinesDataType)) {
                    throw new DataVinesServerException(Status.METRIC_NOT_SUITABLE_ENTITY_TYPE, metric.getNameByLanguage(!LanguageUtils.isZhContext()), dataVinesDataType.getName().toUpperCase());
                }
            }
        }
    }

    private boolean isColumn(BaseJobParameter jobParameter) {
        String column = (String)jobParameter.getMetricParameter().get(COLUMN);
        return StringUtils.isNotEmpty(column);
    }

    private String getFQN(BaseJobParameter jobParameter) {
        String fqn = "";
        String database = (String)jobParameter.getMetricParameter().get(DATABASE);
        String table = (String)jobParameter.getMetricParameter().get(TABLE);
        String column = (String)jobParameter.getMetricParameter().get(COLUMN);

        if (StringUtils.isEmpty(database)) {
            return null;
        }

        if (StringUtils.isEmpty(table)) {
            List<String> tables = SqlUtils.extractTablesFromSelect((String) jobParameter.getMetricParameter().get(ACTUAL_AGGREGATE_SQL));
            if (CollectionUtils.isEmpty(tables)) {
                throw new DataVinesException("custom sql must have table");
            }
            fqn = database + "." + tables.get(0);
        } else {
            fqn = database + "." + table;
        }

        if (StringUtils.isEmpty(column)) {
            return fqn;
        } else {
            return fqn + "." + column;
        }
    }

    private List<String> setJobAttribute(Job job, List<BaseJobParameter> jobParameters) {
        List<String> fqnList = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(jobParameters)) {
            BaseJobParameter jobParameter = jobParameters.get(0);
            job.setSchemaName((String)jobParameter.getMetricParameter().get(DATABASE));
            job.setTableName((String)jobParameter.getMetricParameter().get(TABLE));
            job.setColumnName((String)jobParameter.getMetricParameter().get(COLUMN));
            job.setMetricType(jobParameter.getMetricType());
            fqnList.add(getFQN(jobParameter));
        }

        return fqnList;
    }

    @Override
    public Job getById(long id) {
        return baseMapper.selectById(id);
    }

    @Override
    public List<Job> listByDataSourceId(Long dataSourceId) {
        return baseMapper.listByDataSourceId(dataSourceId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteById(long id) {

        catalogEntityMetricJobRelService.deleteByJobId(id);
        jobExecutionService.deleteByJobId(id);
        issueService.deleteByJobId(id);

        JobSchedule jobSchedule = jobScheduleService.getByJobId(id);
        if (jobSchedule != null) {
            jobScheduleService.deleteBySchedule(jobSchedule);
        }
        slaJobService.deleteByJobId(id);

        if (baseMapper.deleteById(id) > 0) {
            return 1;
        } else {
            return 0;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteByDataSourceId(long dataSourceId) {
        List<Job> jobList = listByDataSourceId(dataSourceId);
        if (CollectionUtils.isEmpty(jobList)) {
            return 0;
        }

        jobList.forEach(job -> {
            deleteById(job.getId());
            jobExecutionService.deleteByJobId(job.getId());
        });

        return 1;
    }

    @Override
    public IPage<JobVO> getJobPage(String searchVal,
                                   String schemaSearch,
                                   String tableSearch,
                                   String columnSearch,
                                   String startTime,
                                   String endTime,
                                   Long dataSourceId, Integer type, Integer pageNumber, Integer pageSize) {
        Page<JobVO> page = new Page<>(pageNumber, pageSize);
        IPage<JobVO> jobs = baseMapper.getJobPageSelect(page, searchVal, schemaSearch, tableSearch, columnSearch, startTime, endTime, dataSourceId, type);
        List<JobVO> jobList = jobs.getRecords();
        if (CollectionUtils.isNotEmpty(jobList)) {
            for (JobVO jobVO: jobList) {
                List<SlaVO> slaList = slaService.getSlaByJobId(jobVO.getId());
                jobVO.setSlaList(slaList);
                JobExecutionStat jobExecutionStat = jobExecutionService.getJobExecutionStat(jobVO.getId());
                if (jobExecutionStat != null) {
                    jobVO.setTotalCount(jobExecutionStat.getTotalCount());
                    jobVO.setSuccessCount(jobExecutionStat.getSuccessCount());
                    jobVO.setFailCount(jobExecutionStat.getFailCount());
                    jobVO.setFirstJobExecutionTime(jobExecutionStat.getFirstJobExecutionTime());
                    jobVO.setLastJobExecutionTime(jobExecutionStat.getLastJobExecutionTime());
                }
            }
        }
        return jobs;
    }

    @Override
    public Long execute(Long jobId, LocalDateTime scheduleTime) throws DataVinesServerException {
        Job job = baseMapper.selectById(jobId);
        if (job == null) {
            throw new DataVinesServerException(Status.JOB_NOT_EXIST_ERROR, jobId);
        }

        return executeJob(job, scheduleTime);
    }

    @Override
    public String getJobExecutionConfig(Long jobId, LocalDateTime scheduleTime) throws DataVinesServerException {
        Job job = baseMapper.selectById(jobId);
        if (job == null) {
            throw new DataVinesServerException(Status.JOB_NOT_EXIST_ERROR, jobId);
        }
        JobExecution jobExecution = getJobExecution(job, scheduleTime);
        Map<String, String> inputParameter = new HashMap<>();
        JobExecutionParameter jobExecutionParameter = JSONUtils.parseObject(jobExecution.getParameter(), JobExecutionParameter.class);
        JobExecutionInfo jobExecutionInfo = new JobExecutionInfo(
                jobExecution.getId(), jobExecution.getName(),
                jobExecution.getEngineType(), jobExecution.getEngineParameter(),
                jobExecution.getErrorDataStorageType(), jobExecution.getErrorDataStorageParameter(), jobExecution.getErrorDataFileName(),
                getDefaultConnectionInfo().getType(), JSONUtils.toJsonString(DefaultDataSourceInfoUtils.getDefaultDataSourceConfigMap()),
                jobExecutionParameter);
        DataVinesJobConfig dataVinesJobConfig =
                DataVinesConfigurationManager.generateConfiguration(jobExecution.getJobType(), inputParameter, jobExecutionInfo);
        return PasswordFilterUtils.convertPassword(PWD_PATTERN_1, JSONUtils.toJsonString(dataVinesJobConfig));
    }


    private Long executeJob(Job job, LocalDateTime scheduleTime) {

        JobExecution jobExecution = getJobExecution(job, scheduleTime);

        jobExecutionService.save(jobExecution);

        Map<String, String> parameter = new HashMap<>();
        parameter.put("engine", jobExecution.getEngineType());

        // add a command
        Command command = new Command();
        command.setType(CommandType.START);
        command.setPriority(Priority.MEDIUM);
        command.setJobExecutionId(jobExecution.getId());
        command.setParameter(JSONUtils.toJsonString(parameter));
        commandService.insert(command);

        return jobExecution.getId();
    }

    private JobExecution getJobExecution(Job job, LocalDateTime scheduleTime) {
        String executionParameter = buildJobExecutionParameter(job);

        long jobId = job.getId();
        Env env = envService.getById(job.getEnv());
        String envStr = "";
        if (env != null) {
            envStr = env.getEnv();
        }

        Tenant tenant = tenantService.getById(job.getTenantCode());
        String tenantStr = "";
        if (tenant != null) {
            tenantStr = tenant.getTenant();
        }

        String errorDataStorageType = "";
        String errorDataStorageParameter = "";

        if (job.getIsErrorDataOutputToDataSource()!= null && job.getIsErrorDataOutputToDataSource()) {
            DataSource dataSource = dataSourceService.getDataSourceById(job.getDataSourceId());
            if (dataSource != null) {
                errorDataStorageType = dataSource.getType();
                Map<String,String> errorDataStorageParameterMap = new HashMap<>();
                errorDataStorageParameterMap.put(ERROR_DATA_OUTPUT_TO_DATASOURCE_DATABASE, job.getErrorDataOutputToDataSourceDatabase());
                errorDataStorageParameter  = JSONUtils.toJsonString(errorDataStorageParameterMap);
            }
        } else {
            ErrorDataStorage errorDataStorage = errorDataStorageService.getById(job.getErrorDataStorageId());
            if (errorDataStorage != null) {
                errorDataStorageType = errorDataStorage.getType();
                errorDataStorageParameter  = errorDataStorage.getParam();
            }
        }

        // add a jobExecution
        JobExecution jobExecution = new JobExecution();
        BeanUtils.copyProperties(job, jobExecution);
        jobExecution.setId(null);
        jobExecution.setJobId(jobId);
        jobExecution.setParameter(executionParameter);
        jobExecution.setName(job.getName() + "_" + System.currentTimeMillis());
        jobExecution.setJobType(job.getType());
        jobExecution.setErrorDataStorageType(errorDataStorageType);
        jobExecution.setErrorDataStorageParameter(errorDataStorageParameter);
        jobExecution.setErrorDataFileName(getErrorDataFileName(job.getParameter()));
        jobExecution.setStatus(ExecutionStatus.WAITING_SUMMIT);
        jobExecution.setTenantCode(tenantStr);
        jobExecution.setEnv(envStr);
        jobExecution.setSubmitTime(LocalDateTime.now());
        jobExecution.setScheduleTime(scheduleTime);
        jobExecution.setCreateTime(LocalDateTime.now());
        jobExecution.setUpdateTime(LocalDateTime.now());
        return jobExecution;
    }

    @Override
    public String getJobName(String jobType, String parameter) {
        List<BaseJobParameter> jobParameters = JSONUtils.toList(parameter, BaseJobParameter.class);

        if (CollectionUtils.isEmpty(jobParameters)) {
            throw new DataVinesServerException(Status.JOB_PARAMETER_IS_NULL_ERROR);
        }

        BaseJobParameter baseJobParameter = jobParameters.get(0);
        Map<String,Object> metricParameter = baseJobParameter.getMetricParameter();
        if (MapUtils.isEmpty(metricParameter)) {
            throw new DataVinesServerException(Status.JOB_PARAMETER_IS_NULL_ERROR);
        }

        ResultFormula resultFormula = PluginLoader.getPluginLoader(ResultFormula.class).getOrCreatePlugin(baseJobParameter.getResultFormula());

        String database = (String)metricParameter.get(DATABASE);
        String table = (String)metricParameter.get(TABLE);
        String column = (String)metricParameter.get(COLUMN);
        String metric = baseJobParameter.getMetricType();

        switch (JobType.of(jobType)) {
            case DATA_QUALITY:
                return String.format("%s(%s)", metric.toUpperCase(), resultFormula.getSymbol());
            case DATA_PROFILE:
                return String.format("%s(%s.%s)", JobType.DATA_PROFILE.getDescription(), database, table);
            case DATA_RECONCILIATION:
                return String.format("%s(%s)", metric.toUpperCase(), resultFormula.getSymbol());
            default:
                return String.format("%s[%s.%s.%s]%s", "JOB", database, table, column, System.currentTimeMillis());
        }
    }

    @Override
    public String getJobConfig(Long jobId) {
        SubmitJob submitJob = new SubmitJob();

        Job job = baseMapper.selectById(jobId);
        if  (job == null) {
            throw new DataVinesServerException(Status.JOB_NOT_EXIST_ERROR, jobId);
        }

        JobExecution jobExecution = getJobExecution(job, null);
        jobExecution.setId(System.currentTimeMillis());

        submitJob.setName(jobExecution.getName());
        submitJob.setExecutePlatformType(jobExecution.getExecutePlatformType());
        submitJob.setExecutePlatformParameter(JSONUtils.toMap(jobExecution.getExecutePlatformParameter(), String.class, Object.class));
        submitJob.setEngineType(jobExecution.getEngineType());
        submitJob.setEngineParameter(JSONUtils.toMap(jobExecution.getEngineParameter(), String.class, Object.class));
        submitJob.setParameter(JSONUtils.parseObject(jobExecution.getParameter(), JobExecutionParameter.class));
        submitJob.setErrorDataStorageType(jobExecution.getErrorDataStorageType());
        submitJob.setErrorDataStorageParameter(JSONUtils.toMap(jobExecution.getErrorDataStorageParameter(), String.class, Object.class));
        submitJob.setValidateResultDataStorageType(DefaultDataSourceInfoUtils.getDefaultConnectionInfo().getType());
        submitJob.setValidateResultDataStorageParameter(DefaultDataSourceInfoUtils.getDefaultDataSourceConfigMap());
        submitJob.setLanguageEn(!LanguageUtils.isZhContext());
        if (StringUtils.isNotEmpty(jobExecution.getEnv())) {
            submitJob.setEnv(jobExecution.getEnv());
        }

        List<SlaConfigVO> slaConfigList = slaService.getSlaConfigByJobId(jobId);
        if (CollectionUtils.isNotEmpty(slaConfigList)) {
            List<NotificationParameter> notificationParameterList = new ArrayList<>();
            slaConfigList.forEach(item->{
                NotificationParameter notificationParameter = new NotificationParameter();
                notificationParameter.setType(item.getType());
                notificationParameter.setConfig(JSONUtils.toMap(item.getConfig(), String.class, Object.class));
                notificationParameter.setReceiver(JSONUtils.toMap(item.getReceiver(), String.class, Object.class));
                notificationParameterList.add(notificationParameter);
            });
            submitJob.setNotificationParameters(notificationParameterList);
        }
        return PasswordFilterUtils.convertPassword(PWD_PATTERN_1,JSONUtils.toJsonString(submitJob));
    }

    private String getErrorDataFileName(String parameter) {
        List<BaseJobParameter> jobParameters = JSONUtils.toList(parameter, BaseJobParameter.class);

        if (CollectionUtils.isEmpty(jobParameters)) {
            throw new DataVinesServerException(Status.JOB_PARAMETER_IS_NULL_ERROR);
        }

        BaseJobParameter baseJobParameter = jobParameters.get(0);
        Map<String,Object> metricParameter = baseJobParameter.getMetricParameter();
        if (MapUtils.isEmpty(metricParameter)) {
            throw new DataVinesServerException(Status.JOB_PARAMETER_IS_NULL_ERROR);
        }

        String column = (String)metricParameter.get(COLUMN);
        String metric = baseJobParameter.getMetricType();
        if (StringUtils.isEmpty(column)) {
            return String.format("%s_%s", metric.toLowerCase(), System.currentTimeMillis());
        } else {
            return String.format("%s_%s_%s", metric.toLowerCase(), column, System.currentTimeMillis());
        }
    }

    private String buildJobExecutionParameter(Job job) {
        DataSource dataSource = dataSourceService.getDataSourceById(job.getDataSourceId());
        Map<String, Object> srcSourceConfigMap = JSONUtils.toMap(dataSource.getParam(), String.class, Object.class);
        ConnectionInfo srcConnectionInfo = new ConnectionInfo();
        srcConnectionInfo.setType(dataSource.getType());
        srcConnectionInfo.setConfig(srcSourceConfigMap);

        ConnectionInfo targetConnectionInfo = new ConnectionInfo();
        DataSource dataSource2 = dataSourceService.getDataSourceById(job.getDataSourceId2());
        if (dataSource2 != null) {
            Map<String, Object> targetSourceConfigMap = JSONUtils.toMap(dataSource2.getParam(), String.class, Object.class);
            targetConnectionInfo.setType(dataSource2.getType());
            targetConnectionInfo.setConfig(targetSourceConfigMap);
        }

        return JobExecutionParameterBuilderFactory.builder(job.getType())
                .buildJobExecutionParameter(job.getParameter(), srcConnectionInfo, targetConnectionInfo);
    }

    public void checkDuplicateMetricInJob(List<BaseJobParameter> jobParameters) {
        Map<String,Integer> metricKey2Count = new HashMap<>();
        for (BaseJobParameter baseJobParameter : jobParameters) {
            String metricKey = getMetricUniqueKey(baseJobParameter);
            Integer count = metricKey2Count.get(metricKey);
            if (count != null) {
                metricKey2Count.put(metricKey, ++count);
            } else {
                metricKey2Count.put(metricKey, 1);
            }
        }

        for (Map.Entry<String,Integer> countEntry : metricKey2Count.entrySet()) {
            if (countEntry.getValue() > 1) {
                throw new DataVinesServerException(Status.JOB_PARAMETER_CONTAIN_DUPLICATE_METRIC_ERROR);
            }
        }

    }
    protected String getMetricUniqueKey(BaseJobParameter parameter) {
        return DigestUtils.md5Hex(String.format("%s_%s_%s_%s",
                parameter.getMetricType(),
                parameter.getMetricParameter().get(DATABASE),
                parameter.getMetricParameter().get(TABLE),
                parameter.getMetricParameter().get(COLUMN)));
    }
}
