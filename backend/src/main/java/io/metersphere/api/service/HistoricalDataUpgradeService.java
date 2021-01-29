package io.metersphere.api.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.metersphere.api.dto.EnvironmentDTO;
import io.metersphere.api.dto.SaveHistoricalDataUpgrade;
import io.metersphere.api.dto.automation.ScenarioStatus;
import io.metersphere.api.dto.definition.request.MsScenario;
import io.metersphere.api.dto.definition.request.MsTestElement;
import io.metersphere.api.dto.definition.request.assertions.MsAssertions;
import io.metersphere.api.dto.definition.request.controller.MsIfController;
import io.metersphere.api.dto.definition.request.extract.MsExtract;
import io.metersphere.api.dto.definition.request.processors.post.MsJSR223PostProcessor;
import io.metersphere.api.dto.definition.request.processors.pre.MsJSR223PreProcessor;
import io.metersphere.api.dto.definition.request.sampler.MsDubboSampler;
import io.metersphere.api.dto.definition.request.sampler.MsHTTPSamplerProxy;
import io.metersphere.api.dto.definition.request.sampler.MsJDBCSampler;
import io.metersphere.api.dto.definition.request.sampler.MsTCPSampler;
import io.metersphere.api.dto.definition.request.timer.MsConstantTimer;
import io.metersphere.api.dto.scenario.Body;
import io.metersphere.api.dto.scenario.KeyValue;
import io.metersphere.api.dto.scenario.Scenario;
import io.metersphere.api.dto.scenario.environment.EnvironmentConfig;
import io.metersphere.api.dto.scenario.request.*;
import io.metersphere.base.domain.*;
import io.metersphere.base.mapper.ApiScenarioMapper;
import io.metersphere.base.mapper.ApiTestMapper;
import io.metersphere.base.mapper.ext.ExtApiScenarioMapper;
import io.metersphere.commons.utils.BeanUtils;
import io.metersphere.commons.utils.DateUtils;
import io.metersphere.commons.utils.LogUtil;
import io.metersphere.commons.utils.SessionUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.*;

@Service
@Transactional(rollbackFor = Exception.class)
public class HistoricalDataUpgradeService {
    @Resource
    private ApiTestMapper apiTestMapper;
    @Resource
    private ExtApiScenarioMapper extApiScenarioMapper;
    @Resource
    SqlSessionFactory sqlSessionFactory;
    @Resource
    ApiTestEnvironmentService apiTestEnvironmentService;
    private Map<String, EnvironmentDTO> environmentDTOMap;

    private int getNextNum(String projectId) {
        ApiScenario apiScenario = extApiScenarioMapper.getNextNum(projectId);
        if (apiScenario == null) {
            return 100001;
        } else {
            return Optional.of(apiScenario.getNum() + 1).orElse(100001);
        }
    }

    private MsScenario createScenarioByTest(ApiTest test) {
        MsScenario scenario = new MsScenario();
        scenario.setName(test.getName());
        scenario.setReferenced("Upgrade");
        scenario.setResourceId(UUID.randomUUID().toString());
        scenario.setId(test.getId());
        return scenario;
    }

    private MsScenario createScenario(Scenario oldScenario) {
        MsScenario scenario = new MsScenario();
        scenario.setOldVariables(oldScenario.getVariables());
        scenario.setName(oldScenario.getName());
        scenario.setEnableCookieShare(oldScenario.isEnableCookieShare());
        scenario.setEnvironmentId(oldScenario.getEnvironmentId());
        scenario.setReferenced("Upgrade");
        scenario.setId(oldScenario.getId());
        scenario.setResourceId(UUID.randomUUID().toString());
        LinkedList<MsTestElement> testElements = new LinkedList<>();
        int index = 1;
        for (Request request : oldScenario.getRequests()) {
            // 条件控制器
            MsIfController ifController = null;
            if (request.getController() != null && StringUtils.isNotEmpty(request.getController().getValue())
                    && StringUtils.isNotEmpty(request.getController().getVariable())) {
                ifController = new MsIfController();
                BeanUtils.copyBean(ifController, request.getController());
                ifController.setType("IfController");
                ifController.setName("IfController");
                ifController.setIndex(index + "");
                ifController.setHashTree(new LinkedList<>());
                ifController.setResourceId(UUID.randomUUID().toString());
            }
            // 等待控制器
            if (request.getTimer() != null && StringUtils.isNotEmpty(request.getTimer().getDelay())) {
                MsConstantTimer constantTimer = new MsConstantTimer();
                BeanUtils.copyBean(constantTimer, request.getTimer());
                constantTimer.setType("ConstantTimer");
                constantTimer.setIndex(index + "");
                constantTimer.setHashTree(new LinkedList<>());
                constantTimer.setResourceId(UUID.randomUUID().toString());
                testElements.add(constantTimer);
            }

            MsTestElement element = null;
            if (request instanceof HttpRequest) {
                element = new MsHTTPSamplerProxy();
                HttpRequest request1 = (HttpRequest) request;
                if (StringUtils.isEmpty(request1.getPath()) && StringUtils.isNotEmpty(request1.getUrl())) {
                    try {
                        URL urlObject = new URL(request1.getUrl());
                        String envPath = StringUtils.equals(urlObject.getPath(), "/") ? "" : urlObject.getPath();
                        request1.setPath(envPath);
                        request1.setUrl(null);
                    } catch (Exception ex) {
                        LogUtil.error(ex.getMessage());
                    }
                } else {
                    request1.setUrl(null);
                }
                if (request1.getBody() != null) {
                    request1.getBody().setBinary(new ArrayList<>());
                    if (request1.getBody().isOldKV()) {
                        request1.getBody().setType(Body.FORM_DATA);
                    }
                    if ("json".equals(request1.getBody().getFormat())) {
                        if ("Raw".equals(request1.getBody().getType())) {
                            request1.getBody().setType(Body.JSON);
                        }
                        if (CollectionUtils.isEmpty(request1.getHeaders())) {
                            List<KeyValue> headers = new LinkedList<>();
                            headers.add(new KeyValue("Content-Type", "application/json"));
                            request1.setHeaders(headers);
                        } else {
                            boolean isJsonType = false;
                            for (KeyValue keyValue : request1.getHeaders()) {
                                if ("Content-Type".equals(keyValue.getName())) {
                                    isJsonType = true;
                                    break;
                                }
                            }
                            if (!isJsonType) {
                                request1.getHeaders().set(request1.getHeaders().size() - 1, new KeyValue("Content-Type", "application/json"));
                            }
                        }
                    }
                    if ("xml".equals(request1.getBody().getFormat())) {
                        request1.getBody().setType(Body.XML);
                    }
                }
                BeanUtils.copyBean(element, request1);
                ((MsHTTPSamplerProxy) element).setProtocol(RequestType.HTTP);
                ((MsHTTPSamplerProxy) element).setArguments(request1.getParameters());
                List<KeyValue> keyValues = new LinkedList<>();
                keyValues.add(new KeyValue("", ""));
                ((MsHTTPSamplerProxy) element).setRest(keyValues);
                if (StringUtils.isEmpty(element.getName())) {
                    element.setName(request1.getPath());
                }
                element.setType("HTTPSamplerProxy");
            }
            if (request instanceof DubboRequest) {
                String requestJson = JSON.toJSONString(request);
                element = JSON.parseObject(requestJson, MsDubboSampler.class);
                element.setType("DubboSampler");
            }
            if (request instanceof SqlRequest) {
                element = new MsJDBCSampler();
                SqlRequest request1 = (SqlRequest) request;
                BeanUtils.copyBean(element, request1);
                EnvironmentDTO dto = environmentDTOMap.get(request1.getDataSource());
                if (dto != null) {
                    ((MsJDBCSampler) element).setEnvironmentId(dto.getEnvironmentId());
                    ((MsJDBCSampler) element).setDataSourceId(dto.getDatabaseConfig().getId());
                    ((MsJDBCSampler) element).setDataSource(dto.getDatabaseConfig());
                }
                element.setType("JDBCSampler");
            }
            if (request instanceof TCPRequest) {
                element = new MsTCPSampler();
                TCPRequest request1 = (TCPRequest) request;
                BeanUtils.copyBean(element, request1);
                element.setType("TCPSampler");
            }
            element.setIndex(index + "");
            element.setResourceId(UUID.randomUUID().toString());
            element.setHashTree(new LinkedList<>());
            LinkedList<MsTestElement> msTestElements = new LinkedList<>();
            // 断言规则
            if (request.getAssertions() != null && ((request.getAssertions().getDuration() != null && request.getAssertions().getDuration().getValue() > 0) ||
                    CollectionUtils.isNotEmpty(request.getAssertions().getJsonPath()) || CollectionUtils.isNotEmpty(request.getAssertions().getJsr223()) ||
                    CollectionUtils.isNotEmpty(request.getAssertions().getRegex()) || CollectionUtils.isNotEmpty(request.getAssertions().getXpath2()))) {
                String assertions = JSON.toJSONString(request.getAssertions());
                MsAssertions msAssertions = JSON.parseObject(assertions, MsAssertions.class);
                if (StringUtils.isEmpty(msAssertions.getName())) {
                    msAssertions.setName("Assertions");
                }
                msAssertions.setType("Assertions");
                msAssertions.setIndex(index + "");
                msAssertions.setResourceId(UUID.randomUUID().toString());
                msAssertions.setHashTree(new LinkedList<>());
                msTestElements.add(msAssertions);
            }
            // 提取规则
            if (request.getExtract() != null && (CollectionUtils.isNotEmpty(request.getExtract().getJson()) ||
                    CollectionUtils.isNotEmpty(request.getExtract().getRegex()) || CollectionUtils.isNotEmpty(request.getExtract().getXpath()))) {
                String extractJson = JSON.toJSONString(request.getExtract());
                MsExtract extract = JSON.parseObject(extractJson, MsExtract.class);
                if (StringUtils.isEmpty(extract.getName())) {
                    extract.setName("Extract");
                }
                extract.setType("Extract");
                extract.setIndex(index + "");
                extract.setHashTree(new LinkedList<>());
                extract.setResourceId(UUID.randomUUID().toString());
                msTestElements.add(extract);
            }
            // 前置脚本
            if (request.getJsr223PreProcessor() != null && StringUtils.isNotEmpty(request.getJsr223PreProcessor().getScript())) {
                String preJson = JSON.toJSONString(request.getJsr223PreProcessor());
                MsJSR223PreProcessor preProcessor = JSON.parseObject(preJson, MsJSR223PreProcessor.class);
                if (StringUtils.isEmpty(preProcessor.getName())) {
                    preProcessor.setName("JSR223PreProcessor");
                }
                preProcessor.setType("JSR223PreProcessor");
                preProcessor.setIndex(index + "");
                preProcessor.setHashTree(new LinkedList<>());
                preProcessor.setResourceId(UUID.randomUUID().toString());
                msTestElements.add(preProcessor);
            }
            // 后置脚本
            if (request.getJsr223PostProcessor() != null && StringUtils.isNotEmpty(request.getJsr223PostProcessor().getScript())) {
                String preJson = JSON.toJSONString(request.getJsr223PostProcessor());
                MsJSR223PostProcessor preProcessor = JSON.parseObject(preJson, MsJSR223PostProcessor.class);
                if (StringUtils.isEmpty(preProcessor.getName())) {
                    preProcessor.setName("JSR223PostProcessor");
                }
                preProcessor.setType("JSR223PostProcessor");
                preProcessor.setIndex(index + "");
                preProcessor.setHashTree(new LinkedList<>());
                preProcessor.setResourceId(UUID.randomUUID().toString());
                msTestElements.add(preProcessor);
            }
            if (CollectionUtils.isNotEmpty(msTestElements)) {
                element.setHashTree(msTestElements);
            }
            if (ifController != null) {
                LinkedList<MsTestElement> elements = new LinkedList<>();
                elements.add(element);
                ifController.setHashTree(elements);
                testElements.add(ifController);
            } else {
                testElements.add(element);
            }
            index++;
        }
        scenario.setHashTree(testElements);
        return scenario;
    }

    private ApiScenarioWithBLOBs getScenario(String oldScenarioId, ApiScenarioMapper mapper) {
        ApiScenarioExample example = new ApiScenarioExample();
        example.createCriteria().andIdEqualTo(oldScenarioId);
        List<ApiScenarioWithBLOBs> list = mapper.selectByExampleWithBLOBs(example);
        if (list.size() > 0) {
            return list.get(0);
        }
        return null;
    }

    private static final String BODY_FILE_DIR = "/opt/metersphere/data/body";

    //文件的拷贝
    public static void copyFile(String sourcePath, String newPath) {
        try (FileChannel inChannel = new FileInputStream(new File(sourcePath)).getChannel();
             FileChannel outChannel = new FileOutputStream(new File(newPath)).getChannel();) {
            inChannel.transferTo(0, inChannel.size(), outChannel);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void copyDir(String sourcePathDir, String newPathDir) {
        File start = new File(sourcePathDir);
        File end = new File(newPathDir);
        String[] filePath = start.list();
        if (!end.exists()) {
            end.mkdir();
        }
        if (filePath != null) {
            for (String temp : filePath) {
                //添加满足情况的条件
                if (new File(sourcePathDir + File.separator + temp).isFile()) {
                    //为文件则进行拷贝
                    copyFile(sourcePathDir + File.separator + temp, newPathDir + File.separator + temp);
                }
            }
        }
    }

    private void createBodyFiles(String testId) {
        String dir = BODY_FILE_DIR + "/" + testId;
        File testDir = new File(dir);
        if (testDir.exists()) {
            testDir.mkdirs();
        }
        copyDir(dir, BODY_FILE_DIR);
    }

    private void createApiScenarioWithBLOBs(SaveHistoricalDataUpgrade saveHistoricalDataUpgrade, String id, String name, int total, String scenarioDefinition, ApiScenarioMapper mapper, int num) {
        if (StringUtils.isEmpty(name)) {
            name = "默认名称-" + DateUtils.getTimeStr(System.currentTimeMillis());
        }
        ApiScenarioWithBLOBs scenario = getScenario(id, mapper);
        if (scenario != null) {
            scenario.setName(name);
            scenario.setProjectId(saveHistoricalDataUpgrade.getProjectId());
            scenario.setTags(scenario.getTags());
            scenario.setLevel("P0");
            scenario.setModulePath(saveHistoricalDataUpgrade.getModulePath());
            scenario.setApiScenarioModuleId(saveHistoricalDataUpgrade.getModuleId());
            scenario.setPrincipal(Objects.requireNonNull(SessionUtils.getUser()).getId());
            scenario.setStepTotal(total);
            scenario.setScenarioDefinition(scenarioDefinition);
            scenario.setUpdateTime(System.currentTimeMillis());
            scenario.setStatus(ScenarioStatus.Underway.name());
            scenario.setUserId(SessionUtils.getUserId());
            mapper.updateByPrimaryKeySelective(scenario);
        } else {
            scenario = new ApiScenarioWithBLOBs();
            scenario.setId(id);
            scenario.setName(name);
            scenario.setProjectId(saveHistoricalDataUpgrade.getProjectId());
            scenario.setTags(scenario.getTags());
            scenario.setLevel("P0");
            scenario.setModulePath(saveHistoricalDataUpgrade.getModulePath());
            scenario.setApiScenarioModuleId(saveHistoricalDataUpgrade.getModuleId());
            scenario.setPrincipal(Objects.requireNonNull(SessionUtils.getUser()).getId());
            scenario.setStepTotal(total);
            scenario.setScenarioDefinition(scenarioDefinition);
            scenario.setCreateTime(System.currentTimeMillis());
            scenario.setUpdateTime(System.currentTimeMillis());
            scenario.setStatus(ScenarioStatus.Underway.name());
            scenario.setUserId(SessionUtils.getUserId());
            scenario.setNum(num);
            mapper.insert(scenario);
        }
    }

    public String upgrade(SaveHistoricalDataUpgrade saveHistoricalDataUpgrade) {
        // 初始化环境，获取数据源
        getDataSource(saveHistoricalDataUpgrade.getProjectId());

        ApiTestExample example = new ApiTestExample();
        example.createCriteria().andIdIn(saveHistoricalDataUpgrade.getTestIds());
        List<ApiTest> blobs = apiTestMapper.selectByExampleWithBLOBs(example);
        SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH);
        ApiScenarioMapper mapper = sqlSession.getMapper(ApiScenarioMapper.class);
        int num = getNextNum(saveHistoricalDataUpgrade.getProjectId());
        for (ApiTest test : blobs) {
            // 附件迁移
            createBodyFiles(test.getId());
            // 把test 生成一个场景，旧场景数据变成引用步骤
            MsScenario scenarioTest = createScenarioByTest(test);
            LinkedList<MsTestElement> listSteps = new LinkedList<>();
            List<Scenario> scenarios = JSON.parseArray(test.getScenarioDefinition(), Scenario.class);
            if (CollectionUtils.isNotEmpty(scenarios)) {
                // 批量处理
                for (Scenario scenario : scenarios) {
                    scenario.setId(test.getId() + "=" + scenario.getId());
                    scenario.setName(test.getName() + "_" + scenario.getName());
                    MsScenario scenario1 = createScenario(scenario);
                    String scenarioDefinition = JSON.toJSONString(scenario1);
                    num++;
                    createApiScenarioWithBLOBs(saveHistoricalDataUpgrade, scenario.getId(), scenario.getName(), scenario.getRequests().size(), scenarioDefinition, mapper, num);
                    MsScenario step = new MsScenario();
                    step.setId(scenario1.getId());
                    step.setName(scenario1.getName());
                    step.setEnable(scenario.isEnable());
                    step.setType("scenario");
                    step.setResourceId(UUID.randomUUID().toString());
                    step.setReferenced("REF");
                    listSteps.add(step);
                }
            }
            num++;
            scenarioTest.setHashTree(listSteps);
            String scenarioDefinition = JSON.toJSONString(scenarioTest);
            createApiScenarioWithBLOBs(saveHistoricalDataUpgrade, scenarioTest.getId(), scenarioTest.getName(), listSteps.size(), scenarioDefinition, mapper, num);
        }
        sqlSession.flushStatements();
        return null;
    }

    private void getDataSource(String projectId) {
        List<ApiTestEnvironmentWithBLOBs> environments = apiTestEnvironmentService.list(projectId);
        environmentDTOMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(environments)) {
            environments.forEach(environment -> {
                if (environment != null && environment.getConfig() != null) {
                    EnvironmentConfig envConfig = JSONObject.parseObject(environment.getConfig(), EnvironmentConfig.class);
                    if (CollectionUtils.isNotEmpty(envConfig.getDatabaseConfigs())) {
                        envConfig.getDatabaseConfigs().forEach(item -> {
                            EnvironmentDTO dto = new EnvironmentDTO();
                            dto.setDatabaseConfig(item);
                            dto.setEnvironmentId(environment.getId());
                            environmentDTOMap.put(item.getId(), dto);
                        });
                    }
                }
            });
        }
    }

}
